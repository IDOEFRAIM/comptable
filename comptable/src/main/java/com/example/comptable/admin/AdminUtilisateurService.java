package com.example.comptable.admin;

import com.example.comptable.admin.AdminDtos.Page;
import com.example.comptable.admin.AdminDtos.ReinitialisationAdmin;
import com.example.comptable.admin.AdminDtos.UtilisateurAdmin;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion des comptes par l'administrateur de la plateforme, tous cabinets confondus. Règles : jamais sur un compte administrateur (y compris soi-même : pas de prise de contrôle d'un compte privilégié), motif
 * obligatoire pour toute action qui change l'état d'un compte, et chaque action est tracée dans {@code audit_log}.
 */
@Service
@RequiredArgsConstructor
public class AdminUtilisateurService {

    private static final int MOTIF_MIN = 5;
    private static final int LONGUEUR_TEMPORAIRE = 16;
    private static final String MAJUSCULES = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String MINUSCULES = "abcdefghijkmnopqrstuvwxyz";
    private static final String CHIFFRES = "23456789";
    private static final SecureRandom ALEA = new SecureRandom();

    private static final String SELECT = """
            select u.id, u.email, u.nom, u.prenom, u.statut, u.tenant_id, t.raison_sociale, u.created_at, u.last_login_at, u.locked_until, u.failed_login_count, u.must_change_password,
                   (select string_agg(r.code, ',' order by r.code) from utilisateur_role ur join role r on r.id = ur.role_id where ur.utilisateur_id = u.id) roles
            from utilisateur u left join tenant t on t.id = u.tenant_id
            """;

    private final JdbcTemplate jdbc;
    private final UtilisateurRepository utilisateurRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrailService audit;

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ lecture

    /** Recherche par e-mail, nom, prénom ou cabinet ; filtre de statut : ACTIF, INACTIF (suspendu) ou VERROUILLE. */
    @Transactional(readOnly = true)
    public Page<UtilisateurAdmin> lister(UtilisateurPrincipal admin, String recherche, String statut, int page, int taille) {
        exigerAdmin(admin);
        int p = Math.max(0, page);
        int n = Math.min(100, Math.max(1, taille));
        List<Object> params = new ArrayList<>();
        String where = " where 1 = 1";
        String q = recherche == null ? "" : recherche.trim().toLowerCase();
        if (!q.isEmpty()) {
            String motif = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where += " and (lower(u.email) like ? or lower(u.nom) like ? or lower(u.prenom) like ? or lower(coalesce(t.raison_sociale, '')) like ?)";
            params.addAll(List.of(motif, motif, motif, motif));
        }
        if (statut != null && !statut.isBlank()) {
            where += switch (statut.trim().toUpperCase()) {
                case "ACTIF" -> " and u.statut = 'ACTIF' and not (u.locked_until is not null and u.locked_until > now())";
                case "INACTIF" -> " and u.statut <> 'ACTIF'";
                case "VERROUILLE" -> " and u.locked_until is not null and u.locked_until > now()";
                default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Statut inconnu : ACTIF, INACTIF ou VERROUILLE");
            };
        }
        Long total = jdbc.queryForObject("select count(*) from utilisateur u left join tenant t on t.id = u.tenant_id" + where, Long.class, params.toArray());
        List<Object> avecPage = new ArrayList<>(params);
        avecPage.add(n);
        avecPage.add((long) p * n);
        List<UtilisateurAdmin> elements = jdbc.query(SELECT + where + " order by u.created_at desc, u.id limit ? offset ?", (rs, i) -> versDto(rs), avecPage.toArray());
        return new Page<>(elements, total == null ? 0 : total, p, n);
    }

