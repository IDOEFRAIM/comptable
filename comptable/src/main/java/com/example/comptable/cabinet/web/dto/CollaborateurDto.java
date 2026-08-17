package com.example.comptable.cabinet.web.dto;

import java.util.UUID;

/** Mission P1.5 (section 51) : source du selecteur "assigner a un collaborateur" pour un
 * Finding - liste tous les COLLABORATEUR/EXPERT_COMPTABLE du tenant, y compris ceux n'ayant
 * encore aucune tache assignee (contrairement a {@link WorkloadDto}, derive uniquement des
 * Finding deja assignes). */
public record CollaborateurDto(
        UUID id,
        String nomComplet
) {
}
