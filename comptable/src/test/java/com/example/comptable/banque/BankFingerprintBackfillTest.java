package com.example.comptable.banque;

import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reprise des empreintes des anciennes transactions (anterieures a la Phase D) : posees seulement si LEGITIMES, doublons d'anciens imports rapportes sans rien
 * supprimer, lignes sans libelle rapportees (jamais une empreinte arbitraire), idempotente.
 */
class BankFingerprintBackfillTest extends BankTestBase {

    @Autowired BankFingerprintBackfillService backfill;
    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired LigneReleveRepository ligneReleveRepository;

    /** Un releve « d'avant la Phase D » : importe puis prive de ses empreintes ; un second releve reprend le MEME contenu (l'ancien code ne detectait aucun doublon). */
    private UUID[] ancienDoubleImport(Dossier d, UUID compte) {
        String jour = LocalDate.now().toString();
        String csv = "date;libelle;montant\n" + jour + ";PRLV ASSURANCE;-8000\n" + jour + ";PRLV ASSURANCE;-8000\n" + jour + ";VIR CLIENT ALPHA;125000\n" + jour + ";;-300\n";
        var res = rapprochementService.importerComplet(d.id(), new BankStatementImporter.DemandeImport(compte, "Ancien releve", null, null, null, null, null),
                new MockMultipartFile("fichier", "ancien.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), d.expert());
        UUID releve1 = res.lot() != null ? jdbc.queryForObject("select id from releve_bancaire where import_batch_id = ?", UUID.class, res.lot().getId()) : null;
        UUID releve2 = UUID.randomUUID();
        jdbc.update("insert into releve_bancaire (id, entreprise_id, compte_id, libelle, date_debut, date_fin, solde_debut, solde_fin, created_at) "
                + "select ?, entreprise_id, compte_id, 'Ancien releve (second import)', date_debut, date_fin, solde_debut, solde_fin, created_at + interval '1 day' "
                + "from releve_bancaire where id = ?", releve2, releve1);
        jdbc.update("insert into ligne_releve (id, releve_id, date_operation, libelle, montant, entreprise_id, compte_id, currency, direction, libelle_normalise, "
                + "reference, source, content_fingerprint, duplicate_status) select gen_random_uuid(), ?, date_operation, libelle, montant, entreprise_id, compte_id, "
                + "currency, direction, libelle_normalise, reference, 'CSV', null, 'NONE' from ligne_releve where releve_id = ?", releve2, releve1);
        jdbc.update("update ligne_releve set content_fingerprint = null where releve_id = ?", releve1);
        return new UUID[] {releve1, releve2};
    }

    private long avecEmpreinte(UUID releve) {
        return jdbc.queryForObject("select count(*) from ligne_releve where releve_id = ? and content_fingerprint is not null", Long.class, releve);
    }

    @Test
    void simulation_neChangeRien_etAnnonceCeQueFeraitLaReprise() {
        Dossier d = nouveauDossier(false);
        UUID compte = reglement(d, 1, LocalDate.now(), "Modele").compteBanqueId();
        UUID[] releves = ancienDoubleImport(d, compte);

        BankFingerprintBackfillService.Rapport rapport = backfill.simuler();

        assertThat(rapport.simulation()).isTrue();
        assertThat(rapport.posees()).isZero();
        assertThat(rapport.total()).isGreaterThanOrEqualTo(8);
        assertThat(avecEmpreinte(releves[0])).isZero();
        assertThat(avecEmpreinte(releves[1])).isZero();
    }

    @Test
    void reprise_poseLesEmpreintesLegitimes_rapporteDoublonsEtInsuffisants_sansRienSupprimer() {
        Dossier d = nouveauDossier(false);
        UUID compte = reglement(d, 1, LocalDate.now(), "Modele").compteBanqueId();
        UUID[] releves = ancienDoubleImport(d, compte);
        long avant = jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id());

        BankFingerprintBackfillService.Rapport rapport = backfill.appliquer();

        // Premier releve : 3 lignes identifiables (les deux prelevements identiques se distinguent par leur rang), 1 ligne sans libelle.
        assertThat(avecEmpreinte(releves[0])).isEqualTo(3);
        // Second releve : le meme contenu = doublons d'un ancien import. Rien n'est supprime, rien n'est pose, tout est rapporte.
        assertThat(avecEmpreinte(releves[1])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id())).isEqualTo(avant);
        assertThat(rapport.detailCollisions().stream().filter(c -> releves[1].toString().equals(c.get("releveId"))).count()).isEqualTo(3);
        assertThat(rapport.detailInsuffisantes().stream().filter(c -> releves[0].toString().equals(c.get("releveId"))).count()).isEqualTo(1);
        assertThat(rapport.detailInsuffisantes().stream().filter(c -> releves[1].toString().equals(c.get("releveId"))).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from migration_report where code = 'BANK_FINGERPRINT_COLLISION' and details->>'releveId' = ?", Long.class,
                releves[1].toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from migration_report where code = 'BANK_FINGERPRINT_BACKFILL'", Long.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void reprise_apresReprise_estIdempotente_etUnReimportDuMemeFichierNeCreeAucunDoublon() {
        Dossier d = nouveauDossier(false);
        UUID compte = reglement(d, 1, LocalDate.now(), "Modele").compteBanqueId();
        UUID[] releves = ancienDoubleImport(d, compte);
        backfill.appliquer();
        long poseesAvant = avecEmpreinte(releves[0]);

        BankFingerprintBackfillService.Rapport seconde = backfill.appliquer();

        assertThat(avecEmpreinte(releves[0])).isEqualTo(poseesAvant);
        assertThat(seconde.posees()).isZero();
        // L'ancien releve est desormais protege : le reimporter n'ajoute que ce qui est vraiment nouveau (ici, la ligne sans libelle, jamais identifiable).
        String jour = LocalDate.now().toString();
        String csv = "date;libelle;montant\n" + jour + ";PRLV ASSURANCE;-8000\n" + jour + ";PRLV ASSURANCE;-8000\n" + jour + ";VIR CLIENT ALPHA;125000\n";
        var reimport = rapprochementService.importerComplet(d.id(), new BankStatementImporter.DemandeImport(compte, "Reimport", null, null, null, null, null),
                new MockMultipartFile("fichier", "ancien.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), d.expert());
        assertThat(reimport.releve()).isNull();
        assertThat(reimport.lot().getExactDuplicates()).isEqualTo(3);
        assertThat(List.of(1)).hasSize(1);
    }
}
