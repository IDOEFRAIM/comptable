package com.example.comptable.admin;

import com.example.comptable.admin.AdminDtos.AbonnementRequest;
import com.example.comptable.admin.AdminDtos.CabinetAdmin;
import com.example.comptable.admin.AdminDtos.CreationCabinetAdmin;
import com.example.comptable.admin.AdminDtos.CreerCabinetRequest;
import com.example.comptable.admin.AdminDtos.Page;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.identite.service.AuthService;
import com.example.comptable.identite.service.QuotaCabinetService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Régulation des cabinets par l'administrateur de la plateforme : abonnement (plan, fin d'essai, quotas), suspension et création. Même discipline que pour les comptes : motif obligatoire pour toute
 * action qui change l'état d'un cabinet, tout est tracé dans {@code audit_log}. La suspension coupe l'accès de tous les comptes du cabinet (voir JwtAuthFilter et AuthService) sans toucher à leurs données.
 */
@Service
@RequiredArgsConstructor
public class AdminCabinetService {

    public static final Set<String> PLANS = Set.of("ESSAI", "STANDARD", "PARTENAIRE");
    private static final int MOTIF_MIN = 5;
    private static final int ESSAI_PAR_DEFAUT_JOURS = 30;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String SELECT = """
            select t.id, t.raison_sociale, t.slug, t.pays, t.plan_abonnement, t.statut, t.fin_essai, t.quota_utilisateurs, t.quota_dossiers, t.created_at, t.suspendu_motif, t.suspendu_le,
                   (%s) membres_actifs,
                   (select count(*) from utilisateur u where u.tenant_id = t.id) utilisateurs_total,
                   (select count(*) from entreprise e where e.tenant_id = t.id and e.statut = 'ACTIF') dossiers_actifs,
                   (select max(a.created_at) from audit_log a where a.tenant_id = t.id) derniere_activite
            from tenant t
            """.formatted(QuotaCabinetService.MEMBRES_ACTIFS.replace("?", "t.id"));

    private final JdbcTemplate jdbc;
    private final TenantRepository tenantRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditTrailService audit;

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ lecture

    /** Recherche par nom ou identifiant court ; statut : ACTIF, SUSPENDU ou ESSAI_EXPIRE. */
    @Transactional(readOnly = true)
    public Page<CabinetAdmin> lister(UtilisateurPrincipal admin, String recherche, String statut, int page, int taille) {
        AdminUtilisateurService.exigerAdmin(admin);
        int p = Math.max(0, page);
        int n = Math.min(100, Math.max(1, taille));
        List<Object> params = new ArrayList<>();
        String where = " where 1 = 1";
        String q = recherche == null ? "" : recherche.trim().toLowerCase();
        if (!q.isEmpty()) {
            String motif = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where += " and (lower(t.raison_sociale) like ? or lower(t.slug) like ?)";
            params.addAll(List.of(motif, motif));
        }
        if (statut != null && !statut.isBlank()) {
            where += switch (statut.trim().toUpperCase()) {
                case "ACTIF" -> " and t.statut = 'ACTIF'";
                case "SUSPENDU" -> " and t.statut <> 'ACTIF'";
                case "ESSAI_EXPIRE" -> " and t.plan_abonnement = 'ESSAI' and t.fin_essai is not null and t.fin_essai < current_date";
                default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Statut inconnu : ACTIF, SUSPENDU ou ESSAI_EXPIRE");
            };
        }
        Long total = jdbc.queryForObject("select count(*) from tenant t" + where, Long.class, params.toArray());
        List<Object> avecPage = new ArrayList<>(params);
        avecPage.add(n);
        avecPage.add((long) p * n);
        List<CabinetAdmin> elements = jdbc.query(SELECT + where + " order by t.created_at desc, t.id limit ? offset ?", (rs, i) -> versDto(rs), avecPage.toArray());
        return new Page<>(elements, total == null ? 0 : total, p, n);
    }

