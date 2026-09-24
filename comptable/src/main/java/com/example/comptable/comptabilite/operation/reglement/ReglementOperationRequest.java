package com.example.comptable.comptabilite.operation.reglement;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** {@code type} doit etre REGLEMENT_CLIENT ou REGLEMENT_FOURNISSEUR - tout autre valeur est
 * refusee (422) par le moteur. Pas de HT/TVA : un reglement est un mouvement de tresorerie pur. */
public record ReglementOperationRequest(
        @NotNull AccountingOperationType type,
        @NotNull UUID tiersId,
        @NotNull LocalDate date,
        String reference,
        @NotNull String libelle,
        @NotNull BigDecimal montant,
        @NotNull MoyenPaiement moyenPaiement
) {
}
