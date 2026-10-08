package com.example.comptable.etatsfinanciers.definition;

import java.util.List;

/** Ligne (poste ou solde intermediaire) d'un etat financier, avec sa provenance et son statut de verification. */
public record StatementLineDefinition(String code, String label, int order, String section, String parentCode, CalculationType calculationType,
                                      SignConvention signConvention, List<AccountMappingRule> mappingRules, String formula, String noteReference,
                                      VerificationStatus verificationStatus, ProvenanceType provenanceType, String officialSourceReference,
                                      String remark, String calculationBlockedReason) {

    public StatementLineDefinition {
        mappingRules = mappingRules == null ? List.of() : List.copyOf(mappingRules);
    }
}
