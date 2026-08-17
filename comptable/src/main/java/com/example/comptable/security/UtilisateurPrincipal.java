package com.example.comptable.security;

import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class UtilisateurPrincipal implements UserDetails {

    private final UUID id;
    private final UUID tenantId;
    private final String email;
    private final String motDePasse;
    private final boolean actif;
    private final Set<GrantedAuthority> authorities;

    public UtilisateurPrincipal(Utilisateur utilisateur) {
        this.id = utilisateur.getId();
        this.tenantId = utilisateur.getTenant() != null ? utilisateur.getTenant().getId() : null;
        this.email = utilisateur.getEmail();
        this.motDePasse = utilisateur.getMotDePasse();
        this.actif = "ACTIF".equals(utilisateur.getStatut());
        this.authorities = utilisateur.getRoles().stream()
                .map(Role::getCode)
                .map(code -> "ROLE_" + code)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return motDePasse;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return actif;
    }

    @Override
    public boolean isAccountNonLocked() {
        return actif;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
