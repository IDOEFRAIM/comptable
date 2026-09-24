package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;

import java.util.List;

/**
 * Resultat interne d'une regle metier (AchatRule/VenteRule/ReglementRule/AvoirRule), assemble par
 * AccountingOperationEngine en AccountingOperationProposal. {@code lignes} ne contient que des
 * lignes entierement resolues (compte reel) - un manque se traduit par une entree dans {@code
 * explications}, jamais par une ligne a compte devine.
 */
public record ResultatRegle(
        TypeJournal typeJournalAttendu,
        List<AccountingOperationProposalLigne> lignes,
        List<String> explications,
        List<String> warnings
) {
}
