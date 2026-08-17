package com.example.comptable.notification.service;

import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.notification.domain.CanalNotification;
import com.example.comptable.notification.domain.Notification;
import com.example.comptable.notification.domain.StatutNotification;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.repository.NotificationRepository;
import com.example.comptable.notification.web.dto.NotificationDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Notifications in-app minimalistes : pas (encore) d'envoi WhatsApp/email reel. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** Cree une notification pour un destinataire ; appelee depuis d'autres services metier (ex: DemandePieceService). */
    @Transactional
    public void notifier(Utilisateur destinataire, TypeNotification type, String contenu) {
        Notification notification = new Notification();
        notification.setTenant(destinataire.getTenant());
        notification.setUtilisateur(destinataire);
        notification.setType(type);
        notification.setCanal(CanalNotification.IN_APP);
        notification.setContenu(contenu);
        notification.setStatut(StatutNotification.NON_LUE);
        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> lister(UtilisateurPrincipal principal) {
        return notificationRepository.findByUtilisateurIdOrderByCreatedAtDesc(principal.getId()).stream()
                .map(NotificationDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long compterNonLues(UtilisateurPrincipal principal) {
        return notificationRepository.countByUtilisateurIdAndStatut(principal.getId(), StatutNotification.NON_LUE);
    }

    @Transactional
    public NotificationDto marquerLue(UUID notificationId, UtilisateurPrincipal principal) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification introuvable"));
        if (!notification.getUtilisateur().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification introuvable");
        }
        notification.setStatut(StatutNotification.LUE);
        notification.setDateEnvoi(OffsetDateTime.now());
        return NotificationDto.from(notificationRepository.save(notification));
    }

    @Transactional
    public void marquerToutesLues(UtilisateurPrincipal principal) {
        List<Notification> nonLues = notificationRepository
                .findByUtilisateurIdAndStatut(principal.getId(), StatutNotification.NON_LUE);
        OffsetDateTime maintenant = OffsetDateTime.now();
        nonLues.forEach(n -> {
            n.setStatut(StatutNotification.LUE);
            n.setDateEnvoi(maintenant);
        });
        notificationRepository.saveAll(nonLues);
    }
}
