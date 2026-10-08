package com.example.comptable.identite.service;

import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.identite.web.dto.AuthResponse;
import com.example.comptable.identite.web.dto.LoginRequest;
import com.example.comptable.identite.web.dto.RegisterCabinetRequest;
import com.example.comptable.security.PasswordPolicy;
import com.example.comptable.security.RateLimiter;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TenantRepository tenantRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CredentialService credentialService;
    private final RateLimiter rateLimiter;

    /** OPEN (développement, tests) : l'inscription d'un cabinet est libre. PROVISIONING (pilote, production) : elle exige le jeton d'approvisionnement de l'opérateur. */
    @Value("${comptable.registration.mode:OPEN}")
    private String registrationMode;

    @Value("${comptable.provisioning.token:}")
    private String provisioningToken;

    /** Débits maximaux par adresse (fenêtres fixes : connexion et réinitialisation 10 min, inscription 1 h). Relevés uniquement pour la suite E2E, qui crée un cabinet par scénario depuis une seule adresse. */
    @Value("${security.rate.login-max:30}")
    private int loginMax;
    @Value("${security.rate.reset-max:10}")
    private int resetMax;
    @Value("${security.rate.register-max:10}")
    private int registerMax;

    /** Connexion : débit limité par adresse, verrouillage du compte après échecs répétés ; la réponse est identique pour un compte inconnu, verrouillé ou un mot de passe faux. */
    public AuthResponse login(LoginRequest request, String adresse) {
        rateLimiter.tenter("login|" + adresse, loginMax, 600);
        if (credentialService.verrouille(request.email())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop de tentatives. Réessayez dans quelques minutes.");
        }
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.motDePasse()));
            var principal = (UtilisateurPrincipal) authentication.getPrincipal();
            credentialService.enregistrerSucces(principal.getId());
            return toAuthResponse(principal);
        } catch (BadCredentialsException e) {
            credentialService.enregistrerEchec(request.email());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
        }
    }

    public AuthResponse login(LoginRequest request) {
        return login(request, "interne");
    }

    /** Changement de mot de passe : renvoie un nouveau jeton (les anciens sont révoqués) sans l'obligation de changement. */
    public AuthResponse changerMotDePasse(UtilisateurPrincipal principal, String actuel, String nouveau) {
        return toAuthResponse(new UtilisateurPrincipal(credentialService.changerMotDePasse(principal, actuel, nouveau)));
    }

    public void reinitialiserMotDePasse(String jeton, String nouveau, String adresse) {
        rateLimiter.tenter("reset|" + adresse, resetMax, 600);
        credentialService.reinitialiser(jeton, nouveau);
    }

    public AuthResponse registerCabinet(RegisterCabinetRequest request) {
        return registerCabinet(request, null, "interne");
    }

    /** Création d'un cabinet et de son premier expert-comptable. En mode PROVISIONING, seul l'opérateur détenteur du jeton d'approvisionnement peut le faire (aucune inscription publique en pilote). */
    @Transactional
    public AuthResponse registerCabinet(RegisterCabinetRequest request, String jetonApprovisionnement, String adresse) {
        rateLimiter.tenter("register|" + adresse, registerMax, 3600);
        if (!"OPEN".equalsIgnoreCase(registrationMode)) {
            boolean autorise = !provisioningToken.isBlank() && jetonApprovisionnement != null && java.security.MessageDigest.isEqual(
                    provisioningToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), jetonApprovisionnement.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!autorise) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La création d'un cabinet est réservée à l'opérateur de la plateforme");
            }
        }
        PasswordPolicy.valider(request.motDePasse(), request.email());
        if (utilisateurRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet email est deja utilise");
        }

        Tenant tenant = new Tenant();
        tenant.setRaisonSociale(request.raisonSocialeCabinet());
        tenant.setSlug(genererSlugUnique(request.raisonSocialeCabinet()));
        tenant.setPays(request.pays().toUpperCase());
        tenant = tenantRepository.save(tenant);

        Role roleExpertComptable = roleRepository.findByCode("EXPERT_COMPTABLE")
                .orElseThrow(() -> new IllegalStateException("Role EXPERT_COMPTABLE introuvable, seed manquant"));

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail(request.email());
        utilisateur.setMotDePasse(passwordEncoder.encode(request.motDePasse()));
        utilisateur.setNom(request.nom());
        utilisateur.setPrenom(request.prenom());
        utilisateur.setTelephone(request.telephone());
        utilisateur.setRoles(Set.of(roleExpertComptable));
        utilisateurRepository.save(utilisateur);

        return toAuthResponse(new UtilisateurPrincipal(utilisateur));
    }

    private AuthResponse toAuthResponse(UtilisateurPrincipal principal) {
        String token = jwtService.generateToken(principal);
        List<String> roles = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        return new AuthResponse(token, principal.getId(), principal.getTenantId(), principal.getUsername(), roles, principal.isMustChangePassword());
    }

    private String genererSlugUnique(String raisonSociale) {
        String base = Normalizer.normalize(raisonSociale, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "cabinet";
        }
        String slug = base;
        int suffixe = 2;
        while (tenantRepository.existsBySlug(slug)) {
            slug = base + "-" + suffixe++;
        }
        return slug;
    }
}
