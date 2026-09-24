package com.example.comptable.agent.accounting.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Requete envoyee par le client Python (AccountingClient.validate_proposal, STEP 4) pour faire
 * valider un brouillon de proposition comptable avant tout posting. {@code montantTva} est
 * nullable (document sans TVA) ; {@code companyId} correspond a l'entrepriseId Java - jamais une
 * preuve d'autorisation, seulement le contexte a resoudre (voir
 * AiAccountingProposalValidationService, qui verifie que l'entreprise existe reellement).
 */
public record ValidateAiAccountingProposalRequest(
        @NotNull UUID workflowId,
        @NotNull UUID documentId,
        @NotNull UUID companyId,
        @NotEmpty @Valid List<AiAccountingProposalLineRequest> lines,
        BigDecimal montantTva,
        @NotNull BigDecimal montantTtc
) {
}
