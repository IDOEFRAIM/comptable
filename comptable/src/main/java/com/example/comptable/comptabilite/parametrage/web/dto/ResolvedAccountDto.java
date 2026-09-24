package com.example.comptable.comptabilite.parametrage.web.dto;

import com.example.comptable.comptabilite.parametrage.AccountResolutionSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionStatus;
import com.example.comptable.comptabilite.parametrage.NiveauConfiance;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;

import java.util.UUID;

public record ResolvedAccountDto(
        AccountResolutionStatus status,
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        AccountResolutionSource source,
        NiveauConfiance confiance,
        String explication
) {
    public static ResolvedAccountDto from(ResolvedAccount resolved) {
        return new ResolvedAccountDto(resolved.status(), resolved.compteId(), resolved.compteNumero(),
                resolved.compteIntitule(), resolved.source(), resolved.confiance(), resolved.explication());
    }
}
