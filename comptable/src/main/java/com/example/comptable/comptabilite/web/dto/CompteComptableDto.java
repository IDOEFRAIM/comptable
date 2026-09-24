package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.CompteComptable;

import java.util.UUID;

/** Enrichi additivement pour la fondation plan comptable (parentId/niveau/actif/reference*) :
 * les champs existants ne changent pas de forme, un ancien consommateur ignore simplement les
 * champs supplementaires. */
public record CompteComptableDto(
        UUID id,
        String numero,
        String intitule,
        String nature,
        boolean lettrable,
        UUID parentId,
        short niveau,
        boolean actif,
        Integer referenceId,
        String referenceNumero,
        String sensNormal,
        boolean compteCollectif,
        boolean compteDetail,
        boolean auxiliaireAutorise,
        Integer mappingsActifsCount
) {
    public static CompteComptableDto from(CompteComptable compte) {
        return from(compte, null);
    }

    /** {@code mappingsActifsCount} n'est renseigne que lors d'une desactivation (voir
     * PlanComptableService.modifier) - section 23 de la mission Etape 6 : avertit le comptable que
     * N parametres comptables referencent encore ce compte, sans bloquer la desactivation elle-meme
     * ni supprimer silencieusement ces mappings. Null hors de ce cas (jamais calcule pour une
     * simple lecture, pour ne pas alourdir lister()/arbre()). */
    public static CompteComptableDto from(CompteComptable compte, Integer mappingsActifsCount) {
        return new CompteComptableDto(
                compte.getId(),
                compte.getNumero(),
                compte.getIntitule(),
                compte.getNature() != null ? compte.getNature().name() : null,
                compte.isLettrable(),
                compte.getParent() != null ? compte.getParent().getId() : null,
                compte.getNiveau(),
                compte.isActif(),
                compte.getReference() != null ? compte.getReference().getId() : null,
                compte.getReference() != null ? compte.getReference().getNumero() : null,
                compte.getSensNormal() != null ? compte.getSensNormal().name() : null,
                compte.isCompteCollectif(),
                // Valeur informative pour l'affichage ; la verification qui compte (y compris pour
                // un compte local sans reference) reste CompteComptableValidationService.isUsableCommeDetail.
                compte.getReference() == null || compte.getReference().isCompteDetail(),
                compte.isAuxiliaireAutorise(),
                mappingsActifsCount
        );
    }
}
