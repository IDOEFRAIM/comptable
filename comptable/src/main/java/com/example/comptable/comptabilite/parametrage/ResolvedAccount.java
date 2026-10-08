package com.example.comptable.comptabilite.parametrage;

import java.util.UUID;

public record ResolvedAccount(
        AccountResolutionStatus status,
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        AccountResolutionSource source,
        NiveauConfiance confiance,
        String explication,
        /** Code machine de l'echec ({@code null} si resolu) : seul ce code est exploite, jamais {@code explication}. */
        AccountResolutionCode code
) {
    /** Forme historique (sans code) : le code est derive du statut. */
    public ResolvedAccount(AccountResolutionStatus status, UUID compteId, String compteNumero, String compteIntitule,
                           AccountResolutionSource source, NiveauConfiance confiance, String explication) {
        this(status, compteId, compteNumero, compteIntitule, source, confiance, explication, codeParDefaut(status));
    }

    private static AccountResolutionCode codeParDefaut(AccountResolutionStatus status) {
        return switch (status) {
            case RESOLVED -> null;
            case AMBIGUOUS -> AccountResolutionCode.ACCOUNT_MAPPING_AMBIGUOUS;
            case NOT_CONFIGURED -> AccountResolutionCode.ACCOUNT_NOT_CONFIGURED;
            case INVALID -> AccountResolutionCode.ACCOUNT_RESOLUTION_FAILED;
        };
    }

    public static ResolvedAccount nonConfigure(String explication) {
        return new ResolvedAccount(AccountResolutionStatus.NOT_CONFIGURED, null, null, null,
                AccountResolutionSource.AUCUN, NiveauConfiance.NON_APPLICABLE, explication);
    }

    public static ResolvedAccount ambigu(String explication) {
        return new ResolvedAccount(AccountResolutionStatus.AMBIGUOUS, null, null, null,
                AccountResolutionSource.AUCUN, NiveauConfiance.NON_APPLICABLE, explication);
    }

    public static ResolvedAccount invalide(String explication) {
        return invalide(explication, AccountResolutionCode.ACCOUNT_RESOLUTION_FAILED);
    }

    public static ResolvedAccount invalide(String explication, AccountResolutionCode code) {
        return new ResolvedAccount(AccountResolutionStatus.INVALID, null, null, null,
                AccountResolutionSource.AUCUN, NiveauConfiance.NON_APPLICABLE, explication, code);
    }

    public boolean isResolved() {
        return status == AccountResolutionStatus.RESOLVED;
    }
}
