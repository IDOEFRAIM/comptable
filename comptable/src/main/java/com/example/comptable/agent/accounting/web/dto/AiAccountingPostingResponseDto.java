package com.example.comptable.agent.accounting.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reponse renvoyee au client Python (PostingResult cote Python, ai-service/app/schemas/accounting.py).
 * {@code posted} ne peut jamais devenir vrai sans etre passe par
 * {@code AccountingOperationEngine.proposerEtValiderImmediatementSysteme} - jamais un flag decide
 * cote Python.
 */
public record AiAccountingPostingResponseDto(
        boolean posted,
        UUID proposalId,
        UUID entryId,
        String entryNumber,
        OffsetDateTime postedAt,
        String journal,
        List<String> errors
) {
}
