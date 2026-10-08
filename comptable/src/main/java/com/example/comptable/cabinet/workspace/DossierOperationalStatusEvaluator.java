package com.example.comptable.cabinet.workspace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Moteur UNIQUE du statut opérationnel, de la progression par étapes et de la priorité d'un dossier. Fonction pure des {@link DossierSignals} : le frontend ne recalcule jamais rien.
 *
 * <p>Ordre des règles du statut (la première qui s'applique l'emporte) : FINALISE, CLOTURE, A_DEMARRER (aucune activité), ATTENTION_REQUISE (constat bloquant ou important que le cabinet doit
 * traiter), EN_ATTENTE_CLIENT (le cabinet n'a plus rien à faire seul : une pièce demandée au client est attendue), EN_COURS (une étape de préparation n'est pas prête), puis, préparation
 * terminée : PRET_POUR_REVUE (aucune note applicable revue), EN_REVUE (revue commencée), PRET_A_CLOTURER (revue terminée, exercice ouvert). Sans note applicable à revoir, il n'y a pas de revue à
 * attendre : le dossier passe directement à PRET_A_CLOTURER.</p>
 */
@Component
public class DossierOperationalStatusEvaluator {

    private static final int JOURS_ANCIENNETE_ELEVEE = 14;

    public record EtapeEtat(EtapeDossier etape, boolean prete) {
    }

    public record Evaluation(StatutOperationnel statut, PrioriteDossier priorite, List<EtapeEtat> etapes) {
        public int etapesPretes() {
            return (int) etapes.stream().filter(EtapeEtat::prete).count();
        }

        public int etapesTotal() {
            return etapes.size();
        }

        public List<EtapeDossier> etapesRestantes() {
            return etapes.stream().filter(e -> !e.prete()).map(EtapeEtat::etape).toList();
        }
    }

    public Evaluation evaluer(DossierSignals s) {
        List<EtapeEtat> etapes = etapes(s);
        StatutOperationnel statut = statut(s, etapes);
        return new Evaluation(statut, priorite(s, statut), etapes);
    }

    /** Un dossier sans aucune activité n'a aucune étape prête : l'absence de problème n'est pas de l'avancement. */
    List<EtapeEtat> etapes(DossierSignals s) {
        if (!s.activiteConnue() && !s.finalise()) {
            return java.util.Arrays.stream(EtapeDossier.values()).map(e -> new EtapeEtat(e, false)).toList();
        }
        boolean pieces = s.demandesAttenteClient() == 0 && s.documentsATraiter() == 0 && s.piecesRecuesARattacher() == 0;
        boolean banque = s.banqueNonRapprochees() == 0 && s.banquePropositions() == 0 && s.banqueAmbigues() == 0;
        boolean comptabilite = s.ecrituresBrouillon() == 0 && s.ecrituresValidees() > 0;
        boolean controles = s.findingsInternes() == 0;
        boolean preCloture = controles && comptabilite && s.regularisationsNonFinalisees() == 0;
        boolean etats = s.notesTotal() > 0 && s.notesADecider() == 0 && s.notesIncompletes() == 0;
        List<EtapeEtat> etapes = new ArrayList<>();
        etapes.add(new EtapeEtat(EtapeDossier.PIECES, pieces));
        etapes.add(new EtapeEtat(EtapeDossier.BANQUE, banque));
        etapes.add(new EtapeEtat(EtapeDossier.COMPTABILITE, comptabilite));
        etapes.add(new EtapeEtat(EtapeDossier.CONTROLES, controles));
        etapes.add(new EtapeEtat(EtapeDossier.PRE_CLOTURE, preCloture));
        etapes.add(new EtapeEtat(EtapeDossier.ETATS_FINANCIERS, etats));
        etapes.add(new EtapeEtat(EtapeDossier.FINALISATION, s.finalise()));
        return etapes;
    }

    StatutOperationnel statut(DossierSignals s, List<EtapeEtat> etapes) {
        if (s.finalise()) {
            return StatutOperationnel.FINALISE;
        }
        if (s.exerciceCloture()) {
            return StatutOperationnel.CLOTURE;
        }
        if (!s.activiteConnue()) {
            return StatutOperationnel.A_DEMARRER;
        }
        if (s.findingsBloquants() + s.findingsImportants() > 0) {
            return StatutOperationnel.ATTENTION_REQUISE;
        }
        if (s.demandesAttenteClient() > 0) {
            return StatutOperationnel.EN_ATTENTE_CLIENT;
        }
        boolean preparationTerminee = etapes.stream().filter(e -> e.etape() != EtapeDossier.FINALISATION).allMatch(EtapeEtat::prete);
        if (!preparationTerminee) {
            return StatutOperationnel.EN_COURS;
        }
        if (s.notesRevisables() > 0 && s.notesRevues() == 0) {
            return StatutOperationnel.PRET_POUR_REVUE;
        }
        if (s.notesRevues() < s.notesRevisables()) {
            return StatutOperationnel.EN_REVUE;
        }
        return StatutOperationnel.PRET_A_CLOTURER;
    }

    PrioriteDossier priorite(DossierSignals s, StatutOperationnel statut) {
        if (!statut.actif()) {
            return PrioriteDossier.LOW;
        }
        if (s.findingsBloquants() > 0) {
            return PrioriteDossier.BLOCKER;
        }
        if (s.findingsImportants() > 0 || s.demandesEnRetard() > 0 || s.echeanceEnRetard() || s.echeanceProche()
                || s.joursPlusAncienItem() >= JOURS_ANCIENNETE_ELEVEE) {
            return PrioriteDossier.HIGH;
        }
        return statut == StatutOperationnel.A_DEMARRER ? PrioriteDossier.LOW : PrioriteDossier.NORMAL;
    }
}
