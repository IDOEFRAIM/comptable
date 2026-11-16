package com.example.comptable.admin;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Crée le tout premier SUPER_ADMIN au démarrage, <b>une seule fois</b> : seulement si aucun administrateur n'existe encore et si l'adresse et un mot de passe provisoire sont fournis par l'environnement
 * ({@code COMPTABLE_ADMIN_BOOTSTRAP_EMAIL} / {@code COMPTABLE_ADMIN_BOOTSTRAP_PASSWORD}). Le compte doit changer son mot de passe à la première connexion. Le mot de passe n'est jamais écrit dans les journaux.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAmorcage {

    static final String ROLE = "SUPER_ADMIN";

    private final UtilisateurRepository utilisateurRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrailService audit;

    @Value("${comptable.admin.bootstrap-email:}")
    private String email;

    @Value("${comptable.admin.bootstrap-password:}")
    private String motDePasse;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void amorcer() {
        if (email == null || email.isBlank() || motDePasse == null || motDePasse.isBlank()) {
            return;
        }
        if (utilisateurRepository.existsByRoleCode(ROLE)) {
            return; // un administrateur existe déjà : les variables sont ignorées
        }
        String adresse = email.trim().toLowerCase();
        if (utilisateurRepository.existsByEmail(adresse)) {
            log.warn("Amorçage de l'administrateur ignoré : l'adresse {} est déjà utilisée par un autre compte.", adresse);
            return;
        }
        try {
            PasswordPolicy.valider(motDePasse, adresse);
        } catch (ResponseStatusException e) {
            log.warn("Amorçage de l'administrateur ignoré : le mot de passe provisoire ne respecte pas la politique ({}).", e.getReason());
            return;
        }
        Utilisateur admin = new Utilisateur();
        admin.setTenant(null);
        admin.setEmail(adresse);
        admin.setMotDePasse(passwordEncoder.encode(motDePasse));
        admin.setNom("Administrateur");
        admin.setPrenom("Plateforme");
        admin.setRoles(Set.of(roleRepository.findByCode(ROLE).orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Rôle SUPER_ADMIN introuvable, seed manquant"))));
        admin.setMustChangePassword(true);
        utilisateurRepository.save(admin);
        audit.enregistrerEvenementSysteme(null, "ADMIN_AMORCE", "UTILISATEUR", admin.getId().toString(), Map.of("email", adresse));
        log.info("Premier administrateur de la plateforme créé : {} (changement de mot de passe obligatoire à la première connexion). Retirez le mot de passe provisoire de la configuration.", adresse);
    }
}
