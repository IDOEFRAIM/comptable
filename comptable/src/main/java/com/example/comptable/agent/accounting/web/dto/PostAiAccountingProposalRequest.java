package com.example.comptable.agent.accounting.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Requete envoyee par le client Python (AccountingClient.post_entry, STEP 7) pour poster
 * reellement une proposition comptable. Memes lignes que {@link ValidateAiAccountingProposalRequest}
 * (aucun troisieme modele de proposition), avec en plus {@code datePiece}/{@code libelle}, requis
 * pour construire une {@code EcritureComptable} reelle (exercice, libelle) - pas necessaires pour
 * une simple validation en lecture seule.
 */
public record PostAiAccountingProposalRequest(
        @NotNull UUID workflowId,
        @NotNull UUID documentId,
        @NotNull UUID companyId,
        @NotEmpty @Valid List<AiAccountingProposalLineRequest> lines,
        BigDecimal montantTva,
        @NotNull BigDecimal montantTtc,
        @NotNull LocalDate datePiece,
        @NotBlank String libelle
) {
}
