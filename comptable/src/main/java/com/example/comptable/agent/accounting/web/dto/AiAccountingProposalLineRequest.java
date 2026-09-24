package com.example.comptable.agent.accounting.web.dto;

import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Une ligne du brouillon de proposition envoye par le workflow Python AI Accounting
 * (AccountingProposal cote Python, ai-service/app/schemas/accounting.py). Ce n'est PAS le meme
 * modele que {@code comptabilite.operation.common.domain.AccountingOperationProposal} - un
 * contrat de transport minimal pour cette validation uniquement, jamais une reimplementation du
 * modele comptable Java.
 */
public record AiAccountingProposalLineRequest(
        @NotNull AccountPurpose purpose,
        @NotNull BigDecimal montantHt,
        @NotBlank String designation
) {
}
