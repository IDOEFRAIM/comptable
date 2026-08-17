package com.example.comptable.notification.web.dto;

import com.example.comptable.notification.domain.Notification;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String type,
        String contenu,
        String statut,
        OffsetDateTime createdAt
) {
    public static NotificationDto from(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getType().name(),
                notification.getContenu(),
                notification.getStatut().name(),
                notification.getCreatedAt()
        );
    }
}
