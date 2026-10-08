package com.example.comptable.common.web;

import com.example.comptable.security.UtilisateurPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Journal d'accès structuré, placé APRÈS l'authentification (le tenant et l'utilisateur sont connus) : méthode, route normalisée (identifiants remplacés, jamais la requête ni un corps), statut, durée, tenant,
 * utilisateur. Aucun mot de passe, jeton, document ni donnée bancaire n'est journalisé. Compte aussi les réponses 5xx pour l'observabilité de pilote.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCES = LoggerFactory.getLogger("comptable.acces");
    private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DEPOT = Pattern.compile("^/api/depot/[^/]+");
    private static final Pattern NOMBRES = Pattern.compile("/\\d+");

    private final MeterRegistry metriques;

    public AccessLogFilter(MeterRegistry metriques) {
        this.metriques = metriques;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long debut = System.nanoTime();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UtilisateurPrincipal p) {
            MDC.put("user", String.valueOf(p.getId()));
            if (p.getTenantId() != null) {
                MDC.put("tenant", String.valueOf(p.getTenantId()));
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - debut) / 1_000_000;
            int statut = response.getStatus();
            if (statut >= 500) {
                metriques.counter("comptable.http.reponses.5xx").increment();
            }
            String route = normaliser(request.getRequestURI());
            if (statut >= 500) {
                ACCES.warn("{} {} -> {} ({} ms)", request.getMethod(), route, statut, ms);
            } else {
                ACCES.info("{} {} -> {} ({} ms)", request.getMethod(), route, statut, ms);
            }
            MDC.remove("user");
            MDC.remove("tenant");
        }
    }

    /** Route sans identifiant : {@code /api/entreprises/{id}/ecritures}. Le jeton d'un lien de dépôt public n'apparaît jamais dans les journaux. */
    static String normaliser(String uri) {
        String r = DEPOT.matcher(uri).replaceFirst("/api/depot/{jeton}");
        r = UUID_RE.matcher(r).replaceAll("{id}");
        return NOMBRES.matcher(r).replaceAll("/{n}");
    }
}