    private CabinetAdmin un(UUID id) {
        return jdbc.query(SELECT + " where t.id = ?", (rs, i) -> versDto(rs), id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cabinet introuvable"));
    }

    private static CabinetAdmin versDto(ResultSet rs) throws SQLException {
        LocalDate finEssai = rs.getObject("fin_essai", LocalDate.class);
        String plan = rs.getString("plan_abonnement");
        return new CabinetAdmin(rs.getObject("id", UUID.class), rs.getString("raison_sociale"), rs.getString("slug"), rs.getString("pays"), plan, rs.getString("statut"), finEssai,
                "ESSAI".equals(plan) && finEssai != null && finEssai.isBefore(LocalDate.now()), rs.getObject("quota_utilisateurs", Integer.class), rs.getObject("quota_dossiers", Integer.class),
                rs.getLong("membres_actifs"), rs.getLong("utilisateurs_total"), rs.getLong("dossiers_actifs"), date(rs.getTimestamp("created_at")), date(rs.getTimestamp("derniere_activite")),
                rs.getString("suspendu_motif"), date(rs.getTimestamp("suspendu_le")));
    }

    private static OffsetDateTime date(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ actions

    @Transactional
    public CabinetAdmin suspendre(UtilisateurPrincipal admin, UUID id, String motif) {
        Tenant t = cible(admin, id, motif);
        t.setStatut("SUSPENDU");
        t.setSuspenduMotif(motif.trim());
        t.setSuspenduLe(OffsetDateTime.now());
        tenantRepository.saveAndFlush(t);
        tracer(admin, t, "ADMIN_CABINET_SUSPENDU", Map.of("motif", motif.trim()));
        return un(id);
    }

    @Transactional
    public CabinetAdmin reactiver(UtilisateurPrincipal admin, UUID id, String motif) {
        Tenant t = cible(admin, id, motif);
        t.setStatut("ACTIF");
        t.setSuspenduMotif(null);
        t.setSuspenduLe(null);
        tenantRepository.saveAndFlush(t);
        tracer(admin, t, "ADMIN_CABINET_REACTIVE", Map.of("motif", motif.trim()));
        return un(id);
    }

    /** Plan, fin d'essai et quotas d'un cabinet. Les trois derniers à null = pas de limite / pas de date. Ne coupe jamais l'accès : un quota abaissé sous l'existant bloque seulement les ajouts suivants. */
    @Transactional
    public CabinetAdmin modifierAbonnement(UtilisateurPrincipal admin, UUID id, AbonnementRequest req) {
        Tenant t = cible(admin, id, req.motif());
        String plan = planValide(req.plan());
        quotaValide(req.quotaUtilisateurs(), "utilisateurs");
        quotaValide(req.quotaDossiers(), "dossiers");
        Map<String, Object> avant = new HashMap<>();
        avant.put("plan", t.getPlanAbonnement());
        avant.put("finEssai", String.valueOf(t.getFinEssai()));
        avant.put("quotaUtilisateurs", String.valueOf(t.getQuotaUtilisateurs()));
        avant.put("quotaDossiers", String.valueOf(t.getQuotaDossiers()));
        t.setPlanAbonnement(plan);
        t.setFinEssai(req.finEssai());
        t.setQuotaUtilisateurs(req.quotaUtilisateurs());
        t.setQuotaDossiers(req.quotaDossiers());
        tenantRepository.saveAndFlush(t);
        Map<String, Object> details = new HashMap<>();
        details.put("motif", req.motif().trim());
        details.put("avant", avant);
        details.put("apres", Map.of("plan", plan, "finEssai", String.valueOf(req.finEssai()), "quotaUtilisateurs", String.valueOf(req.quotaUtilisateurs()), "quotaDossiers", String.valueOf(req.quotaDossiers())));
        tracer(admin, t, "ADMIN_ABONNEMENT_MODIFIE", details);
        return un(id);
    }

    /** Crée un cabinet et son premier expert-comptable (mot de passe temporaire renvoyé une seule fois) : remplace le script d'approvisionnement en ligne de commande. */
    @Transactional
    public CreationCabinetAdmin creer(UtilisateurPrincipal admin, CreerCabinetRequest req) {
        AdminUtilisateurService.exigerAdmin(admin);
        if (vide(req.raisonSociale()) || vide(req.nom()) || vide(req.prenom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le nom du cabinet, le nom et le prénom de l'expert sont obligatoires");
        }
        if (req.pays() == null || !req.pays().trim().matches("[A-Za-z]{2}")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le pays s'indique par son code à deux lettres (ex. BF, CI, SN)");
        }
        String email = req.emailExpert() == null ? "" : req.emailExpert().trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Adresse e-mail de l'expert invalide");
        }
        if (utilisateurRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette adresse e-mail est déjà utilisée");
        }
        String plan = planValide(req.plan() == null || req.plan().isBlank() ? "ESSAI" : req.plan());
        quotaValide(req.quotaUtilisateurs(), "utilisateurs");
        quotaValide(req.quotaDossiers(), "dossiers");

        Tenant t = new Tenant();
        t.setRaisonSociale(req.raisonSociale().trim());
        t.setSlug(authService.genererSlugUnique(req.raisonSociale().trim()));
        t.setPays(req.pays().trim().toUpperCase());
        t.setPlanAbonnement(plan);
        t.setFinEssai(req.finEssai() != null ? req.finEssai() : "ESSAI".equals(plan) ? LocalDate.now().plusDays(ESSAI_PAR_DEFAUT_JOURS) : null);
        t.setQuotaUtilisateurs(req.quotaUtilisateurs());
        t.setQuotaDossiers(req.quotaDossiers());
        t = tenantRepository.saveAndFlush(t);

        String temporaire = AdminUtilisateurService.motDePasseTemporaire();
        Utilisateur expert = new Utilisateur();
        expert.setTenant(t);
        expert.setEmail(email);
        expert.setMotDePasse(passwordEncoder.encode(temporaire));
        expert.setNom(req.nom().trim());
        expert.setPrenom(req.prenom().trim());
        expert.setTelephone(req.telephone() == null || req.telephone().isBlank() ? null : req.telephone().trim());
        expert.setRoles(Set.of(roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow(() -> new IllegalStateException("Rôle EXPERT_COMPTABLE introuvable, seed manquant"))));
        expert.setMustChangePassword(true);
        utilisateurRepository.saveAndFlush(expert);

        tracer(admin, t, "ADMIN_CABINET_CREE", Map.of("expert", email, "plan", plan));
        return new CreationCabinetAdmin(un(t.getId()), email, temporaire);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ garde-fous

    private Tenant cible(UtilisateurPrincipal admin, UUID id, String motif) {
        AdminUtilisateurService.exigerAdmin(admin);
        if (motif == null || motif.trim().length() < MOTIF_MIN) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un motif d'au moins " + MOTIF_MIN + " caractères est obligatoire");
        }
        return tenantRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cabinet introuvable"));
    }

    private static String planValide(String plan) {
        String p = plan == null ? "" : plan.trim().toUpperCase();
        if (!PLANS.contains(p)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Plan inconnu : ESSAI, STANDARD ou PARTENAIRE");
        }
        return p;
    }

    private static void quotaValide(Integer quota, String nom) {
        if (quota != null && quota < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La limite de " + nom + " doit être d'au moins 1 (laissez vide pour ne pas limiter)");
        }
    }

    private static boolean vide(String s) {
        return s == null || s.isBlank();
    }

    private void tracer(UtilisateurPrincipal admin, Tenant t, String action, Map<String, Object> details) {
        Map<String, Object> avecNom = new HashMap<>(details);
        avecNom.put("cabinet", t.getRaisonSociale());
        audit.enregistrerActionHumaine(t.getId(), admin.getId(), action, "CABINET", t.getId().toString(), avecNom);
    }
}
