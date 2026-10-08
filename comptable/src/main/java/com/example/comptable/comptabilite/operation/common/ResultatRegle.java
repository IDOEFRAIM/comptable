package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;

import com.example.comptable.comptabilite.parametrage.AccountResolutionCode;

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
        List<String> warnings,
        /** Codes machine alignes sur {@code explications} ({@code null} = pas un probleme d'imputation). */
        List<AccountResolutionCode> codes
) {

    public ResultatRegle {
        List<AccountResolutionCode> alignes = new java.util.ArrayList<>(codes != null ? codes : List.of());
        while (alignes.size() < explications.size()) {
            alignes.add(null);
        }
        codes = java.util.Collections.unmodifiableList(alignes);
    }

    /** Forme courante : si {@code explications} est une {@link Blocages}, ses codes sont conserves. */
    public ResultatRegle(TypeJournal typeJournalAttendu, List<AccountingOperationProposalLigne> lignes,
                         List<String> explications, List<String> warnings) {
        this(typeJournalAttendu, lignes, explications, warnings,
                explications instanceof Blocages b ? b.codes() : List.of());
    }
}
