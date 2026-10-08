package com.example.comptable.investigation;

import com.example.comptable.Benchmark;
import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.revue.RevueComptableService;
import com.example.comptable.controle.revue.RevueDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume de la Phase E sur PostgreSQL : 10 000 ecritures, 2 000 factures d'un MEME client, 5 000 transactions bancaires, 3 000 documents. Mesures : les
 * exceptions restent tres inferieures aux operations ; un clic sur « Pourquoi ? » ne charge jamais le grand livre (requetes, duree, taille de la reponse
 * mesurees) ; la pre-cloture n'a pas de requete par ligne. Les chiffres sont affiches ; les bornes ne detectent que les derives grossieres.
 */
class RevisionVolumeTest extends BankTestBase {

    // Echelle fonctionnelle reduite ; 10 000 ecritures / 5 000 transactions / 3 000 documents uniquement avec -Dbenchmark=true.
    private static final int FACTURES = Benchmark.taille(2000, 200);
    private static final int REGLEMENTS = Benchmark.taille(1800, 180);
    private static final int DIVERSES = Benchmark.taille(6200, 620);
    private static final int TRANSACTIONS = Benchmark.taille(5000, 500);
    private static final int DOCUMENTS = Benchmark.taille(3000, 300);

    @Autowired FacturationService facturationService;
    @Autowired RevueComptableService revueService;
    @Autowired AccountingInvestigationService investigation;
    @Autowired ClotureReadinessService readinessService;
    @Autowired DocumentRepository documentRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired tools.jackson.databind.json.JsonMapper json;

    private Statistics statistiques() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private record Mesure(String nom, long ms, long requetes, long octets) {
    }

    private Mesure mesurer(String nom, Supplier<Object> action) {
        statistiques().clear();
        long t = System.nanoTime();
        Object r = action.get();
        long ms = (System.nanoTime() - t) / 1_000_000;
        return new Mesure(nom, ms, statistiques().getPrepareStatementCount(), json.writeValueAsBytes(r).length);
    }

