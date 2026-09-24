package com.example.comptable.agent.accounting.web.dto;

import com.example.comptable.comptabilite.parametrage.web.dto.ResolvedAccountDto;

import java.util.List;

/**
 * Reponse renvoyee au client Python (ValidationResult cote Python, ai-service/app/schemas/accounting.py).
 * {@code valid}/{@code requiresReview} sont calcules exclusivement cote Java a partir des
 * resolutions reelles ({@link ResolvedAccountDto}) - Python ne doit jamais recalculer ces champs
 * localement (voir AiAccountingProposalValidationService).
 */
public record AiAccountingValidationResponseDto(
        boolean valid,
        boolean requiresReview,
        List<ResolvedAccountDto> resolvedAccounts,
        List<String> errors,
        List<String> warnings
) {
}
