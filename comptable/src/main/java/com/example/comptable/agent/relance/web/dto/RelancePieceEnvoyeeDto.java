package com.example.comptable.agent.relance.web.dto;

import com.example.comptable.agent.relance.domain.RelancePieceEnvoyee;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RelancePieceEnvoyeeDto(
        UUID id,
        UUID demandePieceId,
        UUID utilisateurClientId,
        String telephone,
        String statut,
        String whatsappMessageId,
        String motifEchec,
        OffsetDateTime envoyeAt
) {
    public static RelancePieceEnvoyeeDto from(RelancePieceEnvoyee relance) {
        return new RelancePieceEnvoyeeDto(
                relance.getId(),
                relance.getDemandePieceId(),
                relance.getUtilisateurClientId(),
                relance.getTelephone(),
                relance.getStatut().name(),
                relance.getWhatsappMessageId(),
                relance.getMotifEchec(),
                relance.getEnvoyeAt()
        );
    }
}
