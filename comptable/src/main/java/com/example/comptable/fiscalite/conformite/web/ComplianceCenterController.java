package com.example.comptable.fiscalite.conformite.web;

import com.example.comptable.fiscalite.conformite.ComplianceCenterService;
import com.example.comptable.fiscalite.conformite.web.dto.ComplianceCenterDto;
import com.example.comptable.fiscalite.conformite.web.dto.TaxHealthDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/conformite")
@RequiredArgsConstructor
public class ComplianceCenterController {

    private final ComplianceCenterService complianceCenterService;

    @GetMapping
    public ComplianceCenterDto conformite(@PathVariable UUID entrepriseId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return complianceCenterService.genererCentreConformite(entrepriseId, principal);
    }

    @GetMapping("/tax-health")
    public TaxHealthDto taxHealth(@PathVariable UUID entrepriseId,
                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return complianceCenterService.genererTaxHealth(entrepriseId, principal);
    }
}
