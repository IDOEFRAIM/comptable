package com.example.comptable.banque;

import com.example.comptable.Benchmark;
import com.example.comptable.banque.BanqueDto.StatutTransaction;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.controle.ControlCodes;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume : 5 000 transactions bancaires (4 500 exactes, 300 probables, 100 ambigues, 100 sans correspondance) sur un vrai PostgreSQL.
 * Les ecritures sont generees en SQL a partir d'un modele reel (le moteur comptable n'est pas le sujet) ; l'import, le rapprochement, les
 * controles et la lecture de l'ecran passent par le code de production. Les durees sont MESUREES et affichees ; les bornes ne servent qu'a
 * detecter une derive grossiere (requete par ligne, balayage quadratique).
 */
class BanqueVolumeTest extends BankTestBase {

    // Echelle fonctionnelle reduite ; 5 000 transactions et bornes de duree uniquement avec -Dbenchmark=true. Les exceptions restent AU-DESSUS du seuil
    // d'agregation (50 par releve) pour verifier qu'elles sont resumees en un constat par nature, pas une ligne par operation.
    private static final int EXACTES = Benchmark.taille(4500, 400);
    private static final int PROBABLES = Benchmark.taille(300, 60);
    private static final int AMBIGUES = Benchmark.taille(100, 55);
    private static final int SANS = Benchmark.taille(100, 55);
    private static final int TOTAL = EXACTES + PROBABLES + AMBIGUES + SANS;

    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired BankTransactionQueryService queryService;
    @Autowired EntityManagerFactory entityManagerFactory;

