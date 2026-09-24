package com.example.comptable.comptabilite.parametrage.web;

import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.AccountingAccountMappingDto;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.parametrage.web.dto.ModifierAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.parametrage.web.dto.ResolvedAccountDto;
import com.example.comptable.comptabilite.parametrage.web.dto.TestResolutionRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/parametres-comptables")
@RequiredArgsConstructor
public class AccountingAccountMappingController {

    private final AccountingAccountMappingService accountingAccountMappingService;

    @GetMapping("/mappings")
    public List<AccountingAccountMappingDto> lister(@PathVariable UUID entrepriseId,
            @RequestParam(required = false) AccountPurpose purpose, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingAccountMappingService.lister(entrepriseId, purpose, principal);
    }

    @PostMapping("/mappings")
    public ResponseEntity<AccountingAccountMappingDto> creer(@PathVariable UUID entrepriseId,
            @Valid @RequestBody CreerAccountingAccountMappingRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        AccountingAccountMappingDto mapping = accountingAccountMappingService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapping);
    }

    @PutMapping("/mappings/{mappingId}")
    public AccountingAccountMappingDto modifier(@PathVariable UUID entrepriseId, @PathVariable UUID mappingId,
            @RequestBody ModifierAccountingAccountMappingRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingAccountMappingService.modifier(entrepriseId, mappingId, request, principal);
    }

    @DeleteMapping("/mappings/{mappingId}")
    public ResponseEntity<Void> supprimer(@PathVariable UUID entrepriseId, @PathVariable UUID mappingId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        accountingAccountMappingService.supprimer(entrepriseId, mappingId, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test-resolution")
    public ResolvedAccountDto testerResolution(@PathVariable UUID entrepriseId,
            @Valid @RequestBody TestResolutionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingAccountMappingService.testerResolution(entrepriseId, request, principal);
    }
}
