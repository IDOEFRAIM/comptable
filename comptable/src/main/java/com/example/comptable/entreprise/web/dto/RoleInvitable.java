package com.example.comptable.entreprise.web.dto;

/** Un cabinet ne peut inviter que ces deux roles : jamais EXPERT_COMPTABLE (reserve a register-cabinet) ni SUPER_ADMIN. */
public enum RoleInvitable {
    CLIENT,
    COLLABORATEUR
}