    private void genererEcritures(UUID entrepriseId, UUID modeleEcriture, UUID compteBanque) {
        Map<String, Object> modele = jdbc.queryForMap("select exercice_id, journal_id from ecriture_comptable where id = ?", modeleEcriture);
        Map<String, Object> ligneTiers = jdbc.queryForMap("select compte_id, tiers_id from ligne_ecriture l where l.ecriture_id = ? and l.debit > 0", modeleEcriture);
        String tiers = ligneTiers.get("tiers_id") != null ? "'" + ligneTiers.get("tiers_id") + "'" : "null";
        String seed = entrepriseId.toString();
        // groupe, debut du montant, nombre de montants, copies par montant (2 = deux mouvements identiques : ambigu)
        Object[][] groupes = {{"E", 1000, EXACTES, 1}, {"P", 100000, PROBABLES, 1}, {"A", 200000, AMBIGUES, 2}};
        for (Object[] g : groupes) {
            String prefixe = (String) g[0];
            int depart = (Integer) g[1];
            int n = (Integer) g[2];
            int copies = (Integer) g[3];
            String serie = "from generate_series(0, " + (n - 1) + ") i cross join generate_series(1, " + copies + ") c";
            String montant = "(" + depart + " + i * 3)";
            String idEcriture = "md5('" + seed + "-" + prefixe + "-' || i || '-' || c)::uuid";
            jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) "
                    + "select " + idEcriture + ", '" + entrepriseId + "', '" + modele.get("exercice_id") + "', '" + modele.get("journal_id") + "', "
                    + "'VOL-" + prefixe + "-' || i || '-' || c, current_date, 'Reglement fournisseur', 'VALIDEE' " + serie);
            jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, tiers_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "-" + prefixe
                    + "-' || i || '-' || c || '-1')::uuid, " + idEcriture + ", '" + ligneTiers.get("compte_id") + "', " + tiers + ", 'Reglement', " + montant + ", 0, 1 " + serie);
            jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, tiers_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "-" + prefixe
                    + "-' || i || '-' || c || '-2')::uuid, " + idEcriture + ", '" + compteBanque + "', null, 'Banque', 0, " + montant + ", 2 " + serie);
        }
    }

    private String releveCsv() {
        LocalDate jour = LocalDate.now();
        StringBuilder sb = new StringBuilder("date;libelle;montant\n");
        for (int i = 0; i < EXACTES; i++) {
            sb.append(jour).append(";VIR STATION XYZ ").append(i).append(";-").append(1000 + i * 3).append('\n');
        }
        for (int i = 0; i < PROBABLES; i++) {
            sb.append(jour.plusDays(10)).append(";VIR PROBABLE ").append(i).append(";-").append(100000 + i * 3).append('\n');
        }
        for (int i = 0; i < AMBIGUES; i++) {
            sb.append(jour).append(";VIR AMBIGU ").append(i).append(";-").append(200000 + i * 3).append('\n');
        }
        for (int i = 0; i < SANS; i++) {
            sb.append(jour).append(";PAIEMENT SANS ECRITURE ").append(i).append(";-").append(900000 + i * 3).append('\n');
        }
        return sb.toString();
    }

    private Statistics statistiques() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void cinqMilleTransactions_sontRapprocheesEnTempsRaisonnable_etLInboxNeRecoitQueLesExceptions() {
        Dossier d = nouveauDossier(false);
        Reglement modele = reglement(d, 1, LocalDate.now(), "Modele");
        genererEcritures(d.id(), modele.ecritureId(), modele.compteBanqueId());
        Benchmark.preparer(jdbc);
        MockMultipartFile fichier = new MockMultipartFile("fichier", "gros-releve.csv", "text/csv", releveCsv().getBytes(StandardCharsets.UTF_8));
        BankStatementImporter.DemandeImport demande = new BankStatementImporter.DemandeImport(modele.compteBanqueId(), "Gros releve", null, null, null, null, null);

        long t0 = System.nanoTime();
        var resultat = rapprochementService.importerComplet(d.id(), demande, fichier, d.expert());
        long importMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(resultat.lot().getImportedRows()).isEqualTo(TOTAL);

        Benchmark.preparer(jdbc);
        long t1 = System.nanoTime();
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        long controleMs = (System.nanoTime() - t1) / 1_000_000;

        statistiques().clear();
        long t2 = System.nanoTime();
        var synthese = queryService.synthese(d.id(), null, null, d.expert());
        var page = queryService.lister(d.id(), null, null, StatutTransaction.A_CONFIRMER, null, 0, 25, d.expert());
        long lectureMs = (System.nanoTime() - t2) / 1_000_000;
        long requetesLecture = statistiques().getPrepareStatementCount();

        System.out.printf("VOLUME-BANQUE import=%d ms, controles=%d ms, lecture ecran=%d ms (%d requetes SQL)%n", importMs, controleMs, lectureMs, requetesLecture);

        assertThat(synthese.transactions()).isEqualTo(TOTAL);
        assertThat(synthese.rapprochees()).isEqualTo(EXACTES);
        assertThat(synthese.aConfirmer()).isEqualTo(PROBABLES);
        assertThat(synthese.ambigues()).isEqualTo(AMBIGUES);
        assertThat(synthese.sansCorrespondance()).isEqualTo(SANS);
        assertThat(synthese.tauxRapproche()).isEqualTo(Math.round(EXACTES * 1000.0 / TOTAL) / 10.0);
        assertThat(page.total()).isEqualTo(PROBABLES);
        assertThat(page.lignes()).hasSize(25);
        assertThat(page.lignes().get(0).proposition().confiance()).isNotNull();

        // Les 4 500 cas certains sont rapproches sans un seul constat ; les exceptions sont resumees par releve (3 constats, pas 500).
        List<?> constatsBancaires = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMATCHED_PAYMENT, ControlCodes.AMBIGUOUS_BANK_MATCH,
                ControlCodes.MATCH_TO_CONFIRM));
        assertThat(constatsBancaires).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from bank_reconciliation where entreprise_id = ? and status = 'CONFIRMED' and source = 'SYSTEM_EXACT'",
                Long.class, d.id())).isEqualTo(EXACTES);
        assertThat(jdbc.queryForObject("select count(*) from bank_reconciliation where entreprise_id = ? and status = 'PROPOSED'", Long.class, d.id()))
                .isEqualTo(PROBABLES);

        // Pas de requete par ligne : la lecture de l'ecran reste a quelques dizaines de requetes pour 5 000 transactions.
        assertThat(requetesLecture).isLessThan(120);
        // Durees : uniquement en benchmark (statistiques a jour, machine dediee) ; jamais un test metier rouge sur une machine chargee.
        if (Benchmark.actif()) {
            assertThat(importMs).isLessThan(180_000);
            assertThat(controleMs).isLessThan(120_000);
            assertThat(lectureMs).isLessThan(30_000);
        }

        // Rejouer ne change rien (idempotence a grande echelle).
        var rejoue = rapprochementService.importerComplet(d.id(), demande, fichier, d.expert());
        assertThat(rejoue.releve()).isNull();
        assertThat(rejoue.lot().getExactDuplicates()).isEqualTo(TOTAL);
    }
}
