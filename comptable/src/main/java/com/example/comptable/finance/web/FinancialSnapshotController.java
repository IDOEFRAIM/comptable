package com.example.comptable.finance.web;

import com.example.comptable.finance.FinancialSnapshotService;
import com.example.comptable.finance.web.dto.ComparaisonFinancialSnapshotDto;
import com.example.comptable.finance.web.dto.FinancialSnapshotDto;
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

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/financial-snapshot")
@RequiredArgsConstructor
public class FinancialSnapshotController {

    private final FinancialSnapshotService financialSnapshotService;

    @GetMapping
    public FinancialSnapshotDto snapshot(@PathVariable UUID entrepriseId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return financialSnapshotService.generer(entrepriseId, debut, fin, principal);
    }

    @GetMapping("/comparaison")
    public ComparaisonFinancialSnapshotDto comparaison(
            @PathVariable UUID entrepriseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(defaultValue = "PERIODE_PRECEDENTE") String comparerA,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return financialSnapshotService.comparer(entrepriseId, debut, fin, comparerA, principal);
    }
}
