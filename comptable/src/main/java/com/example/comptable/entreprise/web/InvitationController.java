package com.example.comptable.entreprise.web;

import com.example.comptable.entreprise.service.InvitationService;
import com.example.comptable.entreprise.web.dto.InviterUtilisateurRequest;
import com.example.comptable.entreprise.web.dto.UtilisateurInviteDto;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/inviter")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    public ResponseEntity<UtilisateurInviteDto> inviter(@PathVariable UUID entrepriseId,
                                                          @Valid @RequestBody InviterUtilisateurRequest request,
                                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        UtilisateurInviteDto invite = invitationService.inviter(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(invite);
    }
}
