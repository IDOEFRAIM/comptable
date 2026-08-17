package com.example.comptable.notification.repository;

import com.example.comptable.notification.domain.Notification;
import com.example.comptable.notification.domain.StatutNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUtilisateurIdOrderByCreatedAtDesc(UUID utilisateurId);

    long countByUtilisateurIdAndStatut(UUID utilisateurId, StatutNotification statut);

    List<Notification> findByUtilisateurIdAndStatut(UUID utilisateurId, StatutNotification statut);
}