    private UtilisateurAdmin un(UUID id) {
        return jdbc.query(SELECT + " where u.id = ?", (rs, i) -> versDto(rs), id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
    }

    private static UtilisateurAdmin versDto(ResultSet rs) throws SQLException {
        OffsetDateTime verrou = date(rs.getTimestamp("locked_until"));
        List<String> roles = rs.getString("roles") == null ? List.of() : Arrays.asList(rs.getString("roles").split(","));
        return new UtilisateurAdmin(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("nom"), rs.getString("prenom"), rs.getString("statut"), rs.getObject("tenant_id", UUID.class),
                rs.getString("raison_sociale"), roles, date(rs.getTimestamp("created_at")), date(rs.getTimestamp("last_login_at")), verrou, verrou != null && verrou.isAfter(OffsetDateTime.now()),
                rs.getInt("failed_login_count"), rs.getBoolean("must_change_password"));
    }

    private static OffsetDateTime date(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ actions

    @Transactional
    public UtilisateurAdmin suspendre(UtilisateurPrincipal admin, UUID id, String motif) {
        Utilisateur u = cible(admin, id, motif);
        u.setStatut("INACTIF");
        u.setCredentialsVersion(u.getCredentialsVersion() + 1); // les sessions ouvertes sont coupées tout de suite
        utilisateurRepository.saveAndFlush(u);
        tracer(admin, u, "ADMIN_UTILISATEUR_SUSPENDU", motif);
        return un(id);
    }

    @Transactional
    public UtilisateurAdmin reactiver(UtilisateurPrincipal admin, UUID id, String motif) {
        Utilisateur u = cible(admin, id, motif);
        u.setStatut("ACTIF");
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        utilisateurRepository.saveAndFlush(u);
        tracer(admin, u, "ADMIN_UTILISATEUR_REACTIVE", motif);
        return un(id);
    }

    @Transactional
    public UtilisateurAdmin deverrouiller(UtilisateurPrincipal admin, UUID id, String motif) {
        Utilisateur u = cible(admin, id, motif);
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        utilisateurRepository.saveAndFlush(u);
        tracer(admin, u, "ADMIN_UTILISATEUR_DEVERROUILLE", motif);
        return un(id);
    }

    @Transactional
    public UtilisateurAdmin revoquerSessions(UtilisateurPrincipal admin, UUID id, String motif) {
        Utilisateur u = cible(admin, id, motif);
        u.setCredentialsVersion(u.getCredentialsVersion() + 1);
        utilisateurRepository.saveAndFlush(u);
        tracer(admin, u, "ADMIN_SESSIONS_REVOQUEES", motif);
        return un(id);
    }

    /** Nouveau mot de passe temporaire, renvoyé une seule fois : l'utilisateur devra en choisir un autre dès sa prochaine connexion, ses anciennes sessions sont coupées et le compte est déverrouillé. */
    @Transactional
    public ReinitialisationAdmin reinitialiserMotDePasse(UtilisateurPrincipal admin, UUID id, String motif) {
        Utilisateur u = cible(admin, id, motif);
        String temporaire = motDePasseTemporaire();
        u.setMotDePasse(passwordEncoder.encode(temporaire));
        u.setMustChangePassword(true);
        u.setCredentialsVersion(u.getCredentialsVersion() + 1);
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        utilisateurRepository.saveAndFlush(u);
        jdbc.update("update password_reset_token set used_at = now() where utilisateur_id = ? and used_at is null", u.getId()); // un éventuel lien de réinitialisation en attente ne vaut plus rien
        tracer(admin, u, "ADMIN_MOT_DE_PASSE_REINITIALISE", motif);
        return new ReinitialisationAdmin(un(id), temporaire);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ garde-fous

    static void exigerAdmin(UtilisateurPrincipal admin) {
        if (admin == null || admin.getAuthorities().stream().noneMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé à l'administrateur de la plateforme");
        }
    }

    private Utilisateur cible(UtilisateurPrincipal admin, UUID id, String motif) {
        exigerAdmin(admin);
        if (motif == null || motif.trim().length() < MOTIF_MIN) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un motif d'au moins " + MOTIF_MIN + " caractères est obligatoire");
        }
        Utilisateur u = utilisateurRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        if (u.getRoles().stream().anyMatch(r -> AdminAmorcage.ROLE.equals(r.getCode()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un compte administrateur ne se gère pas depuis cet écran");
        }
        return u;
    }

    private void tracer(UtilisateurPrincipal admin, Utilisateur cible, String action, String motif) {
        audit.enregistrerActionHumaine(cible.getTenant() == null ? null : cible.getTenant().getId(), admin.getId(), action, "UTILISATEUR", cible.getId().toString(),
                Map.of("motif", motif.trim(), "cible", cible.getEmail()));
    }

    /** 16 caractères sans ambiguïté visuelle (ni 0/O ni 1/l/I), avec au moins une majuscule, une minuscule et un chiffre. */
    static String motDePasseTemporaire() {
        String tous = MAJUSCULES + MINUSCULES + CHIFFRES;
        List<Character> caracteres = new ArrayList<>();
        caracteres.add(MAJUSCULES.charAt(ALEA.nextInt(MAJUSCULES.length())));
        caracteres.add(MINUSCULES.charAt(ALEA.nextInt(MINUSCULES.length())));
        caracteres.add(CHIFFRES.charAt(ALEA.nextInt(CHIFFRES.length())));
        while (caracteres.size() < LONGUEUR_TEMPORAIRE) {
            caracteres.add(tous.charAt(ALEA.nextInt(tous.length())));
        }
        Collections.shuffle(caracteres, ALEA);
        StringBuilder s = new StringBuilder();
        caracteres.forEach(s::append);
        return s.toString();
    }
}
