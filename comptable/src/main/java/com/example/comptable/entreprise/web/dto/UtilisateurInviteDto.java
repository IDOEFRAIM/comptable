package com.example.comptable.entreprise.web.dto;

import com.example.comptable.identite.domain.Utilisateur;

import java.util.UUID;

/**
 * Le mot de passe temporaire n'apparait qu'ici, une seule fois, en clair : il n'est
 * jamais journalise ni re-consultable (seul son hash BCrypt est persiste).
 * Le cabinet doit le communiquer lui-meme au collaborateur/client invite.
 */
public record UtilisateurInviteDto(
        UUID id,
        String email,
        String nom,
        String prenom,
        String role,
        String motDePasseTemporaire
) {
    public static UtilisateurInviteDto from(Utilisateur utilisateur, String role, String motDePasseTemporaire) {
        return new UtilisateurInviteDto(
                utilisateur.getId(),
                utilisateur.getEmail(),
                utilisateur.getNom(),
                utilisateur.getPrenom(),
                role,
                motDePasseTemporaire
        );
    }
}
