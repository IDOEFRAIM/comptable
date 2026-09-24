package com.example.comptable.comptabilite.parametrage;

import java.util.UUID;

public record ResolvedAccount(
        AccountResolutionStatus status,
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        AccountResolutionSource source,
        NiveauConfiance confiance,
        String explication
) {
    public static ResolvedAccount nonConfigure(String explication) {
        return new ResolvedAccount(AccountResolutionStatus.NOT_CONFIGURED, null, null, null,
                AccountResolutionSource.AUCUN, NiveauConfiance.NON_APPLICABLE, explication);
    }

    public static ResolvedAccount ambigu(String explication) {
        return new ResolvedAccount(AccountResolutionStatus.AMBIGUOUS, null, null, null,
                AccountResolutionSource.AUCUN, NiveauConfiance.NON_APPLICABLE, explication);
    }

    public static ResolvedAccount invalide(String explication) {
        return new ResolvedAccount(AccountResolutionStatus.INVALID, null, null, null,
                AccountResolutionSource.AUCUN, NiveauConfiance.NON_APPLICABLE, explication);
    }

    public boolean isResolved() {
        return status == AccountResolutionStatus.RESOLVED;
    }
}
