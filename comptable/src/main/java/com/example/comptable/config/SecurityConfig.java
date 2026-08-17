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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
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
                        .requestMatchers("/api/entreprises/*/etats-financiers/**")
                        .hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")
                        .requestMatchers("/api/entreprises/*/comptes", "/api/entreprises/*/journaux",
                                "/api/entreprises/*/exercices", "/api/entreprises/*/exercices/**")
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
