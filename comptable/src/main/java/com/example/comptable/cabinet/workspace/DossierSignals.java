package com.example.comptable.cabinet.workspace;

import lombok.Builder;

/**
 * Faits bruts d'un dossier, agrégés par requêtes groupées ({@link DossierSignalsLoader}) : entrée unique et pure de
 * {@link DossierOperationalStatusEvaluator}. Aucune règle ici, seulement des compteurs.
 *
 * <p>{@code findingsInternes} : constats actifs que le cabinet peut traiter lui-même (ceux qui attendent une pièce déjà demandée au client n'y figurent pas).</p>
 */
@Builder(toBuilder = true)
public record DossierSignals(
        boolean exerciceCloture,
        boolean finalise,
        long ecrituresValidees,
        long ecrituresBrouillon,
        long documentsATraiter,
        long demandesAttenteClient,
        long demandesEnRetard,
        long piecesRecuesARattacher,
        long findingsInternes,
        long findingsBloquants,
        long findingsImportants,
        long banqueNonRapprochees,
        long banquePropositions,
        long banqueAmbigues,
        long regularisationsNonFinalisees,
        long notesTotal,
        long notesADecider,
        long notesIncompletes,
        long notesRevisables,
        long notesRevues,
        boolean activiteConnue,
        long joursPlusAncienItem,
        boolean echeanceEnRetard,
        boolean echeanceProche) {
}
