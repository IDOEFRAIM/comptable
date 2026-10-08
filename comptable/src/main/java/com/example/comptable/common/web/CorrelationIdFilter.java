package com.example.comptable.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifiant de corrélation de chaque requête : repris de l'en-tête {@code X-Request-Id} s'il est sain (16 à 64 caractères alphanumériques / tirets), sinon généré. Il est placé dans le MDC (donc dans chaque
 * ligne de journal), renvoyé dans l'en-tête de réponse et dans le corps de toute erreur : le support retrouve une requête de bout en bout. Distinct de l'audit métier.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String EN_TETE = "X-Request-Id";
    public static final String CLE_MDC = "correlationId";
    private static final Pattern SAIN = Pattern.compile("^[A-Za-z0-9-]{16,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String recu = request.getHeader(EN_TETE);
        String id = recu != null && SAIN.matcher(recu).matches() ? recu : UUID.randomUUID().toString();
        MDC.put(CLE_MDC, id);
        response.setHeader(EN_TETE, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CLE_MDC);
        }
    }
}
