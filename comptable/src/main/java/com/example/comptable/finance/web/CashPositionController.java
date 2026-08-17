package com.example.comptable.finance.web;

import com.example.comptable.finance.CashForecastService;
import com.example.comptable.finance.CashPositionService;
import com.example.comptable.finance.web.dto.CashPositionDto;
import com.example.comptable.finance.web.dto.PrevisionTresorerieDto;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/tresorerie")
@RequiredArgsConstructor
public class CashPositionController {

    private final CashPositionService cashPositionService;
    private final CashForecastService cashForecastService;

    @GetMapping("/position")
    public CashPositionDto position(@PathVariable UUID entrepriseId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return cashPositionService.generer(entrepriseId, date, principal);
    }

    @GetMapping("/prevision")
    public List<PrevisionTresorerieDto> prevision(@PathVariable UUID entrepriseId,
                                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return cashForecastService.generer(entrepriseId, principal);
    }
}
