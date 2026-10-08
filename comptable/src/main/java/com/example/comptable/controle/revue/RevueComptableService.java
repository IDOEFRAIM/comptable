package com.example.comptable.controle.revue;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.repository.FindingRepository.AgregatRevue;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlReconciler;
import com.example.comptable.controle.ControlRunRepository;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Revue comptable (Phase B, lot B3) : reponse a « que doit faire le comptable maintenant ? ». Agrege
 * les Findings actifs d'une entreprise - jamais une nouvelle table, jamais un recalcul complet a
 * chaque lecture (la derniere execution des controles est reutilisee tant qu'elle est recente).
 * Une ligne de la liste = une decision humaine possible ; les tuiles sont des filtres cliquables.
 */
@Service
public class RevueComptableService {

    /** Tuiles de la revue. Les tuiles de gravite (BLOQUANTS, ERREURS) croisent les tuiles par nature : une meme ligne peut compter dans deux tuiles. */
    public enum Tuile {
        BLOQUANTS("Bloquants"),
        ERREURS("Erreurs"),
        ANOMALIES("Anomalies"),
        PIECES_MANQUANTES("Pieces manquantes"),
        IMPUTATIONS("Imputations"),
        RAPPROCHEMENTS("Rapprochements");

        private final String libelle;

        Tuile(String libelle) {
            this.libelle = libelle;
        }

        public String libelle() {
            return libelle;
        }

        boolean contient(ControlSeverity severite, ControlCategory categorie, String code) {
            return switch (this) {
                case BLOQUANTS -> severite == ControlSeverity.BLOCKING;
                case ERREURS -> severite == ControlSeverity.ERROR;
                case ANOMALIES -> Set.of(ControlCategory.ANOMALY, ControlCategory.THIRD_PARTY, ControlCategory.STOCK,
                        ControlCategory.FIXED_ASSET).contains(categorie);
                case PIECES_MANQUANTES -> ControlCodes.MISSING_DOCUMENT.equals(code);
                case IMPUTATIONS -> CODES_IMPUTATION.contains(code);
                case RAPPROCHEMENTS -> categorie == ControlCategory.RECONCILIATION;
            };
        }
    }

    static final Set<String> CODES_IMPUTATION = Set.of(ControlCodes.ACCOUNT_NOT_CONFIGURED, ControlCodes.ACCOUNT_AMBIGUOUS,
            ControlCodes.ACCOUNT_INVALID);
    static final int TAILLE_MAX = 100;

    /** Filtres de la liste (tous optionnels). {@code du}/{@code au} bornent la date de creation du constat. */
    public record Filtre(Tuile tuile, ControlSeverity severite, ControlCategory categorie, StatutFinding statut,
                         String entityType, String code, LocalDate du, LocalDate au, String q, com.example.comptable.controle.ControlDomain domaine) {
        public Filtre(Tuile tuile, ControlSeverity severite, ControlCategory categorie, StatutFinding statut,
                      String entityType, String code, LocalDate du, LocalDate au, String q) {
            this(tuile, severite, categorie, statut, entityType, code, du, au, q, null);
        }
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final FindingRepository findingRepository;
    private final ControlRunService controlRunService;
    private final ControlRunRepository runRepository;
    private final com.example.comptable.audit.AuditTrailService auditTrailService;
    private final com.example.comptable.dossier.ClotureReadinessService clotureReadinessService;

    public RevueComptableService(EntrepriseAccessService entrepriseAccessService, FindingRepository findingRepository,
                                 ControlRunService controlRunService, ControlRunRepository runRepository,
                                 com.example.comptable.audit.AuditTrailService auditTrailService,
                                 com.example.comptable.dossier.ClotureReadinessService clotureReadinessService) {
        this.auditTrailService = auditTrailService;
        this.clotureReadinessService = clotureReadinessService;
        this.entrepriseAccessService = entrepriseAccessService;
        this.findingRepository = findingRepository;
        this.controlRunService = controlRunService;
        this.runRepository = runRepository;
    }

    @Transactional
    public RevueDto.Synthese synthese(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        // Une revue qui relance les controles (derniere execution perimee) est une nouvelle passe de revision : elle est tracee.
        if (controlRunService.executerSiPerime(entreprise)) {
            auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "REVUE_OUVERTE", "ENTREPRISE", entrepriseId.toString(), java.util.Map.of());
        }

        List<AgregatRevue> agregats = findingRepository.agregerActifs(entrepriseId);
        Map<Tuile, Long> parTuile = new EnumMap<>(Tuile.class);
        Map<String, Long> parSeverite = new LinkedHashMap<>();
        Map<String, Long> parCategorie = new LinkedHashMap<>();
        long total = 0;
        for (AgregatRevue a : agregats) {
            total += a.nombre();
            parSeverite.merge(a.severite().name(), a.nombre(), Long::sum);
            parCategorie.merge(a.categorie().name(), a.nombre(), Long::sum);
            for (Tuile tuile : Tuile.values()) {
                if (tuile.contient(a.severite(), a.categorie(), a.code())) {
                    parTuile.merge(tuile, a.nombre(), Long::sum);
                }
            }
        }
        List<RevueDto.TuileDto> tuiles = new ArrayList<>();
        for (Tuile tuile : Tuile.values()) {
            tuiles.add(new RevueDto.TuileDto(tuile.name(), tuile.libelle(), parTuile.getOrDefault(tuile, 0L)));
        }
        var derniere = runRepository.findFirstByEntrepriseIdOrderByStartedAtDesc(entrepriseId);
        return new RevueDto.Synthese(total, parSeverite.getOrDefault(ControlSeverity.BLOCKING.name(), 0L) > 0, tuiles,
                parSeverite, parCategorie, derniere.map(r -> r.getStartedAt()).orElse(null));
    }

    /** Espace de revision : synthese par domaine + etat de preparation a la cloture. Relance les controles s'ils sont perimes. */
    @Transactional
    public RevueDto.Espace espace(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (controlRunService.executerSiPerime(entreprise)) {
            auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "REVUE_OUVERTE", "ENTREPRISE", entrepriseId.toString(), java.util.Map.of());
        }
        Map<com.example.comptable.controle.ControlDomain, long[]> parDomaine = new java.util.EnumMap<>(com.example.comptable.controle.ControlDomain.class);
        long bloquants = 0;
        long avertissements = 0;
        long aExaminer = 0;
        for (Finding f : findingRepository.actifsDe(entrepriseId)) {
            RevueDto.Constat c = RevueDto.Constat.from(f);
            long[] cases = parDomaine.computeIfAbsent(com.example.comptable.controle.ControlDomain.valueOf(c.domaine()), k -> new long[3]);
            switch (f.getControlSeverity()) {
                case BLOCKING -> {
                    cases[0]++;
                    bloquants++;
                }
                case ERROR, WARNING -> {
                    cases[1]++;
                    avertissements++;
                }
                default -> {
                    cases[2]++;
                    aExaminer++;
                }
            }
        }
        List<RevueDto.DomaineRevue> domaines = new ArrayList<>();
        for (com.example.comptable.controle.ControlDomain d : com.example.comptable.controle.ControlDomain.values()) {
            long[] c = parDomaine.get(d);
            if (c != null) {
                domaines.add(new RevueDto.DomaineRevue(d.name(), d.libelle(), c[0], c[1], c[2], c[0] + c[1] + c[2]));
            }
        }
        var debutDuJour = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        var preparation = clotureReadinessService.construireChecklist(entrepriseId);
        var derniere = runRepository.findFirstByEntrepriseIdOrderByStartedAtDesc(entrepriseId);
        return new RevueDto.Espace("READY".equals(preparation.statut()), preparation.statut(), bloquants, avertissements, aExaminer,
                findingRepository.compterResolusDepuis(entrepriseId, debutDuJour), domaines, derniere.map(r -> r.getStartedAt()).orElse(null));
    }

    @Transactional(readOnly = true)
    public RevueDto.Page lister(UUID entrepriseId, Filtre filtre, int page, int taille, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        int tailleBornee = Math.max(1, Math.min(TAILLE_MAX, taille));
        Page<Finding> resultat = findingRepository.findAll(specification(entrepriseId, filtre),
                PageRequest.of(Math.max(0, page), tailleBornee));
        return new RevueDto.Page(resultat.getContent().stream().map(RevueDto.Constat::from).toList(),
                resultat.getNumber(), resultat.getSize(), resultat.getTotalElements(), resultat.getTotalPages());
    }

    @Transactional(readOnly = true)
    public RevueDto.Constat obtenir(UUID entrepriseId, UUID findingId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return findingRepository.findById(findingId)
                .filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                .map(RevueDto.Constat::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Constat introuvable"));
    }

    /** Relance les controles (hors fraicheur) : le comptable vient d'agir et veut voir le dossier a jour. */
    @Transactional
    public RevueDto.Execution relancer(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ControlReconciler.Bilan bilan = controlRunService.executer(entreprise);
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CONTROLES_EXECUTES", "ENTREPRISE", entrepriseId.toString(),
                java.util.Map.of("detectes", bilan.detectes(), "crees", bilan.crees(), "resolus", bilan.resolus()));
        return new RevueDto.Execution(bilan.detectes(), bilan.crees(), bilan.misAJour(), bilan.resolus());
    }

    static Specification<Finding> specification(UUID entrepriseId, Filtre f) {
        return (root, query, cb) -> {
            List<Predicate> predicats = new ArrayList<>();
            predicats.add(cb.equal(root.get("entreprise").get("id"), entrepriseId));
            if (f.statut() != null) {
                predicats.add(cb.equal(root.get("status"), f.statut()));
            } else {
                predicats.add(root.get("status").in(StatutFinding.OPEN, StatutFinding.IN_PROGRESS));
            }
            if (f.tuile() != null) {
                switch (f.tuile()) {
                    case BLOQUANTS -> predicats.add(cb.equal(root.get("controlSeverity"), ControlSeverity.BLOCKING));
                    case ERREURS -> predicats.add(cb.equal(root.get("controlSeverity"), ControlSeverity.ERROR));
                    case ANOMALIES -> predicats.add(root.get("category").in(ControlCategory.ANOMALY, ControlCategory.THIRD_PARTY,
                            ControlCategory.STOCK, ControlCategory.FIXED_ASSET));
                    case PIECES_MANQUANTES -> predicats.add(cb.equal(root.get("controlCode"), ControlCodes.MISSING_DOCUMENT));
                    case IMPUTATIONS -> predicats.add(root.get("controlCode").in(CODES_IMPUTATION));
                    case RAPPROCHEMENTS -> predicats.add(cb.equal(root.get("category"), ControlCategory.RECONCILIATION));
                }
            }
            if (f.domaine() != null) {
                predicats.add(cb.equal(root.get("domaine"), f.domaine().name()));
            }
            if (f.severite() != null) {
                predicats.add(cb.equal(root.get("controlSeverity"), f.severite()));
            }
            if (f.categorie() != null) {
                predicats.add(cb.equal(root.get("category"), f.categorie()));
            }
            if (f.entityType() != null && !f.entityType().isBlank()) {
                predicats.add(cb.equal(root.get("sourceType"), f.entityType()));
            }
            if (f.code() != null && !f.code().isBlank()) {
                predicats.add(cb.equal(root.get("controlCode"), f.code()));
            }
            if (f.du() != null) {
                predicats.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.du().atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            if (f.au() != null) {
                predicats.add(cb.lessThan(root.get("createdAt"), f.au().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String motif = "%" + f.q().trim().toLowerCase() + "%";
                predicats.add(cb.or(
                        cb.like(cb.lower(root.get("title")), motif),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("description"), "")), motif),
                        cb.like(cb.lower(root.get("sourceId")), motif),
                        cb.like(cb.lower(root.get("controlCode")), motif)));
            }
            // Tri : gravite decroissante (BLOCKING > ERROR > WARNING > INFO), puis detection la plus recente. Pas de tri sur la requete de comptage.
            if (Long.class != query.getResultType()) {
                Expression<Integer> rang = cb.<Integer>selectCase()
                        .when(cb.equal(root.get("controlSeverity"), ControlSeverity.BLOCKING), 0)
                        .when(cb.equal(root.get("controlSeverity"), ControlSeverity.ERROR), 1)
                        .when(cb.equal(root.get("controlSeverity"), ControlSeverity.WARNING), 2)
                        .otherwise(3);
                query.orderBy(cb.asc(rang), cb.desc(root.get("lastDetectedAt")), cb.asc(root.get("id")));
            }
            return cb.and(predicats.toArray(new Predicate[0]));
        };
    }
}
