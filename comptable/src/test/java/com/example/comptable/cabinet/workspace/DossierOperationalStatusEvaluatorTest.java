package com.example.comptable.cabinet.workspace;

import com.example.comptable.cabinet.workspace.DossierOperationalStatusEvaluator.Evaluation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Règles du statut, de la progression et de la priorité : fonction pure, sans base ni Spring. */
class DossierOperationalStatusEvaluatorTest {

    private final DossierOperationalStatusEvaluator evaluateur = new DossierOperationalStatusEvaluator();

    /** Un dossier dont toute la préparation est terminée, à partir duquel chaque test dérive un seul écart. */
    private DossierSignals.DossierSignalsBuilder prepare() {
        return DossierSignals.builder().activiteConnue(true).ecrituresValidees(12).notesTotal(46).notesRevisables(4);
    }

    private StatutOperationnel statut(DossierSignals s) {
        return evaluateur.evaluer(s).statut();
    }

    @Test
    void aucuneActivite_aDemarrer_priorite_basse() {
        Evaluation e = evaluateur.evaluer(DossierSignals.builder().build());
        assertThat(e.statut()).isEqualTo(StatutOperationnel.A_DEMARRER);
        assertThat(e.priorite()).isEqualTo(PrioriteDossier.LOW);
        assertThat(e.etapesPretes()).isZero();
    }

    @Test
    void constatBloquantOuImportant_attentionRequise_etPrioriteBlocker() {
        Evaluation bloquant = evaluateur.evaluer(prepare().findingsInternes(1).findingsBloquants(1).build());
        assertThat(bloquant.statut()).isEqualTo(StatutOperationnel.ATTENTION_REQUISE);
        assertThat(bloquant.priorite()).isEqualTo(PrioriteDossier.BLOCKER);

        Evaluation important = evaluateur.evaluer(prepare().findingsInternes(1).findingsImportants(1).build());
        assertThat(important.statut()).isEqualTo(StatutOperationnel.ATTENTION_REQUISE);
        assertThat(important.priorite()).isEqualTo(PrioriteDossier.HIGH);
    }

    @Test
    void pieceDemandeeAuClient_enAttenteClient_maisUnConstatInterneImportantPasse_devant() {
        assertThat(statut(prepare().demandesAttenteClient(2).build())).isEqualTo(StatutOperationnel.EN_ATTENTE_CLIENT);
        assertThat(statut(prepare().demandesAttenteClient(2).findingsInternes(1).findingsImportants(1).build())).isEqualTo(StatutOperationnel.ATTENTION_REQUISE);
    }

    @Test
    void constatMineurSeul_dossierEnCours_pasEncoreAttentionRequise() {
        Evaluation e = evaluateur.evaluer(prepare().findingsInternes(3).build());
        assertThat(e.statut()).isEqualTo(StatutOperationnel.EN_COURS);
        assertThat(e.etapesRestantes()).contains(EtapeDossier.CONTROLES, EtapeDossier.PRE_CLOTURE);
    }

    @Test
    void preparationTerminee_pretPourRevue_puisEnRevue_puisPretACloturer() {
        assertThat(statut(prepare().build())).isEqualTo(StatutOperationnel.PRET_POUR_REVUE);
        assertThat(statut(prepare().notesRevues(1).build())).isEqualTo(StatutOperationnel.EN_REVUE);
        assertThat(statut(prepare().notesRevues(4).build())).isEqualTo(StatutOperationnel.PRET_A_CLOTURER);
    }

    @Test
    void sansNoteApplicableARevoir_aucuneRevueAAttendre() {
        assertThat(statut(prepare().notesRevisables(0).build())).isEqualTo(StatutOperationnel.PRET_A_CLOTURER);
    }

    @Test
    void exerciceCloture_puisSnapshotFinal() {
        assertThat(statut(prepare().notesRevues(4).exerciceCloture(true).build())).isEqualTo(StatutOperationnel.CLOTURE);
        Evaluation finalise = evaluateur.evaluer(prepare().notesRevues(4).exerciceCloture(true).finalise(true).findingsInternes(5).findingsBloquants(5).build());
        assertThat(finalise.statut()).isEqualTo(StatutOperationnel.FINALISE);
        assertThat(finalise.priorite()).isEqualTo(PrioriteDossier.LOW);
        assertThat(finalise.etapes().get(6).prete()).isTrue();
    }

    @Test
    void progression_estUnComptageDEtapes_pasUnPourcentage() {
        Evaluation complet = evaluateur.evaluer(prepare().notesRevues(4).build());
        assertThat(complet.etapesTotal()).isEqualTo(7);
        assertThat(complet.etapesPretes()).isEqualTo(6);                                  // tout sauf la finalisation
        assertThat(complet.etapesRestantes()).containsExactly(EtapeDossier.FINALISATION);

        Evaluation partiel = evaluateur.evaluer(prepare().demandesAttenteClient(1).banqueNonRapprochees(4).notesIncompletes(2).build());
        assertThat(partiel.etapesRestantes()).contains(EtapeDossier.PIECES, EtapeDossier.BANQUE, EtapeDossier.ETATS_FINANCIERS, EtapeDossier.FINALISATION);
        assertThat(partiel.etapesPretes()).isEqualTo(partiel.etapesTotal() - partiel.etapesRestantes().size());
    }

    @Test
    void etapes_chaqueSourceDeLaPreparation() {
        assertThat(evaluateur.etapes(prepare().documentsATraiter(1).build()).get(0).prete()).isFalse();            // pièces
        assertThat(evaluateur.etapes(prepare().banquePropositions(1).build()).get(1).prete()).isFalse();           // banque
        assertThat(evaluateur.etapes(prepare().ecrituresBrouillon(1).build()).get(2).prete()).isFalse();          // comptabilité
        assertThat(evaluateur.etapes(prepare().regularisationsNonFinalisees(1).build()).get(4).prete()).isFalse(); // pré-clôture
        assertThat(evaluateur.etapes(prepare().notesADecider(3).build()).get(5).prete()).isFalse();               // états financiers
        assertThat(evaluateur.etapes(prepare().notesTotal(0).build()).get(5).prete()).isFalse();                   // notes jamais ouvertes
    }

    @Test
    void priorite_echeanceAnciennete_etDossierSansUrgence() {
        assertThat(evaluateur.evaluer(prepare().build()).priorite()).isEqualTo(PrioriteDossier.NORMAL);
        assertThat(evaluateur.evaluer(prepare().echeanceEnRetard(true).build()).priorite()).isEqualTo(PrioriteDossier.HIGH);
        assertThat(evaluateur.evaluer(prepare().echeanceProche(true).build()).priorite()).isEqualTo(PrioriteDossier.HIGH);
        assertThat(evaluateur.evaluer(prepare().demandesAttenteClient(1).demandesEnRetard(1).build()).priorite()).isEqualTo(PrioriteDossier.HIGH);
        assertThat(evaluateur.evaluer(prepare().findingsInternes(1).joursPlusAncienItem(20).build()).priorite()).isEqualTo(PrioriteDossier.HIGH);
        assertThat(evaluateur.evaluer(prepare().findingsInternes(1).joursPlusAncienItem(3).build()).priorite()).isEqualTo(PrioriteDossier.NORMAL);
    }
}
