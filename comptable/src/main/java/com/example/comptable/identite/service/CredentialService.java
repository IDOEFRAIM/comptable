package com.example.comptable.identite.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.PasswordPolicy;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des identifiants : verrouillage après échecs (en base : sûr avec plusieurs instances), changement de mot de passe, réinitialisation à usage unique émise par un expert du cabinet (aucune
 * infrastructure e-mail n'est supposée : le jeton est remis une seule fois à l'expert, jamais journalisé, seule son empreinte SHA-256 est stockée). Toute modification de mot de passe incrémente
 * {@code credentials_version} : les jetons de session émis avant sont révoqués.
 */
@Service
public class CredentialService {

    public record ReinitialisationEmise(String jeton, OffsetDateTime expireAt, String chemin) {
    }

    private static final SecureRandom ALEA = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UtilisateurRepository utilisateurRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrailService audit;
    private final Clock clock;
    private final int maxEchecs;
    private final long verrouMinutes;
    private final long validiteReinitialisationHeures;

    public CredentialService(JdbcTemplate jdbc, UtilisateurRepository utilisateurRepository, PasswordEncoder passwordEncoder, AuditTrailService audit, Clock clock,
                             @Value("${security.login.max-echecs:5}") int maxEchecs, @Value("${security.login.verrou-minutes:15}") long verrouMinutes,
                             @Value("${security.reset.validite-heures:24}") long validiteReinitialisationHeures) {
        this.jdbc = jdbc;
        this.utilisateurRepository = utilisateurRepository;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
        this.maxEchecs = maxEchecs;
        this.verrouMinutes = verrouMinutes;
        this.validiteReinitialisationHeures = validiteReinitialisationHeures;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ verrouillage

    /** Le compte est-il verrouillé ? (même réponse générique pour un compte inconnu : on ne révèle rien). */
    public boolean verrouille(String email) {
        Integer n = jdbc.queryForObject("select count(*) from utilisateur where lower(email) = lower(?) and locked_until is not null and locked_until > ?", Integer.class, email, clock.instant().atOffset(java.time.ZoneOffset.UTC));
        return n != null && n > 0;
    }

    /** Enregistre un échec (SQL atomique, hors transaction appelante) ; au seuil, le compte est verrouillé et le compteur remis à zéro. */
    public void enregistrerEchec(String email) {
        OffsetDateTime maintenant = clock.instant().atOffset(java.time.ZoneOffset.UTC);
        jdbc.update("""
                update utilisateur set
                    locked_until = case when failed_login_count + 1 >= ? then ? else locked_until end,
                    failed_login_count = case when failed_login_count + 1 >= ? then 0 else failed_login_count + 1 end
                where lower(email) = lower(?)
                """, maxEchecs, maintenant.plusMinutes(verrouMinutes), maxEchecs, email);
    }

    public void enregistrerSucces(UUID utilisateurId) {
        jdbc.update("update utilisateur set failed_login_count = 0, locked_until = null, last_login_at = ? where id = ?", clock.instant().atOffset(java.time.ZoneOffset.UTC), utilisateurId);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ changement

    /** Changement par l'utilisateur connecté : vérifie le mot de passe actuel, applique la politique, révoque les anciens jetons. */
    @Transactional
    public Utilisateur changerMotDePasse(UtilisateurPrincipal principal, String actuel, String nouveau) {
        Utilisateur u = utilisateurRepository.findById(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        if (actuel == null || !passwordEncoder.matches(actuel, u.getMotDePasse())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le mot de passe actuel est incorrect");
        }
        PasswordPolicy.valider(nouveau, u.getEmail());
        if (passwordEncoder.matches(nouveau, u.getMotDePasse())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le nouveau mot de passe doit être différent de l'actuel");
        }
        appliquer(u, nouveau);
        audit.enregistrerActionHumaine(u.getTenant() == null ? null : u.getTenant().getId(), u.getId(), "MOT_DE_PASSE_CHANGE", "UTILISATEUR", u.getId().toString(), Map.of());
        return u;
    }

    private void appliquer(Utilisateur u, String nouveau) {
        u.setMotDePasse(passwordEncoder.encode(nouveau));
        u.setMustChangePassword(false);
        u.setCredentialsVersion(u.getCredentialsVersion() + 1);
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        utilisateurRepository.save(u);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ réinitialisation

    /** Émise par un expert du cabinet pour un membre de SON cabinet. Le jeton n'est renvoyé qu'ici, une seule fois. */
    @Transactional
    public ReinitialisationEmise emettreReinitialisation(UtilisateurPrincipal expert, UUID membreId) {
        Utilisateur u = utilisateurRepository.findById(membreId).filter(x -> x.getTenant() != null && expert.getTenantId() != null && expert.getTenantId().equals(x.getTenant().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membre introuvable"));
        jdbc.update("update password_reset_token set used_at = ? where utilisateur_id = ? and used_at is null", clock.instant().atOffset(java.time.ZoneOffset.UTC), u.getId());   // un seul jeton valide à la fois
        byte[] octets = new byte[32];
        ALEA.nextBytes(octets);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        OffsetDateTime expire = clock.instant().atOffset(java.time.ZoneOffset.UTC).plusHours(validiteReinitialisationHeures);
        jdbc.update("insert into password_reset_token (id, utilisateur_id, token_hash, expires_at, created_by, created_at) values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), u.getId(), sha256(jeton), expire,
                expert.getId(), clock.instant().atOffset(java.time.ZoneOffset.UTC));
        audit.enregistrerActionHumaine(expert.getTenantId(), expert.getId(), "MOT_DE_PASSE_REINITIALISATION_EMISE", "UTILISATEUR", u.getId().toString(), Map.of("expire", expire.toString()));
        return new ReinitialisationEmise(jeton, expire, "/reinitialiser-mot-de-passe?jeton=" + jeton);
    }

    /** Consommation publique du jeton : usage unique, expiration, politique de mot de passe ; réponse identique pour un jeton inconnu, expiré ou déjà utilisé. */
    @Transactional
    public void reinitialiser(String jeton, String nouveau) {
        List<Map<String, Object>> l = jdbc.queryForList("select id, utilisateur_id from password_reset_token where token_hash = ? and used_at is null and expires_at > ? for update", jeton == null ? "" : sha256(jeton),
                clock.instant().atOffset(java.time.ZoneOffset.UTC));
        if (l.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lien de réinitialisation invalide ou expiré");
        }
        Utilisateur u = utilisateurRepository.findById((UUID) l.get(0).get("utilisateur_id")).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lien de réinitialisation invalide ou expiré"));
        PasswordPolicy.valider(nouveau, u.getEmail());
        appliquer(u, nouveau);
        jdbc.update("update password_reset_token set used_at = ? where id = ?", clock.instant().atOffset(java.time.ZoneOffset.UTC), l.get(0).get("id"));
        audit.enregistrerEvenementSysteme(u.getTenant() == null ? null : u.getTenant().getId(), "MOT_DE_PASSE_REINITIALISE", "UTILISATEUR", u.getId().toString(), Map.of());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
