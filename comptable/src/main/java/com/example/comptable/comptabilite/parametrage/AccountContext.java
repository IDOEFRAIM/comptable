package com.example.comptable.comptabilite.parametrage;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;

import java.util.Map;
import java.util.UUID;

/**
 * Contexte d'une resolution de compte. {@code contextKey} est calcule par l'appelant (ex.
 * "ESPECES" pour un reglement en caisse) - pas de DSL, une simple chaine comparee a l'egalite
 * (mission Etape 6, section 7). {@code metadata} permet d'ajouter des precisions futures sans
 * modifier cette signature (section 5).
 */
public record AccountContext(
        AccountingOperationType operationType,
        String natureOperation,
        UUID articleId,
        String tiersType,
        String pays,
        String contextKey,
        Map<String, String> metadata
) {
    public static AccountContext vide() {
        return new AccountContext(null, null, null, null, null, null, Map.of());
    }

    public AccountContext avecContextKey(String contextKey) {
        return new AccountContext(operationType, natureOperation, articleId, tiersType, pays, contextKey, metadata);
    }
}
