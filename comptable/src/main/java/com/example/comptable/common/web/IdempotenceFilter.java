package com.example.comptable.common.web;

import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Empêche qu'une saisie envoyée deux fois crée deux écritures ou deux pièces. L'application hors ligne envoie chaque saisie avec une clé unique (en-tête {@code Idempotency-Key}) ; si le réseau retombe avant la
 * réponse, elle renvoie la même saisie avec la même clé. Le serveur ne l'exécute qu'une fois et rejoue la réponse mémorisée aux renvois.
 *
 * <p>Limité à la création d'écritures, au dépôt de pièces et aux propositions d'opérations (les seules saisies mises en file d'attente hors ligne), placé APRÈS l'authentification : la clé est propre à l'utilisateur. Sans l'en-tête, le comportement
 * est strictement inchangé. Seules les réponses de succès (2xx) sont mémorisées ; un refus ou une panne libère la clé pour que le renvoi puisse réussir.
 */
public class IdempotenceFilter extends OncePerRequestFilter {

    public static final String ENTETE = "Idempotency-Key";
    static final String ENTETE_REJEU = "Idempotent-Replayed";

    private static final Pattern ROUTE = Pattern.compile("^/api/entreprises/[^/]+/(ecritures|documents|operations/(achat|vente|reglement|avoir)/proposition)$");
    private static final Pattern CLE_VALIDE = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final RequeteIdempotenteStore store;

    public IdempotenceFilter(RequeteIdempotenteStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(ENTETE) == null || !ROUTE.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !(authentification.getPrincipal() instanceof UtilisateurPrincipal principal)) {
            chain.doFilter(request, response); // non authentifié : la sécurité répond 401, sans mémoriser quoi que ce soit
            return;
        }
        String cle = request.getHeader(ENTETE).trim();
        if (!CLE_VALIDE.matcher(cle).matches()) {
            erreur(response, HttpServletResponse.SC_BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Clé d'idempotence invalide (8 à 100 caractères : lettres, chiffres, - et _).");
            return;
        }
        UUID utilisateur = principal.getId();
        RequeteIdempotenteStore.Resultat reservation = store.reserver(utilisateur, cle, request.getMethod() + " " + request.getRequestURI());
        switch (reservation.etat()) {
            case REJEU -> rejouer(response, reservation);
            case EN_COURS -> {
                response.setHeader("Retry-After", "2");
                // 503 : l'application hors ligne garde la saisie en attente et réessaie (un 409 serait pris pour un refus définitif)
                erreur(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "REQUETE_EN_COURS", "Cette saisie est déjà en cours d'enregistrement : réessayez dans un instant.");
            }
            case CLE_REUTILISEE -> erreur(response, 422, "IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour une autre opération.");
            case NOUVELLE -> executer(request, response, chain, utilisateur, cle);
        }
    }

    private void executer(HttpServletRequest request, HttpServletResponse response, FilterChain chain, UUID utilisateur, String cle) throws ServletException, IOException {
        ContentCachingResponseWrapper enveloppe = new ContentCachingResponseWrapper(response);
        boolean termine = false;
        try {
            chain.doFilter(request, enveloppe);
            int statut = enveloppe.getStatus();
            if (statut >= 200 && statut < 300) {
                store.terminer(utilisateur, cle, statut, new String(enveloppe.getContentAsByteArray(), StandardCharsets.UTF_8), enveloppe.getContentType());
                termine = true;
            }
        } finally {
            if (!termine) {
                store.liberer(utilisateur, cle);
            }
            enveloppe.copyBodyToResponse();
        }
    }

    private static void rejouer(HttpServletResponse response, RequeteIdempotenteStore.Resultat memorise) throws IOException {
        response.setStatus(memorise.statut());
        response.setHeader(ENTETE_REJEU, "true");
        if (memorise.typeContenu() != null) {
            response.setContentType(memorise.typeContenu());
        }
        if (memorise.corps() != null) {
            response.getOutputStream().write(memorise.corps().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void erreur(HttpServletResponse response, int statut, String code, String message) throws IOException {
        response.setStatus(statut);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "\\\"") + "\"}");
    }
}
