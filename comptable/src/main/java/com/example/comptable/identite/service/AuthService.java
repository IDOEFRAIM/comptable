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
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
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

    public AuthResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.motDePasse()));
            var principal = (UtilisateurPrincipal) authentication.getPrincipal();
            return toAuthResponse(principal);
        } catch (BadCredentialsException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
        }
    }

    @Transactional
    public AuthResponse registerCabinet(RegisterCabinetRequest request) {
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
        return new AuthResponse(token, principal.getId(), principal.getTenantId(), principal.getUsername(), roles);
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
