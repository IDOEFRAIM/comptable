package com.example.comptable.cabinet.web;

import com.example.comptable.cabinet.CabinetAnalyticsService;
import com.example.comptable.cabinet.web.dto.CabinetAnalyticsDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cabinet")
@RequiredArgsConstructor
public class CabinetAnalyticsController {

    private final CabinetAnalyticsService cabinetAnalyticsService;

    @GetMapping("/analytics")
    public CabinetAnalyticsDto analytics(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return cabinetAnalyticsService.generer(principal);
    }
}
