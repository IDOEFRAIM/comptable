package com.example.comptable.config;

import com.example.comptable.security.jwt.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Requête non authentifiée (jeton absent, expiré, révoqué, émis avant un changement de mot de passe) : 401, et non le 403 par défaut de Spring. Le 401 est ce qui ramène l'interface à la connexion ;
                // un refus de rôle (utilisateur connecté sans le droit) reste un 403.
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Session absente ou expirée : reconnectez-vous.\"}");
                }))
                .authorizeHttpRequests(auth -> auth
                        // Changement de mot de passe : réservé à un utilisateur authentifié (la seule route permise avec un mot de passe temporaire). Doit précéder /api/auth/**.
                        .requestMatchers("/api/auth/change-password").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Sondes d'exploitation : seulement l'état (aucun détail, aucune information interne) ; tout autre point d'actuator reste protégé.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Le webhook Meta (/api/v1/whatsapp/webhook) est desormais recu directement
                        // par ai-service (FastAPI), plus par ce module Spring Boot - voir
                        // ai-service/app/api/v1/endpoints/whatsapp.py. Seul l'appel retour
                        // ci-dessous, depuis ai-service vers ce module, existe encore ici.
                        // Appele par le microservice IA (ai-service, FastAPI), jamais par un
                        // utilisateur authentifie : securise par cle partagee X-Internal-Api-Key
                        // (cf. WhatsAppInternalIngestionController), pas par le JWT applicatif -
                        // meme principe que le webhook Meta ci-dessus.
                        .requestMatchers("/api/v1/internal/whatsapp/documents").permitAll()
                        // Outils appeles par l'agent comptable autonome (ai-service,
                        // agent_orchestrator.py) pendant sa boucle de raisonnement - meme principe
                        // d'authentification par cle partagee que la ligne ci-dessus.
                        .requestMatchers("/api/v1/internal/agent-tools/**").permitAll()
                        // Validation de proposition comptable appelee par le workflow LangGraph
                        // Python "AI Accounting" (STEP 4) - meme principe d'authentification par
                        // cle partagee que les deux lignes ci-dessus, jamais un utilisateur final.
                        .requestMatchers("/api/v1/internal/ai-accounting/**").permitAll()
                        // Financial Intelligence Layer (STEP 8) appelee par le workflow LangGraph
                        // Python "Financial Health" - meme principe d'authentification par cle
                        // partagee que la ligne ci-dessus, jamais un utilisateur final.
                        .requestMatchers("/api/v1/internal/ai-finance/**").permitAll()
                        // Webhook de confirmation asynchrone des fournisseurs de telepaiement fiscal
                        // (e-Syntaxe, Faso Arzeka) - appele par eux, jamais par un utilisateur final,
                        // donc pas de JWT possible. Securise par cle partagee X-Webhook-Api-Key (voir
                        // PaiementWebhookController), meme principe que les endpoints internes ci-dessus.
                        .requestMatchers("/api/webhooks/paiements/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/inviter")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/documents")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.GET, "/api/entreprises/*/documents")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/tiers/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/documents/*/comptabiliser")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.GET, "/api/entreprises/*/documents/*/fichier")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Phase B/C : revue des constats, lineage comptable, Hub documents (rapprochement, doublons, validation) et
                        // demande de piece depuis un constat : travail du cabinet, jamais du role CLIENT (qui garde le depot, la liste
                        // de ses pieces et la reponse a une demande). Matchers obligatoires : sans eux ces routes tomberaient sur
                        // anyRequest().authenticated() et un CLIENT pourrait lire/ecarter les constats internes.
                        .requestMatchers("/api/entreprises/*/revue/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/lineage/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/documents/hub/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.GET, "/api/entreprises/*/documents/*/candidats", "/api/entreprises/*/documents/*/originaux-possibles")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/documents/*/associer", "/api/entreprises/*/documents/*/rejeter-proposition",
                                "/api/entreprises/*/documents/*/marquer-doublon", "/api/entreprises/*/documents/*/valider",
                                "/api/entreprises/*/documents/*/retraiter", "/api/entreprises/*/documents/*/lier-ecriture")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/demandes-pieces/depuis-constat/*")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Phase C+ : collecte active (demandes groupees, envoi, relance, synthese, metriques) et association manuelle
                        // piece <-> demande : travail du cabinet. Le CLIENT garde le depot, sa liste simplifiee et la reponse a une demande.
                        .requestMatchers("/api/entreprises/*/demandes-pieces/collecte/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/demandes-pieces/bulk", "/api/entreprises/*/demandes-pieces/*/envoyer",
                                "/api/entreprises/*/demandes-pieces/*/relancer", "/api/entreprises/*/demandes-pieces/*/marquer-recue")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.GET, "/api/entreprises/*/demandes-pieces/*/messages", "/api/entreprises/*/documents/*/demandes-candidates")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/documents/*/associer-demande")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Depot par lien : public, MAIS l'autorisation reelle est le jeton signe (verifie puis recharge la demande reelle).
                        .requestMatchers("/api/depot/**").permitAll()
                        .requestMatchers("/api/entreprises/*/documents-agent/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/ecritures", "/api/entreprises/*/ecritures/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/rapports/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/lettrage/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/rapprochements", "/api/entreprises/*/rapprochements/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Phase D : ecran Banque (import, rapprochements, affectation des reglements) = travail du cabinet, jamais du role CLIENT.
                        .requestMatchers("/api/entreprises/*/banque", "/api/entreprises/*/banque/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/immobilisations", "/api/entreprises/*/immobilisations/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/factures", "/api/entreprises/*/factures/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/factures-avoir", "/api/entreprises/*/factures-avoir/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/articles", "/api/entreprises/*/articles/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/mouvements-stock", "/api/entreprises/*/mouvements-stock/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/commandes-achat", "/api/entreprises/*/commandes-achat/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/avoirs-fournisseur", "/api/entreprises/*/avoirs-fournisseur/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/depots", "/api/entreprises/*/depots/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/inventaires", "/api/entreprises/*/inventaires/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/regularisations", "/api/entreprises/*/regularisations/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/provisions", "/api/entreprises/*/provisions/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/depreciations", "/api/entreprises/*/depreciations/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/etats-financiers-syscohada/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/comptes", "/api/entreprises/*/journaux",
                                "/api/entreprises/*/exercices", "/api/entreprises/*/exercices/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Fondation plan comptable SYSCOHADA (hierarchie/PCE) : meme role que la route
                        // /comptes ci-dessus. Le matcher global (sans scope entreprise) ci-dessous est
                        // obligatoire, pas optionnel - sans lui il tomberait sur anyRequest().authenticated()
                        // et laisserait un CLIENT le lire, exactement le bug deja corrige une fois pour
                        // /api/fiscalite/tax-rules/** (cf. commentaire plus bas).
                        .requestMatchers("/api/entreprises/*/plan-comptable", "/api/entreprises/*/plan-comptable/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/parametres-comptables", "/api/entreprises/*/parametres-comptables/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/plan-comptable-reference", "/api/plan-comptable-reference/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Espace TVA (Phase G) : travail du cabinet, jamais du role CLIENT.
                        .requestMatchers("/api/entreprises/*/fiscalite/tva", "/api/entreprises/*/fiscalite/tva/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/declarations-fiscales", "/api/entreprises/*/declarations-fiscales/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Correctif securite (mission P1.5, section 78) : cet endpoint tombait
                        // auparavant sur le authenticated() generique, incoherent avec les autres
                        // routes fiscalite - un CLIENT pouvait l'appeler.
                        .requestMatchers("/api/fiscalite/tax-rules", "/api/fiscalite/tax-rules/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/tableau-de-bord")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Inbox comptable (mission P0) : constats internes (anomalies, preuves
                        // manquantes...) potentiellement sur des tiers fournisseurs - jamais visible
                        // du role CLIENT, meme principe que le tableau de bord ci-dessus.
                        .requestMatchers("/api/entreprises/*/findings", "/api/entreprises/*/findings/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/dossier")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/comptabilisation/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Moteur d'operations comptables (achat/vente/reglement/avoir) : meme role
                        // que le reste de la comptabilite. Matcher obligatoire, pas optionnel -
                        // sans lui il tomberait sur anyRequest().authenticated() plus bas et un
                        // CLIENT pourrait y acceder (meme bug deja corrige pour plan-comptable et
                        // tax-rules, voir les commentaires plus haut/bas).
                        .requestMatchers("/api/entreprises/*/operations/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/cloture-readiness")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Cabinet Cockpit (mission P1) : vue portefeuille cross-entreprises, jamais
                        // accessible a un CLIENT (qui ne voit que ses propres entreprises).
                        .requestMatchers("/api/cabinet/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Financial Intelligence (mission P1) : tresorerie/snapshot financier,
                        // meme perimetre que le Dossier Comptable Intelligent ci-dessus.
                        .requestMatchers("/api/entreprises/*/tresorerie/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/financial-snapshot", "/api/entreprises/*/financial-snapshot/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Copilot Evidence-First niveau comptable (mission P1) : lecture seule,
                        // jamais accessible directement a un CLIENT dans cette premiere version.
                        .requestMatchers("/api/entreprises/*/copilot/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Copilot Dirigeant (mission P1.5, section 46) : meme service Java, sous-ensemble
                        // d'intentions au vocabulaire non-comptable - seul endpoint Copilot ouvert au CLIENT.
                        .requestMatchers("/api/entreprises/*/copilot-dirigeant/**")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Feedback Copilot (mission P1.9, section 30) : accessible depuis toutes les
                        // portees, y compris le Dirigeant qui doit pouvoir noter ses propres reponses -
                        // l'isolation reelle est faite cote service (tenant du principal == tenant de la
                        // question), pas ici.
                        .requestMatchers("/api/copilot/queries/**")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Recouvrement intelligent (mission P1) : meme perimetre que le reste de
                        // l'intelligence financiere ci-dessus.
                        .requestMatchers("/api/entreprises/*/recouvrement/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        // Compliance Center (mission P1) : meme perimetre que la fiscalite P0.
                        .requestMatchers("/api/entreprises/*/conformite/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.GET, "/api/entreprises/*/demandes-pieces")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/demandes-pieces/*/repondre")
                        .hasAnyRole("CLIENT", "COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/demandes-pieces")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers(HttpMethod.POST, "/api/entreprises/*/demandes-pieces/*/annuler")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/relances/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/notifications/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new com.example.comptable.common.web.AccessLogFilter(meterRegistry), JwtAuthFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
