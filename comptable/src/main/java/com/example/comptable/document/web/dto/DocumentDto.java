package com.example.comptable.document.web.dto;

import com.example.comptable.document.domain.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentDto(
        UUID id,
        UUID entrepriseId,
        String typeDocument,
        String canalSource,
        String nomFichierOriginal,
        String contentType,
        Long tailleOctets,
        String statut,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        LocalDate datePiece,
        String messageOcr,
        UUID ecritureId,
        String scoreRisqueFraude,
        List<AlerteFraudeDto> alertesFraude,
        OffsetDateTime createdAt
) {
    public static DocumentDto from(Document document) {
        String message = document.getDonneesOcr() != null
                ? String.valueOf(document.getDonneesOcr().get("message"))
                : null;
        return new DocumentDto(
                document.getId(),
                document.getEntreprise().getId(),
                document.getTypeDocument().name(),
                document.getCanalSource().name(),
                document.getNomFichierOriginal(),
                document.getContentType(),
                document.getTailleOctets(),
                document.getStatut().name(),
                document.getMontantHt(),
                document.getMontantTva(),
                document.getMontantTtc(),
                document.getDatePiece(),
                message,
                document.getEcritureId(),
                AlerteFraudeDto.scoreRisque(document),
                AlerteFraudeDto.alertes(document),
                document.getCreatedAt()
        );
    }
}
