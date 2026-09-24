package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.PlanComptableReference;

public record PlanComptableReferenceDto(
        Integer id,
        String numero,
        String intitule,
        Short classe,
        String nature,
        Integer parentId,
        Short niveau,
        String sensNormal,
        boolean compteCollectif,
        boolean compteDetail,
        boolean lettrable,
        boolean auxiliaireAutorise,
        boolean actif,
        String roleSysteme
) {
    public static PlanComptableReferenceDto from(PlanComptableReference r) {
        return new PlanComptableReferenceDto(
                r.getId(), r.getNumero(), r.getIntitule(), r.getClasse(),
                r.getNature() != null ? r.getNature().name() : null,
                r.getParent() != null ? r.getParent().getId() : null,
                r.getNiveau(),
                r.getSensNormal() != null ? r.getSensNormal().name() : null,
                r.isCompteCollectif(), r.isCompteDetail(), r.isLettrable(), r.isAuxiliaireAutorise(),
                r.isActif(),
                r.getRoleSysteme() != null ? r.getRoleSysteme().name() : null
        );
    }
}
