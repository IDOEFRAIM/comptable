package com.example.comptable.etatsfinanciers.sync;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.WorkItemDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.etatsfinanciers.dossier.CashFlowQualificationService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.FieldPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ManualPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.NoteView;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.SectionView;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synchronisation PROACTIVE des Findings des états financiers : aucune de ces vérifications n'ouvre le dossier financier ni n'appelle un contrôle à la main. Le constat naît (ou disparaît) de
 * l'opération métier (écriture validée, qualification d'un flux, note complétée) ; le Cabinet Workspace le lit tel quel.
 */
class ProactiveFinancialFindingsTest extends BankTestBase {

    @Autowired EcritureComptableService ecritureService;
    @Autowired CashFlowQualificationService qualification;
    @Autowired NoteWorkspaceService notes;
    @Autowired FinancialControlSynchronizationService synchronisation;
    @Autowired CabinetWorkspaceService workspace;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    // ---------------------------------------------------------------------------------------------------------------------------------------------- outils

    private UUID compte(Dossier d, String prefixe) {
        String numero = prefixe + "900";
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    private UUID ecrire(Dossier d, Map<String, Integer> debits, Map<String, Integer> credits, CashFlowEvent evenement) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        debits.forEach((p, v) -> lignes.add(new LigneEcritureRequest(compte(d, p), "D " + p, new BigDecimal(v), BigDecimal.ZERO, null)));
        credits.forEach((p, v) -> lignes.add(new LigneEcritureRequest(compte(d, p), "C " + p, BigDecimal.ZERO, new BigDecimal(v), null)));
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, LocalDate.now(), "Scenario proactif", null, lignes, evenement), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
        return id;
    }

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    private List<com.example.comptable.agent.finding.domain.Finding> actifs(Dossier d, String code) {
        return findingRepository.findActifsParCodes(d.id(), List.of(code));
    }

    private boolean dansLaFile(Dossier d) {
        return workspace.workQueue(d.expert(), false, 0, 100).items().stream().anyMatch(i -> i.entrepriseId().equals(d.id()));
    }

    private List<WorkItemDto> file(Dossier d) {
        return workspace.workQueue(d.expert(), false, 0, 100).items().stream().filter(i -> i.entrepriseId().equals(d.id())).toList();
    }

    private void remplir(Dossier d, String code) {
        NoteView v = notes.lire(d.id(), exercice(d), code, d.expert());
        List<FieldPatch> champs = new ArrayList<>();
        for (SectionView s : v.manualSections()) {
            for (var f : s.fields()) {
                if (f.required() && !f.filled()) {
                    champs.add(new FieldPatch(s.sectionCode(), f.fieldCode(), f.type().equals("INTEGER") ? "40" : "Renseigne pour le test", null, "test"));
                }
            }
        }
        if (!champs.isEmpty()) {
            notes.modifierDonneesManuelles(d.id(), exercice(d), code, new ManualPatch(v.version(), champs, null), d.expert());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------- compte non mappé

    @Test
    void compteNonMappe_estDansLaWorkQueueSansOuvrirLeDossier_puisResoluParLeSysteme() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("521", 105_000), m("701", 100_000, "708", 5_000), null);          // 708 : aucune règle vérifiée -> compte non mappé

        // aucun GET du dossier financier, aucun appel de contrôle : le constat existe déjà
        assertThat(actifs(d, ControlCodes.UNMAPPED_ACCOUNT)).isNotEmpty();             // un constat par état concerné (compte de résultat et bilan)
        assertThat(file(d)).extracting(WorkItemDto::domaine).contains("Comptabilité");
        assertThat(file(d)).anyMatch(i -> i.cible().equals("ETATS_FINANCIERS"));

        // la situation est corrigée (écriture inverse : le solde du compte 708 redevient nul), toujours sans ouvrir le dossier
        ecrire(d, m("708", 5_000), m("521", 5_000), null);
        assertThat(actifs(d, ControlCodes.UNMAPPED_ACCOUNT)).isEmpty();
        var resolus = jdbc.queryForList("select status, resolution_type from finding where entreprise_id = ? and control_code = ?", d.id(), ControlCodes.UNMAPPED_ACCOUNT);
        assertThat(resolus).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("RESOLVED");
            assertThat(r.get("resolution_type")).isEqualTo("SYSTEM");
        });
        assertThat(file(d)).noneMatch(i -> i.titre().contains("708900"));
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------- TFT

    @Test
    void fluxDeTresorerieNonQualifie_apparaitPuisDisparaitALaQualification() {
        Dossier d = nouveauDossier(false);
        UUID capital = ecrire(d, m("521", 700_000), m("101", 700_000), null);          // mouvement de financement sans événement de trésorerie

        assertThat(actifs(d, ControlCodes.UNQUALIFIED_CASH_EVENT)).hasSize(1);
        assertThat(file(d)).anyMatch(i -> i.titre().contains("sans evenement qualifie"));

        qualification.qualifier(d.id(), capital, CashFlowEvent.CAPITAL_CASH_INCREASE, d.expert());
        assertThat(actifs(d, ControlCodes.UNQUALIFIED_CASH_EVENT)).isEmpty();
        assertThat(file(d)).noneMatch(i -> i.titre().contains("sans evenement qualifie"));
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------- notes

    @Test
    void noteObligatoireIncomplete_apparaitAvecLesEcritures_puisDisparaitQuandElleEstCompletee() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("601", 300_000), m("401", 300_000), null);                          // dettes fournisseurs : la Note 17 devient obligatoire et incomplète

        List<com.example.comptable.agent.finding.domain.Finding> incompletes = actifs(d, ControlCodes.REQUIRED_NOTE_INCOMPLETE);
        assertThat(incompletes).extracting(f -> f.getTitle()).anyMatch(t -> t.startsWith("Note 17"));
        assertThat(file(d)).anyMatch(i -> i.titre().startsWith("Note 17"));

        remplir(d, "17");
        assertThat(actifs(d, ControlCodes.REQUIRED_NOTE_INCOMPLETE)).extracting(f -> f.getTitle()).noneMatch(t -> t.startsWith("Note 17"));
        assertThat(file(d)).noneMatch(i -> i.titre().startsWith("Note 17"));
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------- idempotence, backfill, isolation

    @Test
    void synchronisationIdempotente_backfillExplicite_etUneAnnulationNeSynchroniseRien() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("521", 105_000), m("701", 100_000, "708", 5_000), null);
        List<UUID> avant = actifs(d, ControlCodes.UNMAPPED_ACCOUNT).stream().map(f -> f.getId()).toList();
        long total = jdbc.queryForObject("select count(*) from finding where entreprise_id = ?", Long.class, d.id());

        for (int i = 0; i < 3; i++) {
            synchronisation.synchroniser(d.id(), exercice(d), EnumSet.allOf(FinancialControlDomain.class));
        }
        assertThat(actifs(d, ControlCodes.UNMAPPED_ACCOUNT).stream().map(f -> f.getId()).toList()).isEqualTo(avant);   // aucun doublon, mêmes constats
        assertThat(jdbc.queryForObject("select count(*) from finding where entreprise_id = ?", Long.class, d.id())).isEqualTo(total);

        // backfill : un dossier existant avant la synchronisation proactive (aucun constat) est rattrapé par une action explicite
        jdbc.update("delete from finding where entreprise_id = ?", d.id());
        assertThat(actifs(d, ControlCodes.UNMAPPED_ACCOUNT)).isEmpty();
        assertThat(synchronisation.synchroniserCabinet(d.expert().getTenantId())).isGreaterThanOrEqualTo(1);
        assertThat(actifs(d, ControlCodes.UNMAPPED_ACCOUNT)).isNotEmpty();

        // un autre cabinet n'est jamais touché par le backfill du premier
        Dossier autre = nouveauDossier(false);
        ecrire(autre, m("521", 105_000), m("701", 100_000, "708", 5_000), null);
        jdbc.update("delete from finding where entreprise_id = ?", autre.id());
        synchronisation.synchroniserCabinet(d.expert().getTenantId());
        assertThat(actifs(autre, ControlCodes.UNMAPPED_ACCOUNT)).isEmpty();
    }

    @Test
    void ecritureEnBrouillon_neDeclencheAucuneSynchronisation() {
        Dossier d = nouveauDossier(false);
        List<LigneEcritureRequest> lignes = List.of(
                new LigneEcritureRequest(compte(d, "521"), "D", new BigDecimal(5000), BigDecimal.ZERO, null),
                new LigneEcritureRequest(compte(d, "708"), "C", BigDecimal.ZERO, new BigDecimal(5000), null));
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, LocalDate.now(), "Brouillon", null, lignes, null), d.expert());
        assertThat(actifs(d, ControlCodes.UNMAPPED_ACCOUNT)).isEmpty();                // un brouillon n'entre dans aucun état
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------- coût

    @Test
    void cout_dUneOperation_bornePar_lesRequetesGroupees_etIndependantDuVolume() {
        Dossier d = nouveauDossier(false);
        SessionFactory sf = entityManagerFactory.unwrap(SessionFactory.class);

        long[] e1 = mesurer(sf, () -> ecrire(d, m("521", 200_000), m("101", 200_000), null));
        for (int i = 0; i < 15; i++) {
            ecrire(d, m("601", 1_000 + i), m("401", 1_000 + i), null);                 // le dossier grossit
        }
        long[] e2 = mesurer(sf, () -> ecrire(d, m("521", 300_000), m("101", 300_000), null));
        UUID ecriture = ecrire(d, m("521", 10_000), m("101", 10_000), null);
        long[] flux = mesurer(sf, () -> qualification.qualifier(d.id(), ecriture, CashFlowEvent.CAPITAL_CASH_INCREASE, d.expert()));
        NoteView v = notes.lire(d.id(), exercice(d), "17", d.expert());
        long[] note = mesurer(sf, () -> notes.modifierApplicabilite(d.id(), exercice(d), "17", new NoteWorkspaceService.ApplicabilityPatch(v.version(), "APPLICABLE", "test"), d.expert()));
        long[] sync = mesurer(sf, () -> synchronisation.synchroniser(d.id(), exercice(d), EnumSet.allOf(FinancialControlDomain.class)));

        System.out.printf("BENCH-SYNC ecriture (1 écriture): %d requetes, %d ms | ecriture (17 écritures): %d requetes, %d ms | qualification flux: %d requetes, %d ms | note: %d requetes, %d ms | synchro seule: %d requetes, %d ms%n",
                e1[0], e1[1], e2[0], e2[1], flux[0], flux[1], note[0], note[1], sync[0], sync[1]);
        // la synchronisation proactive ajoute des requêtes groupées ; le nombre ne dépend pas du nombre d'écritures du dossier
        assertThat(e2[0]).isLessThanOrEqualTo(e1[0] + 10);
        assertThat(e2[0]).isLessThan(400);
        assertThat(sync[0]).isLessThan(300);
    }

    private long[] mesurer(SessionFactory sf, Runnable action) {
        sf.getStatistics().clear();
        long debut = System.nanoTime();
        action.run();
        return new long[] {sf.getStatistics().getPrepareStatementCount(), (System.nanoTime() - debut) / 1_000_000};
    }
}
