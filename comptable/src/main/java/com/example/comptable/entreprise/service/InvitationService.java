package com.example.comptable.entreprise.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.web.dto.InviterUtilisateurRequest;
import com.example.comptable.entreprise.web.dto.RoleInvitable;
import com.example.comptable.entreprise.web.dto.UtilisateurInviteDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Set;

/**
 * Cree un compte pour un CLIENT (rattache a une entreprise precise via utilisateur_entreprise,
 * pour le portail) ou un COLLABORATEUR (rattache au tenant, deja plein acces a toutes les
 * entreprises du cabinet). Aucune infrastructure email n'existe encore : un mot de passe
 * temporaire est genere et renvoye une seule fois, a communiquer manuellement.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final String ALPHABET_MOT_DE_PASSE = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LONGUEUR_MOT_DE_PASSE = 12;

    private final EntrepriseAccessService entrepriseAccessService;
    private final UtilisateurRepository utilisateurRepository;
    private final RoleRepository roleRepository;
    private final UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public UtilisateurInviteDto inviter(java.util.UUID entrepriseId, InviterUtilisateurRequest request,
                                         UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        if (utilisateurRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet email est deja utilise");
        }

        Role role = roleRepository.findByCode(request.role().name())
                .orElseThrow(() -> new IllegalStateException("Role " + request.role() + " introuvable, seed manquant"));

        String motDePasseTemporaire = genererMotDePasseTemporaire();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(entreprise.getTenant());
        utilisateur.setEmail(request.email());
        utilisateur.setMotDePasse(passwordEncoder.encode(motDePasseTemporaire));
        utilisateur.setNom(request.nom());
        utilisateur.setPrenom(request.prenom());
        utilisateur.setTelephone(request.telephone());
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        if (request.role() == RoleInvitable.CLIENT) {
            utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(utilisateur.getId(), entrepriseId));
        }

        return UtilisateurInviteDto.from(utilisateur, request.role().name(), motDePasseTemporaire);
    }

    private String genererMotDePasseTemporaire() {
        StringBuilder motDePasse = new StringBuilder(LONGUEUR_MOT_DE_PASSE);
        for (int i = 0; i < LONGUEUR_MOT_DE_PASSE; i++) {
            motDePasse.append(ALPHABET_MOT_DE_PASSE.charAt(secureRandom.nextInt(ALPHABET_MOT_DE_PASSE.length())));
        }
        return motDePasse.toString();
    }
}
