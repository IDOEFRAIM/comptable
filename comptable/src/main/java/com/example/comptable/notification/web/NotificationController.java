package com.example.comptable.notification.web;

import com.example.comptable.notification.service.NotificationService;
import com.example.comptable.notification.web.dto.NotificationDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public List<NotificationDto> lister(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return notificationService.lister(principal);
    }

    @GetMapping("/non-lues/compteur")
    public Map<String, Long> compterNonLues(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Map.of("nonLues", notificationService.compterNonLues(principal));
    }

    @PostMapping("/{notificationId}/marquer-lue")
    public NotificationDto marquerLue(@PathVariable UUID notificationId,
                                       @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return notificationService.marquerLue(notificationId, principal);
    }

    @PostMapping("/marquer-toutes-lues")
    public void marquerToutesLues(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        notificationService.marquerToutesLues(principal);
    }
}
