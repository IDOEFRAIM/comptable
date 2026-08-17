package com.example.comptable.agent.pipeline.web;

import com.example.comptable.agent.pipeline.web.dto.DocumentEnAttenteValidationDto;
import com.example.comptable.agent.pipeline.web.dto.ValiderDocumentRequest;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * API de l'inbox de validation par lot (Sprint 4). Nichee sous {@code /api/entreprises/{id}/...}
 * comme le reste de l'application (isolation multi-tenant via {@code EntrepriseAccessService})
 * plutot que sous {@code /api/v1/documents/...} : cette derniere forme n'exprime pas
 * l'appartenance a une entreprise dans l'URL, ce qui casserait la coherence avec tous les autres
 * endpoints et les regles {@code SecurityConfig} existantes.
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/documents-agent")
@RequiredArgsConstructor
public class DocumentAgentController {

    private final DocumentAgentService documentAgentService;

    @GetMapping("/en-attente")
    public List<DocumentEnAttenteValidationDto> listerEnAttente(
            @PathVariable UUID entrepriseId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return documentAgentService.listerEnAttenteValidation(entrepriseId, principal);
    }

    @PostMapping("/{documentId}/valider-et-reprendre")
    public DocumentDto validerEtReprendre(
            @PathVariable UUID entrepriseId,
            @PathVariable UUID documentId,
            @RequestBody ValiderDocumentRequest request,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return documentAgentService.validerEtReprendre(entrepriseId, documentId, request, principal);
    }
}
