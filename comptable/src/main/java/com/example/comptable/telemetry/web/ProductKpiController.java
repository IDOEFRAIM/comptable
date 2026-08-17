package com.example.comptable.telemetry.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.telemetry.ProductKpiService;
import com.example.comptable.telemetry.web.dto.ProductKpiDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mission P1.9 (section 24 "Quality Dashboard") : meme perimetre que le reste de
 * {@code /api/cabinet/**} - jamais accessible a un CLIENT (portefeuille cross-entreprises). */
@RestController
@RequestMapping("/api/cabinet")
@RequiredArgsConstructor
public class ProductKpiController {

    private final ProductKpiService productKpiService;

    @GetMapping("/kpi")
    public ProductKpiDto obtenirKpi(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return productKpiService.generer(principal);
    }
}
