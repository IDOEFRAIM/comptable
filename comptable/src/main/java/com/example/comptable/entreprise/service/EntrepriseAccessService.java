package com.example.comptable.entreprise.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Verification d'acces partagee par tous les modules operant sur une entreprise :
 * isolation tenant stricte (non negociable), puis restriction CLIENT au portail
 * (l'entreprise doit lui avoir ete explicitement rattachee via utilisateur_entreprise).
 * EXPERT_COMPTABLE/COLLABORATEUR ont acces a toute entreprise de leur tenant.
 */
@Service
@RequiredArgsConstructor
public class EntrepriseAccessService {

    private final EntrepriseRepository entrepriseRepository;
    private final UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;

    public Entreprise verifierAcces(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseRepository.findById(entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable"));

        if (!entreprise.getTenant().getId().equals(principal.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable");
        }

        boolean estClient = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT"));
        if (estClient && !utilisateurEntrepriseRepository.existsByIdUtilisateurIdAndIdEntrepriseId(principal.getId(), entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acces non autorise a cette entreprise");
        }

        return entreprise;
    }
}
