package com.example.comptable.cabinet.workspace;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.etatsfinanciers.dossier.DossierRoles;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Équipe du cabinet : les membres (collaborateurs et experts-comptables) du tenant de l'utilisateur connecté. Aucun nouveau modèle : un membre est un {@code Utilisateur} du tenant porteur d'un des deux
 * rôles de cabinet ; « désactivé » = {@code statut != ACTIF} (le principal est alors refusé à la connexion). La lecture est ouverte aux membres du cabinet ; ajout, changement de rôle et
 * (dés)activation sont réservés à l'expert-comptable. Un membre d'un autre cabinet n'existe pas pour l'appelant (404).
 */
@Service
public class CabinetEquipeService {

    public static final Set<String> ROLES = Set.of("COLLABORATEUR", "EXPERT_COMPTABLE");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LONGUEUR_MOT_DE_PASSE = 12;

    public record MembreDto(UUID id, String nom, String prenom, String email, String role, String statut, long dossiersAffectes, OffsetDateTime derniereActivite) {
    }

    public record MembreRequest(String email, String nom, String prenom, String role, String motDePasse) {
    }

    /** {@code motDePasseTemporaire} n'est renvoyé qu'à la création, une seule fois, quand aucun mot de passe n'a été fourni (aucune infrastructure d'e-mail n'existe encore). */
    public record MembreCreeDto(MembreDto membre, String motDePasseTemporaire) {
    }

    public record MembrePatch(String role, String statut) {
    }

    private final UtilisateurRepository utilisateurRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrailService audit;
    private final JdbcTemplate jdbc;
    private final SecureRandom aleatoire = new SecureRandom();

