package com.example.comptable.document.web;

import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public ResponseEntity<DocumentDto> uploader(
            @PathVariable UUID entrepriseId,
            @RequestParam TypeDocument typeDocument,
            @RequestParam MultipartFile fichier,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DocumentDto document = documentService.uploader(entrepriseId, typeDocument, fichier, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(document);
    }

    @GetMapping
    public List<DocumentDto> lister(
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) StatutDocument statut,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return documentService.lister(entrepriseId, statut, principal);
    }

    @GetMapping("/{documentId}/fichier")
    public ResponseEntity<org.springframework.core.io.Resource> telecharger(
            @PathVariable UUID entrepriseId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DocumentService.FichierDocument fichier = documentService.telecharger(entrepriseId, documentId, principal);
        MediaType type = fichier.contentType() != null
                ? MediaType.parseMediaType(fichier.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fichier.nomFichier() + "\"")
                .body(fichier.resource());
    }
}
