package com.example.comptable.investigation;

import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * « Pourquoi ? » de la Revue. Sous {@code /revue/**} : meme securite que la revue (cabinet uniquement, jamais le role CLIENT). Lecture seule.
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/revue")
@RequiredArgsConstructor
public class InvestigationController {

    private final AccountingInvestigationService investigationService;

    /** Investigation contextuelle d'un constat (ce que le constat designe : transaction, reglement, tiers, piece...). */
    @GetMapping("/constats/{findingId}/investigation")
    public InvestigationResult constat(@PathVariable UUID entrepriseId, @PathVariable UUID findingId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return investigationService.depuisConstat(entrepriseId, findingId, principal);
    }

    /** Investigation d'un objet (TIERS, LIGNE_ECRITURE, ECRITURE, FACTURE, LIGNE_RELEVE, RELEVE, DOCUMENT, IMMOBILISATION). */
    @GetMapping("/investigations/{type}/{id}")
    public InvestigationResult objet(@PathVariable UUID entrepriseId, @PathVariable String type, @PathVariable UUID id,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return investigationService.investiguer(entrepriseId, type, id, au, principal);
    }
}