    public CabinetEquipeService(UtilisateurRepository utilisateurRepository, RoleRepository roleRepository, TenantRepository tenantRepository, PasswordEncoder passwordEncoder,
                                AuditTrailService audit, JdbcTemplate jdbc) {
        this.utilisateurRepository = utilisateurRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** Garde de rôle de la réinitialisation de mot de passe (réservée à l'expert-comptable du cabinet). */
    public void exigerExpertPourReinitialisation(UtilisateurPrincipal principal) {
        exigerExpert(principal);
    }

    @Transactional(readOnly = true)
    public List<MembreDto> equipe(UtilisateurPrincipal principal) {
        exigerCabinet(principal);
        return membres(principal.getTenantId(), null);
    }

    @Transactional
    public MembreCreeDto ajouter(UtilisateurPrincipal principal, MembreRequest req) {
        exigerExpert(principal);
        UUID tenant = principal.getTenantId();
        String role = req.role() == null || req.role().isBlank() ? "COLLABORATEUR" : req.role().trim().toUpperCase();
        if (!ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Rôle inconnu : seuls COLLABORATEUR et EXPERT_COMPTABLE sont possibles");
        }
        String email = req.email() == null ? "" : req.email().trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Adresse e-mail invalide");
        }
        if (vide(req.nom()) || vide(req.prenom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le nom et le prénom sont obligatoires");
        }
        if (req.motDePasse() != null && !req.motDePasse().isBlank() && req.motDePasse().length() < 8) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le mot de passe doit contenir au moins 8 caractères");
        }
        if (utilisateurRepository.existsByEmail(email) || utilisateurRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette adresse e-mail est déjà utilisée");
        }
        boolean temporaire = req.motDePasse() == null || req.motDePasse().isBlank();
        String motDePasse = temporaire ? motDePasseTemporaire() : req.motDePasse();
        Role entiteRole = roleRepository.findByCode(role).orElseThrow(() -> new IllegalStateException("Rôle " + role + " introuvable, seed manquant"));
        Utilisateur u = new Utilisateur();
        u.setTenant(tenantRepository.getReferenceById(tenant));
        u.setEmail(email);
        u.setMotDePasse(passwordEncoder.encode(motDePasse));
        u.setNom(req.nom().trim());
        u.setPrenom(req.prenom().trim());
        u.setRoles(new java.util.HashSet<>(Set.of(entiteRole)));
        u.setMustChangePassword(temporaire);                                   // un mot de passe temporaire ne sert qu'à la première connexion
        utilisateurRepository.save(u);
        audit.enregistrerActionHumaine(tenant, principal.getId(), "MEMBRE_CABINET_AJOUTE", "UTILISATEUR", u.getId().toString(), Map.of("role", role));
        return new MembreCreeDto(membres(tenant, u.getId()).get(0), temporaire ? motDePasse : null);
    }

    /** Change le rôle et/ou active / désactive un membre. Garde-fous : jamais soi-même, jamais le dernier expert actif. */
    @Transactional
    public MembreDto modifier(UtilisateurPrincipal principal, UUID membreId, MembrePatch patch) {
        exigerExpert(principal);
        UUID tenant = principal.getTenantId();
        Utilisateur u = utilisateurRepository.findById(membreId).filter(x -> x.getTenant() != null && tenant.equals(x.getTenant().getId())
                && x.getRoles().stream().anyMatch(r -> ROLES.contains(r.getCode()))).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membre introuvable"));
        String ancienRole = roleDe(u);
        boolean etaitActif = "ACTIF".equals(u.getStatut());
        boolean soiMeme = u.getId().equals(principal.getId());

        if (patch.role() != null && !patch.role().isBlank() && !patch.role().equals(ancienRole)) {
            String role = patch.role().trim().toUpperCase();
            if (!ROLES.contains(role)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Rôle inconnu : seuls COLLABORATEUR et EXPERT_COMPTABLE sont possibles");
            }
            if (soiMeme) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Vous ne pouvez pas modifier votre propre rôle");
            }
            if ("EXPERT_COMPTABLE".equals(ancienRole) && etaitActif && expertsActifs(tenant) <= 1) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le cabinet doit conserver au moins un expert-comptable actif");
            }
            u.setRoles(new java.util.HashSet<>(Set.of(roleRepository.findByCode(role).orElseThrow())));
            audit.enregistrerActionHumaine(tenant, principal.getId(), "ROLE_MEMBRE_MODIFIE", "UTILISATEUR", u.getId().toString(), Map.of("avant", ancienRole, "apres", role));
        }
        if (patch.statut() != null && !patch.statut().isBlank()) {
            String statut = patch.statut().trim().toUpperCase();
            if (!Set.of("ACTIF", "INACTIF").contains(statut)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Statut inconnu : ACTIF ou INACTIF");
            }
            boolean veutActif = "ACTIF".equals(statut);
            if (veutActif != etaitActif) {
                if (!veutActif && soiMeme) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Vous ne pouvez pas désactiver votre propre compte");
                }
                if (!veutActif && "EXPERT_COMPTABLE".equals(roleDe(u)) && expertsActifs(tenant) <= 1) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le cabinet doit conserver au moins un expert-comptable actif");
                }
                u.setStatut(statut);
                audit.enregistrerActionHumaine(tenant, principal.getId(), veutActif ? "MEMBRE_CABINET_REACTIVE" : "MEMBRE_CABINET_DESACTIVE", "UTILISATEUR", u.getId().toString(), Map.of());
            }
        }
        utilisateurRepository.save(u);
        return membres(tenant, u.getId()).get(0);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ lecture

    /** Un seul passage SQL pour toute l'équipe : rôle, statut, dossiers affectés (collaborateur ou expert) et dernière action tracée. */
    private List<MembreDto> membres(UUID tenantId, UUID seulement) {
        Map<UUID, Long> affectes = new HashMap<>();
        jdbc.query("""
                select u, count(*) n from (
                    select a.collaborateur_id u from dossier_affectation a where a.tenant_id = ? and a.collaborateur_id is not null
                    union all
                    select a.expert_id u from dossier_affectation a where a.tenant_id = ? and a.expert_id is not null) t group by u
                """, rs -> {
            affectes.put(rs.getObject("u", UUID.class), rs.getLong("n"));
        }, tenantId, tenantId);
        Map<UUID, OffsetDateTime> activite = new HashMap<>();
        jdbc.query("select utilisateur_id, max(created_at) derniere from audit_log where tenant_id = ? and utilisateur_id is not null group by utilisateur_id", rs -> {
            java.sql.Timestamp ts = rs.getTimestamp("derniere");
            activite.put(rs.getObject("utilisateur_id", UUID.class), ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC));
        }, tenantId);
        return utilisateurRepository.findByTenantIdAndRoleCodeIn(tenantId, List.copyOf(ROLES)).stream()
                .filter(u -> seulement == null || seulement.equals(u.getId()))
                .map(u -> new MembreDto(u.getId(), u.getNom(), u.getPrenom(), u.getEmail(), roleDe(u), u.getStatut(), affectes.getOrDefault(u.getId(), 0L), activite.get(u.getId())))
                .toList();
    }

    private long expertsActifs(UUID tenantId) {
        return utilisateurRepository.findByTenantIdAndRoleCodeIn(tenantId, List.of("EXPERT_COMPTABLE")).stream().filter(x -> "ACTIF".equals(x.getStatut())).count();
    }

    private static String roleDe(Utilisateur u) {
        return u.getRoles().stream().anyMatch(r -> "EXPERT_COMPTABLE".equals(r.getCode())) ? "EXPERT_COMPTABLE" : "COLLABORATEUR";
    }

    private String motDePasseTemporaire() {
        StringBuilder s = new StringBuilder(LONGUEUR_MOT_DE_PASSE);
        for (int i = 0; i < LONGUEUR_MOT_DE_PASSE; i++) {
            s.append(ALPHABET.charAt(aleatoire.nextInt(ALPHABET.length())));
        }
        return s.toString();
    }

    private static boolean vide(String s) {
        return s == null || s.isBlank();
    }

    private void exigerCabinet(UtilisateurPrincipal principal) {
        if (!DossierRoles.peutPreparer(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'équipe du cabinet est réservée aux collaborateurs et experts-comptables");
        }
    }

    private void exigerExpert(UtilisateurPrincipal principal) {
        if (!DossierRoles.peutRevoir(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La gestion de l'équipe est réservée à l'expert-comptable");
        }
    }
}
