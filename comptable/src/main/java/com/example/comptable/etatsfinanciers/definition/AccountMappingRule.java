package com.example.comptable.etatsfinanciers.definition;

import java.util.List;

/**
 * Regle de correspondance comptes -> ligne, lue dans la definition versionnee (jamais codee dans un service). Un compte entre dans la regle si son numero commence
 * par un prefixe de {@code includePrefixes}, par aucun de {@code excludePrefixes}, et si son solde est du cote {@code side}.
 */
public record AccountMappingRule(MappingRole role, List<String> includePrefixes, List<String> excludePrefixes, BalanceSide side, Polarity polarity,
                                 VerificationStatus verificationStatus, ProvenanceType provenanceType) {

    public AccountMappingRule {
        includePrefixes = includePrefixes == null ? List.of() : List.copyOf(includePrefixes);
        excludePrefixes = excludePrefixes == null ? List.of() : List.copyOf(excludePrefixes);
        side = side == null ? BalanceSide.ANY : side;
        polarity = polarity == null ? Polarity.EITHER : polarity;
    }

    public boolean accepte(String numero) {
        boolean inclus = includePrefixes.stream().anyMatch(numero::startsWith);
        return inclus && excludePrefixes.stream().noneMatch(numero::startsWith);
    }
}