    @Test
    void dixMilleEcritures_lesExceptionsSontPeuNombreuses_etLeDebuggerNeChargePasLeGrandLivre() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Gros Client", null, d.expert());
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        FactureDto modele = facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now(),
                LocalDate.now().plusDays(30), "Modele", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal("1000"), BigDecimal.ZERO, null))),
                d.expert()).id(), produit, d.expert());
        Reglement reglementModele = reglement(d, 1_000, LocalDate.now(), "Modele banque");
        UUID compteClient = jdbc.queryForObject("select l.compte_id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' "
                + "and l.debit > 0", UUID.class, modele.ecritureId());
        UUID compteBanque = reglementModele.compteBanqueId();
        Map<String, Object> venteModele = jdbc.queryForMap("select exercice_id, journal_id from ecriture_comptable where id = ?", modele.ecritureId());
        Map<String, Object> banqueModele = jdbc.queryForMap("select journal_id from ecriture_comptable where id = ?", reglementModele.ecritureId());
        String seed = d.id().toString();
        String e = "'" + d.id() + "'";
        String exercice = "'" + venteModele.get("exercice_id") + "'";
        UUID compteCharge = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "601").orElseThrow().getId();

        // 2 000 factures du meme client (debit 411 / credit 701), 1 800 reglements (debit banque / credit 411), 6 200 ecritures diverses.
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) select md5('" + seed + "F' || i)::uuid, " + e
                + ", " + exercice + ", '" + venteModele.get("journal_id") + "', 'VF-' || i, current_date - (i % 120), 'Vente ' || i, 'VALIDEE' from generate_series(1, " + FACTURES + ") i");
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "FL1' || i)::uuid, md5('" + seed + "F' || i)::uuid, '"
                + compteClient + "', 'Client', 1000 + i, 0, 1 from generate_series(1, " + FACTURES + ") i");
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "FL2' || i)::uuid, md5('" + seed + "F' || i)::uuid, '"
                + produit + "', 'Produit', 0, 1000 + i, 2 from generate_series(1, " + FACTURES + ") i");
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) select md5('" + seed + "R' || i)::uuid, " + e
                + ", " + exercice + ", '" + banqueModele.get("journal_id") + "', 'VR-' || i, current_date - (i % 100), 'Encaissement ' || i, 'VALIDEE' from generate_series(1, " + REGLEMENTS + ") i");
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "RL1' || i)::uuid, md5('" + seed + "R' || i)::uuid, '"
                + compteClient + "', 'Client', 0, 1000 + i, 1 from generate_series(1, " + REGLEMENTS + ") i");
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "RL2' || i)::uuid, md5('" + seed + "R' || i)::uuid, '"
                + compteBanque + "', 'Banque', 1000 + i, 0, 2 from generate_series(1, " + REGLEMENTS + ") i");
        jdbc.update("insert into payment_allocation (id, entreprise_id, payment_line_id, invoice_line_id, allocated_amount, currency, source, status, created_at) select gen_random_uuid(), " + e
                + ", md5('" + seed + "RL1' || i)::uuid, md5('" + seed + "FL1' || i)::uuid, 1000 + i, 'XOF', 'USER', 'ACTIVE', now() from generate_series(1, " + REGLEMENTS + ") i");
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) select md5('" + seed + "D' || i)::uuid, " + e
                + ", " + exercice + ", '" + venteModele.get("journal_id") + "', 'VD-' || i, current_date - (i % 200), 'Operation diverse ' || i, 'VALIDEE' from generate_series(1, " + DIVERSES + ") i");
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "DL1' || i)::uuid, md5('" + seed + "D' || i)::uuid, '"
                + compteCharge + "', 'Charge', 500, 0, 1 from generate_series(1, " + DIVERSES + ") i");
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select md5('" + seed + "DL2' || i)::uuid, md5('" + seed + "D' || i)::uuid, '"
                + produit + "', 'Produit', 0, 500, 2 from generate_series(1, " + DIVERSES + ") i");

        // 5 000 transactions de releve : 1 800 pointees a un encaissement, 3 200 sans correspondance.
        UUID releve = UUID.randomUUID();
        jdbc.update("insert into releve_bancaire (id, entreprise_id, compte_id, libelle, date_debut, date_fin, solde_debut, solde_fin, created_at) values (?, ?, ?, 'Releve volume', ?, ?, 0, 0, now())",
                releve, d.id(), compteBanque, LocalDate.now().minusDays(120), LocalDate.now());
        jdbc.update("insert into ligne_releve (id, releve_id, date_operation, libelle, montant, entreprise_id, compte_id, currency, direction, libelle_normalise, reference, source, "
                + "content_fingerprint, duplicate_status, ligne_ecriture_id) select gen_random_uuid(), ?, current_date - (i % 100), 'ENCAISSEMENT ' || i, case when i <= " + REGLEMENTS
                + " then 1000 + i else -(9000000 + i) end, ?, ?, 'XOF', case when i <= " + REGLEMENTS + " then 'CREDIT' else 'DEBIT' end, 'ENCAISSEMENT ' || i, null, 'CSV', md5(? || i), 'NONE', "
                + "case when i <= " + REGLEMENTS + " then md5('" + seed + "RL2' || i)::uuid else null end from generate_series(1, " + TRANSACTIONS + ") i", releve, d.id(), compteBanque, seed);

        // 3 000 documents traites jamais comptabilises.
        List<Document> documents = new ArrayList<>();
        var entreprise = entrepriseRepository.findById(d.id()).orElseThrow();
        for (int i = 0; i < DOCUMENTS; i++) {
            Document doc = new Document();
            doc.setTenant(entreprise.getTenant());
            doc.setEntreprise(entreprise);
            doc.setTypeDocument(TypeDocument.FACTURE_ACHAT);
            doc.setCanalSource(CanalSource.WEB);
            doc.setCheminFichier("volume/doc-" + i + ".pdf");
            doc.setStatut(StatutDocument.TRAITE);
            doc.setMontantTtc(new BigDecimal(1000 + i));
            doc.setMontantHt(new BigDecimal(1000 + i));
            doc.setMontantTva(BigDecimal.ZERO);
            doc.setDatePiece(LocalDate.now().minusDays(i % 90));
            doc.setNumeroFacture("FA-" + i);
            documents.add(doc);
        }
        documentRepository.saveAll(documents);
        Benchmark.preparer(jdbc);
        long ecritures = jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ?", Long.class, d.id());

        // Controles : les exceptions restent tres inferieures aux operations (resumes par releve / par entreprise, jamais une ligne par operation).
        long t0 = System.nanoTime();
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        long controlesMs = (System.nanoTime() - t0) / 1_000_000;
        long constats = jdbc.queryForObject("select count(*) from finding where entreprise_id = ? and status in ('OPEN', 'IN_PROGRESS')", Long.class, d.id());

        List<Mesure> mesures = new ArrayList<>();
        mesures.add(mesurer("espace de revision", () -> revueService.espace(d.id(), d.expert())));
        mesures.add(mesurer("pre-cloture", () -> readinessService.evaluer(d.id(), d.expert())));
        Mesure tiers = mesurer("pourquoi : tiers (2 000 factures)", () -> investigation.investiguer(d.id(), "TIERS", client.id(), null, d.expert()));
        mesures.add(tiers);
        mesures.add(mesurer("pourquoi : releve (5 000 transactions)", () -> investigation.investiguer(d.id(), "RELEVE", releve, null, d.expert())));
        UUID transactionSansEcriture = jdbc.queryForObject("select id from ligne_releve where releve_id = ? and ligne_ecriture_id is null limit 1", UUID.class, releve);
        mesures.add(mesurer("pourquoi : une transaction", () -> investigation.investiguer(d.id(), "LIGNE_RELEVE", transactionSansEcriture, null, d.expert())));
        UUID ecritureFacture = jdbc.queryForObject("select md5(?::text || 'F' || ?)::uuid", UUID.class, seed, FACTURES * 3 / 4);
        mesures.add(mesurer("pourquoi : une facture", () -> investigation.investiguer(d.id(), "ECRITURE", ecritureFacture, null, d.expert())));

        System.out.printf("VOLUME-REVISION %d ecritures, %d transactions, %d documents, %d factures -> %d constats actifs, controles %d ms%n", ecritures, TRANSACTIONS, DOCUMENTS, FACTURES,
                constats, controlesMs);
        for (Mesure m : mesures) {
            System.out.printf("VOLUME-REVISION %-42s | %6d ms | %4d SQL | %8d o JSON%n", m.nom(), m.ms(), m.requetes(), m.octets());
        }

        assertThat(ecritures).isGreaterThanOrEqualTo(FACTURES + REGLEMENTS + DIVERSES);
        // Les exceptions sont tres inferieures aux operations : quelques constats resumes pour des milliers d'operations.
        assertThat(constats).isLessThan(ecritures / 50);
        // Le debugger ne charge pas le grand livre : requetes et taille de reponse bornees, quel que soit le volume.
        for (Mesure m : mesures) {
            assertThat(m.requetes()).as(m.nom()).isLessThan(120);
            assertThat(m.octets()).as(m.nom()).isLessThan(400_000);
        }
        // Le compte d'un tiers a 2 000 factures : reponse plafonnee, chronologie tronquee et dite.
        InvestigationResult r = investigation.investiguer(d.id(), "TIERS", client.id(), null, d.expert());
        System.out.printf("VOLUME-REVISION detail tiers : causes=%d o, chronologie=%d o (%d evt), liees=%d o (%d), details=%d o%n", json.writeValueAsBytes(r.causes()).length,
                json.writeValueAsBytes(r.timeline()).length, r.timeline().size(), json.writeValueAsBytes(r.relatedEntities()).length, r.relatedEntities().size(),
                json.writeValueAsBytes(r.details()).length);
        assertThat(r.timeline().size()).isLessThanOrEqualTo(2 * TiersInvestigator.MAX_EVENEMENTS);
        assertThat(r.details()).containsKey("chronologieTronquee");
        assertThat(r.causes().stream().filter(c -> c.code().equals(CauseCodes.UNPAID_INVOICE)).toList()).hasSize(1);
        assertThat(r.comparison().difference()).isEqualByComparingTo("0");
        RevueDto.Espace espace = revueService.espace(d.id(), d.expert());
        assertThat(espace.domaines()).isNotEmpty();
        ClotureReadinessDto pre = readinessService.evaluer(d.id(), d.expert());
        assertThat(pre.statut()).isIn("READY_WITH_WARNINGS", "NOT_READY");
    }
}
