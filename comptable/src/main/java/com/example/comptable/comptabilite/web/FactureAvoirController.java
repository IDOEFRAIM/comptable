package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.FactureAvoirService;
import com.example.comptable.comptabilite.web.dto.FactureAvoirDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/factures-avoir")
@RequiredArgsConstructor
public class FactureAvoirController {

    private final FactureAvoirService factureAvoirService;

    @GetMapping
    public List<FactureAvoirDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return factureAvoirService.lister(entrepriseId, principal);
    }

    @PostMapping("/{avoirId}/valider")
    public FactureAvoirDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID avoirId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return factureAvoirService.validerAvoir(entrepriseId, avoirId, principal);
    }

    @GetMapping("/{avoirId}/pdf")
    public ResponseEntity<byte[]> telechargerPdf(@PathVariable UUID entrepriseId, @PathVariable UUID avoirId,
                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        byte[] pdf = factureAvoirService.genererPdf(entrepriseId, avoirId, principal);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"avoir.pdf\"")
                .body(pdf);
    }
}
