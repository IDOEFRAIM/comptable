package com.example.comptable.security.jwt;

import com.example.comptable.common.tenant.TenantContext;
import com.example.comptable.security.CustomUserDetailsService;
import com.example.comptable.security.UtilisateurPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parseClaims(header.substring(7));

                String tenantId = claims.get("tenantId", String.class);
                if (tenantId != null) {
                    TenantContext.set(UUID.fromString(tenantId));
                }

                UtilisateurPrincipal principal = userDetailsService.loadUserById(claims.getSubject());
                Integer version = claims.get("cv", Integer.class);
                // Compte désactivé ou mot de passe modifié depuis l'émission du jeton : le jeton est révoqué (effet immédiat, pas à l'expiration).
                if (!principal.isEnabled() || version == null || version != principal.getCredentialsVersion()) {
                    throw new JwtException("Jeton révoqué");
                }
                // Mot de passe temporaire ou réinitialisé : seule la route de changement de mot de passe est permise.
                if (principal.isMustChangePassword() && !"/api/auth/change-password".equals(request.getRequestURI())) {
                    TenantContext.clear();
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Vous devez choisir un nouveau mot de passe avant de continuer.\"}");
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
