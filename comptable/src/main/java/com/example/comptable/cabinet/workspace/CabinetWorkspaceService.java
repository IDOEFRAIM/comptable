package com.example.comptable.cabinet.workspace;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.*;
import com.example.comptable.cabinet.workspace.DossierOperationalStatusEvaluator.Evaluation;
import com.example.comptable.cabinet.workspace.DossierSignalsLoader.DossierCharge;
import com.example.comptable.cabinet.workspace.DossierSignalsLoader.DossierRow;
import com.example.comptable.cabinet.workspace.WorkQueueLoader.WorkRow;
import com.example.comptable.etatsfinanciers.dossier.DossierRoles;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cabinet Workspace : agrège les modules existants (constats, demandes de pièces, banque, notes, clôture, snapshot) en un portefeuille de dossiers (entreprise + exercice) sans dupliquer leurs règles.
 * Le statut, la progression et la priorité viennent de {@link DossierOperationalStatusEvaluator} ; le nombre de requêtes SQL reste constant quel que soit le nombre de dossiers
 * ({@link DossierSignalsLoader}). Le tri, les filtres et la pagination s'appliquent ici, côté serveur, sur le portefeuille évalué : Angular ne reçoit jamais plus d'une page.
 */
@Service
public class CabinetWorkspaceService {

    public enum Filtre { MES_DOSSIERS, TOUS, A_TRAITER, EN_ATTENTE_CLIENT, PRETS_REVUE, PRETS_CLOTURE, FINALISES }

    public enum Tri { PRIORITE, ENTREPRISE, ACTIVITE, ECHEANCE }

    public record DossierQuery(Filtre filtre, String q, UUID collaborateurId, UUID expertId, Integer exercice, StatutOperationnel statut, Tri tri, boolean decroissant, int page, int taille) {
    }

    private static final int TAILLE_MAX = 100;
    private static final int JOURS_ECHEANCE_PROCHE = 7;
    private static final Set<String> CODES_ETATS_FINANCIERS = Set.of("ETAT_FINANCIER");

    private final DossierSignalsLoader loader;
    private final WorkQueueLoader workQueueLoader;
    private final DossierOperationalStatusEvaluator evaluator;
    private final DossierEcheanceRepository echeanceRepository;
    private final DossierAffectationRepository affectationRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final AuditTrailService audit;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    public CabinetWorkspaceService(DossierSignalsLoader loader, WorkQueueLoader workQueueLoader, DossierOperationalStatusEvaluator evaluator, DossierEcheanceRepository echeanceRepository,
                                   DossierAffectationRepository affectationRepository, UtilisateurRepository utilisateurRepository, AuditTrailService audit, JdbcTemplate jdbc, java.time.Clock clock) {
        this.clock = clock;
        this.loader = loader;
        this.workQueueLoader = workQueueLoader;
        this.evaluator = evaluator;
        this.echeanceRepository = echeanceRepository;
        this.affectationRepository = affectationRepository;
        this.utilisateurRepository = utilisateurRepository;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ portefeuille

    private record Vue(DossierSummaryDto dto, LocalDate prochaineEcheance) {
    }

    @Transactional(readOnly = true)
    List<DossierSummaryDto> portefeuille(UUID tenantId) {
        return vues(tenantId).stream().map(Vue::dto).toList();
    }

    private List<Vue> vues(UUID tenantId) {
        List<DossierCharge> charges = loader.charger(tenantId);
        Map<String, List<DossierEcheance>> echeances = new HashMap<>();
        Map<UUID, UUID> courant = new HashMap<>();
        charges.stream().filter(c -> c.dossier().courant()).forEach(c -> courant.put(c.dossier().entrepriseId(), c.dossier().exerciceId()));
        for (DossierEcheance e : echeanceRepository.findByTenantIdAndStatutOrderByDateEcheanceAsc(tenantId, DossierEcheance.Statut.OUVERTE)) {
            UUID exercice = e.getExerciceId() != null ? e.getExerciceId() : courant.get(e.getEntrepriseId());
            if (exercice != null) {
                echeances.computeIfAbsent(e.getEntrepriseId() + ":" + exercice, k -> new ArrayList<>()).add(e);
            }
        }
        LocalDate aujourdhui = LocalDate.now(clock);
        List<Vue> vues = new ArrayList<>(charges.size());
        for (DossierCharge c : charges) {
            DossierRow d = c.dossier();
            List<DossierEcheance> ouvertes = echeances.getOrDefault(d.entrepriseId() + ":" + d.exerciceId(), List.of());
            boolean retard = ouvertes.stream().anyMatch(e -> e.getDateEcheance().isBefore(aujourdhui));
            boolean proche = ouvertes.stream().anyMatch(e -> !e.getDateEcheance().isBefore(aujourdhui) && !e.getDateEcheance().isAfter(aujourdhui.plusDays(JOURS_ECHEANCE_PROCHE)));
            DossierSignals signaux = c.signaux().toBuilder().echeanceEnRetard(retard).echeanceProche(proche).build();
            Evaluation ev = evaluator.evaluer(signaux);
            DossierEcheance prochaine = ouvertes.isEmpty() ? null : ouvertes.get(0);
            DossierSummaryDto dto = new DossierSummaryDto(d.entrepriseId(), d.entrepriseNom(), d.identifiant(), d.exerciceId(), d.debut(), d.fin(), d.courant(),
                    d.collaborateurId() == null ? null : new UtilisateurRef(d.collaborateurId(), d.collaborateurNom()),
                    d.expertId() == null ? null : new UtilisateurRef(d.expertId(), d.expertNom()),
                    ev.statut().name(), ev.statut().libelle(), ev.priorite().name(), ev.etapesPretes(), ev.etapesTotal(),
                    ev.etapes().stream().map(e -> new EtapeDto(e.etape().name(), e.etape().libelle(), e.prete())).toList(),
                    signaux.findingsInternes(), signaux.findingsBloquants(), signaux.demandesAttenteClient(),
                    signaux.banqueNonRapprochees() + signaux.banquePropositions() + signaux.banqueAmbigues(),
                    prochaine == null ? null : new EcheanceRef(prochaine.getId(), prochaine.getDateEcheance(), prochaine.getType().name(), prochaine.getLibelle()),
                    c.derniereActivite(), c.tva() == null ? null : statutTva(c.tva()), c.tva() == null ? null : libelleTva(c.tva()));
            vues.add(new Vue(dto, prochaine == null ? null : prochaine.getDateEcheance()));
        }
        return vues;
    }

    @Transactional(readOnly = true)
    public PageDto<DossierSummaryDto> dossiers(UtilisateurPrincipal principal, DossierQuery q) {
        exigerCabinet(principal);
        List<DossierSummaryDto> tous = filtrer(vues(principal.getTenantId()), q, principal.getId());
        int taille = Math.max(1, Math.min(q.taille(), TAILLE_MAX));
        int page = Math.max(0, q.page());
        int debut = Math.min(page * taille, tous.size());
        return new PageDto<>(tous.subList(debut, Math.min(debut + taille, tous.size())), page, taille, tous.size());
    }

    private List<DossierSummaryDto> filtrer(List<Vue> vues, DossierQuery q, UUID moi) {
        String recherche = q.q() == null ? "" : q.q().trim().toLowerCase(Locale.ROOT);
        Filtre filtre = q.filtre() == null ? Filtre.TOUS : q.filtre();
        Comparator<Vue> ordre = comparateur(q.tri() == null ? Tri.PRIORITE : q.tri(), q.decroissant());
        return vues.stream()
                .filter(v -> correspondFiltre(v.dto(), filtre, moi))
                .filter(v -> q.collaborateurId() == null || (v.dto().collaborateur() != null && q.collaborateurId().equals(v.dto().collaborateur().id())))
                .filter(v -> q.expertId() == null || (v.dto().expert() != null && q.expertId().equals(v.dto().expert().id())))
                .filter(v -> q.exercice() == null || v.dto().exerciceFin().getYear() == q.exercice())
                .filter(v -> q.statut() == null || v.dto().statut().equals(q.statut().name()))
                .filter(v -> recherche.isEmpty() || contient(v.dto(), recherche))
                .sorted(ordre)
                .map(Vue::dto)
                .toList();
    }

    /** Avancement de la TVA d'un dossier : À préparer / À revoir / Prête / Finalisée (le détail est dans l'espace TVA). */
    /** Code compact de l'indicateur TVA, calculé par l'évaluateur unique du backend ({@code A_TRAITER:n} porte le nombre d'éléments à traiter). */
    private static String statutTva(String code) {
        return code.contains(":") ? code.substring(0, code.indexOf(':')) : code;
    }

    private static String libelleTva(String code) {
        return switch (statutTva(code)) {
            case "A_TRAITER" -> code.substring(code.indexOf(':') + 1) + " à traiter";
            case "A_REVOIR" -> "À revoir";
            case "PRETE" -> "Prête";
            case "FINALISEE" -> "Finalisée";
            case "A_PAYER" -> "Finalisée — À payer";
            case "PARTIELLEMENT_PAYEE" -> "Finalisée — Partiellement payée";
            case "PAYEE" -> "Finalisée — Payée";
            case "CREDIT_A_REPORTER" -> "Finalisée — Crédit à reporter";
            case "CADRE_INCOMPLET" -> "Cadre réglementaire incomplet";
            default -> "À préparer";
        };
    }

    private static boolean contient(DossierSummaryDto d, String recherche) {
        return minuscule(d.entrepriseNom()).contains(recherche) || minuscule(d.identifiant()).contains(recherche)
                || (d.collaborateur() != null && minuscule(d.collaborateur().nom()).contains(recherche))
                || (d.expert() != null && minuscule(d.expert().nom()).contains(recherche));
    }

    private static String minuscule(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean correspondFiltre(DossierSummaryDto d, Filtre filtre, UUID moi) {
        StatutOperationnel s = StatutOperationnel.valueOf(d.statut());
        return switch (filtre) {
            case TOUS -> true;
            case MES_DOSSIERS -> estAffecteA(d, moi);
            // un dossier sans aucune activité n'est pas encore « à traiter » : il apparaît dans « Tous » et par son statut À démarrer
            case A_TRAITER -> EnumSet.of(StatutOperationnel.EN_COURS, StatutOperationnel.ATTENTION_REQUISE).contains(s);
            case EN_ATTENTE_CLIENT -> s == StatutOperationnel.EN_ATTENTE_CLIENT;
            case PRETS_REVUE -> s == StatutOperationnel.PRET_POUR_REVUE || s == StatutOperationnel.EN_REVUE;
            case PRETS_CLOTURE -> s == StatutOperationnel.PRET_A_CLOTURER;
            case FINALISES -> s == StatutOperationnel.FINALISE;
        };
    }

    private static boolean estAffecteA(DossierSummaryDto d, UUID moi) {
        return (d.collaborateur() != null && d.collaborateur().id().equals(moi)) || (d.expert() != null && d.expert().id().equals(moi));
    }

    private Comparator<Vue> comparateur(Tri tri, boolean decroissant) {
        Comparator<Vue> parNom = Comparator.comparing((Vue v) -> minuscule(v.dto().entrepriseNom())).thenComparing(v -> v.dto().exerciceFin(), Comparator.reverseOrder());
        Comparator<Vue> ordre = switch (tri) {
            case ENTREPRISE -> parNom;
            case ACTIVITE -> Comparator.comparing((Vue v) -> v.dto().derniereActivite(), Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder())).thenComparing(parNom);
            case ECHEANCE -> Comparator.comparing(Vue::prochaineEcheance, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())).thenComparing(parNom);
            case PRIORITE -> Comparator.comparing((Vue v) -> PrioriteDossier.valueOf(v.dto().priorite()))
                    .thenComparing(v -> rangStatut(StatutOperationnel.valueOf(v.dto().statut()))).thenComparing(parNom);
        };
        return decroissant ? ordre.reversed() : ordre;
    }

    /** Ordre de travail : ce qui demande une action du cabinet d'abord, ce qui est terminé en dernier. */
    private static int rangStatut(StatutOperationnel s) {
        return switch (s) {
            case ATTENTION_REQUISE -> 0;
            case PRET_POUR_REVUE -> 1;
            case EN_REVUE -> 2;
            case PRET_A_CLOTURER -> 3;
            case EN_COURS -> 4;
            case EN_ATTENTE_CLIENT -> 5;
            case A_DEMARRER -> 6;
            case CLOTURE -> 7;
            case FINALISE -> 8;
        };
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ dashboard

    @Transactional(readOnly = true)
    public DashboardDto dashboard(UtilisateurPrincipal principal, boolean mes) {
        exigerCabinet(principal);
        UUID tenant = principal.getTenantId();
        List<Vue> vues = vues(tenant);
        List<Vue> perimetre = mes ? vues.stream().filter(v -> estAffecteA(v.dto(), principal.getId())).toList() : vues;
        List<DossierSummaryDto> actifs = perimetre.stream().map(Vue::dto).filter(d -> StatutOperationnel.valueOf(d.statut()).actif() && d.courant()).toList();
        LocalDate aujourdhui = LocalDate.now(clock);
        long echeancesProches = echeances(tenant, perimetre, mes ? principal.getId() : null).stream()
                .filter(e -> !e.date().isAfter(aujourdhui.plusDays(JOURS_ECHEANCE_PROCHE))).count();
        SummaryDto summary = new SummaryDto(actifs.size(),
                actifs.stream().filter(d -> correspondFiltre(d, Filtre.A_TRAITER, null)).count(),
                actifs.stream().filter(d -> correspondFiltre(d, Filtre.EN_ATTENTE_CLIENT, null)).count(),
                actifs.stream().filter(d -> StatutOperationnel.PRET_POUR_REVUE.name().equals(d.statut())).count(),
                echeancesProches);

        PageDto<WorkItemDto> file = workQueue(principal, mes, 0, 8);
        boolean expert = DossierRoles.peutRevoir(principal);
        List<DossierSummaryDto> aRevoir = expert ? vues.stream().map(Vue::dto)
                .filter(d -> correspondFiltre(d, Filtre.PRETS_REVUE, null))
                .filter(d -> !mes || d.expert() == null || d.expert().id().equals(principal.getId()))
                .sorted(Comparator.comparing((DossierSummaryDto d) -> PrioriteDossier.valueOf(d.priorite())).thenComparing(d -> minuscule(d.entrepriseNom())))
                .limit(5).toList() : List.of();
        List<EcheanceDto> echeancesAffichees = echeances(tenant, perimetre, mes ? principal.getId() : null).stream().limit(6).toList();
        return new DashboardDto(mes ? "MES_DOSSIERS" : "CABINET", summary, file, aRevoir, echeancesAffichees);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ work queue

    @Transactional(readOnly = true)
    public PageDto<WorkItemDto> workQueue(UtilisateurPrincipal principal, boolean mes, int page, int taille) {
        exigerCabinet(principal);
        int t = Math.max(1, Math.min(taille, TAILLE_MAX));
        int p = Math.max(0, page);
        WorkQueueLoader.Resultat r = workQueueLoader.charger(principal.getTenantId(), mes ? principal.getId() : null, p, t);
        OffsetDateTime maintenant = OffsetDateTime.now(clock);
        List<WorkItemDto> items = r.lignes().stream().map(l -> versItem(l, maintenant)).toList();
        return new PageDto<>(items, p, t, r.total());
    }

    private WorkItemDto versItem(WorkRow l, OffsetDateTime maintenant) {
        boolean demande = "DEMANDE_PIECE".equals(l.kind());
        boolean fiscal = "TAX_OPERATION".equals(l.sourceType()) || "TAX_PERIOD".equals(l.sourceType()) || "TAX_REVIEW".equals(l.kind()) || "TAX_PAYMENT".equals(l.kind());
        String impact = "TAX_REVIEW".equals(l.kind()) ? "À revoir par l'expert" : "TAX_PAYMENT".equals(l.kind()) ? "Paiement à suivre" : fiscal ? "À corriger avant la déclaration" : demande ? "Pièce reçue à rattacher" : l.bloquant() ? "Bloque la clôture" : "IMPORTANT".equals(l.severite()) ? "À corriger avant la clôture" : "À vérifier";
        String priorite = l.bloquant() ? PrioriteDossier.BLOCKER.name() : "IMPORTANT".equals(l.severite()) ? PrioriteDossier.HIGH.name() : PrioriteDossier.NORMAL.name();
        String cible = fiscal ? "FISCALITE" : demande ? "DEMANDES" : "RECONCILIATION".equals(l.categorie()) ? "BANQUE" : CODES_ETATS_FINANCIERS.contains(l.sourceType()) ? "ETATS_FINANCIERS" : "REVISION";
        long jours = l.depuis() == null ? 0 : Duration.between(l.depuis(), maintenant).toDays();
        return new WorkItemDto(l.kind(), l.id(), l.entrepriseId(), l.entrepriseNom(), l.exerciceId(), l.titre(), domaine(l.categorie()), impact, priorite, jours, l.depuis(),
                l.responsableId() == null ? null : new UtilisateurRef(l.responsableId(), l.responsableNom()), cible);
    }

    private static String domaine(String categorie) {
        return switch (categorie == null ? "" : categorie) {
            case "DOCUMENT" -> "Documents";
            case "RECONCILIATION" -> "Banque";
            case "ACCOUNTING" -> "Comptabilité";
            case "TAX" -> "Fiscalité";
            case "THIRD_PARTY" -> "Tiers";
            case "STOCK" -> "Stocks";
            case "FIXED_ASSET" -> "Immobilisations";
            case "CLOSING" -> "Clôture";
            default -> "Contrôles";
        };
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ échéances

    @Transactional(readOnly = true)
    public EcheancesDto echeances(UtilisateurPrincipal principal, String periode, boolean mes) {
        exigerCabinet(principal);
        List<EcheanceDto> toutes = echeances(principal.getTenantId(), vues(principal.getTenantId()), mes ? principal.getId() : null);
        long retard = toutes.stream().filter(e -> "EN_RETARD".equals(e.periode())).count();
        long jour = toutes.stream().filter(e -> "AUJOURDHUI".equals(e.periode())).count();
        long semaine = toutes.stream().filter(e -> "CETTE_SEMAINE".equals(e.periode())).count();
        long venir = toutes.stream().filter(e -> "A_VENIR".equals(e.periode())).count();
        List<EcheanceDto> filtrees = periode == null || periode.isBlank() || "TOUTES".equals(periode) ? toutes : toutes.stream().filter(e -> periode.equals(e.periode())).toList();
        return new EcheancesDto(retard, jour, semaine, venir, filtrees);
    }

    /** Échéances ouvertes des dossiers du périmètre, déjà triées par date ; {@code responsable} non nul : celles de cette personne ou des dossiers qu'elle porte. */
    private List<EcheanceDto> echeances(UUID tenantId, List<Vue> perimetre, UUID responsable) {
        Map<UUID, DossierSummaryDto> parEntreprise = new HashMap<>();
        Set<UUID> entreprises = new HashSet<>();
        for (Vue v : perimetre) {
            entreprises.add(v.dto().entrepriseId());
            if (v.dto().courant()) {
                parEntreprise.put(v.dto().entrepriseId(), v.dto());
            }
        }
        Map<UUID, String> noms = utilisateurRepository.findAllById(echeanceRepository.findByTenantIdAndStatutOrderByDateEcheanceAsc(tenantId, DossierEcheance.Statut.OUVERTE).stream()
                .map(DossierEcheance::getResponsableId).filter(java.util.Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Utilisateur::getId, u -> (u.getNom() + " " + u.getPrenom()).trim()));
        LocalDate aujourdhui = LocalDate.now(clock);
        List<EcheanceDto> resultat = new ArrayList<>();
        for (DossierEcheance e : echeanceRepository.findByTenantIdAndStatutOrderByDateEcheanceAsc(tenantId, DossierEcheance.Statut.OUVERTE)) {
            if (!entreprises.contains(e.getEntrepriseId())) {
                continue;
            }
            DossierSummaryDto dossier = parEntreprise.get(e.getEntrepriseId());
            if (responsable != null && !responsable.equals(e.getResponsableId()) && (dossier == null || !estAffecteA(dossier, responsable))) {
                continue;
            }
            resultat.add(versEcheance(e, dossier, noms, aujourdhui));
        }
        return resultat;
    }

    private EcheanceDto versEcheance(DossierEcheance e, DossierSummaryDto dossier, Map<UUID, String> noms, LocalDate aujourdhui) {
        String periode = e.getStatut() == DossierEcheance.Statut.FAITE ? "FAITE" : e.getDateEcheance().isBefore(aujourdhui) ? "EN_RETARD" : e.getDateEcheance().isEqual(aujourdhui) ? "AUJOURDHUI"
                : !e.getDateEcheance().isAfter(aujourdhui.plusDays(JOURS_ECHEANCE_PROCHE)) ? "CETTE_SEMAINE" : "A_VENIR";
        return new EcheanceDto(e.getId(), e.getEntrepriseId(), dossier == null ? null : dossier.entrepriseNom(), e.getExerciceId() != null ? e.getExerciceId() : dossier == null ? null : dossier.exerciceId(),
                e.getType().name(), e.getType().libelle(), e.getLibelle(), e.getDateEcheance(),
                e.getResponsableId() == null ? null : new UtilisateurRef(e.getResponsableId(), noms.get(e.getResponsableId())), e.getStatut().name(), periode);
    }

    @Transactional
    public EcheanceDto creerEcheance(UtilisateurPrincipal principal, EcheanceRequest req) {
        exigerCabinet(principal);
        UUID tenant = principal.getTenantId();
        exigerEntreprise(tenant, req.entrepriseId());
        if (req.exerciceId() != null) {
            exigerExercice(req.entrepriseId(), req.exerciceId());
        }
        if (req.date() == null || req.libelle() == null || req.libelle().isBlank() || req.type() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Type, libellé et date sont obligatoires");
        }
        DossierEcheance e = new DossierEcheance();
        e.setTenantId(tenant);
        e.setEntrepriseId(req.entrepriseId());
        e.setExerciceId(req.exerciceId());
        e.setType(typeEcheance(req.type()));
        e.setLibelle(req.libelle().trim());
        e.setDateEcheance(req.date());
        e.setResponsableId(exigerMembre(tenant, req.responsableId()));
        e.setCreatedAt(OffsetDateTime.now(clock));
        e.setUpdatedAt(e.getCreatedAt());
        echeanceRepository.save(e);
        audit.enregistrerActionHumaine(tenant, principal.getId(), "ECHEANCE_DOSSIER_CREEE", "ECHEANCE_DOSSIER", e.getId().toString(),
                Map.of("entrepriseId", e.getEntrepriseId().toString(), "type", e.getType().name(), "date", e.getDateEcheance().toString()));
        return versEcheance(e, null, Map.of(), LocalDate.now(clock));
    }

    @Transactional
    public EcheanceDto modifierEcheance(UtilisateurPrincipal principal, UUID id, EcheancePatch patch) {
        exigerCabinet(principal);
        UUID tenant = principal.getTenantId();
        DossierEcheance e = echeanceRepository.findByIdAndTenantId(id, tenant).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Échéance introuvable"));
        Map<String, Object> avant = new HashMap<>();
        avant.put("date", e.getDateEcheance().toString());
        avant.put("statut", e.getStatut().name());
        avant.put("responsableId", e.getResponsableId() == null ? null : e.getResponsableId().toString());
        if (patch.date() != null) {
            e.setDateEcheance(patch.date());
        }
        if (patch.statut() != null) {
            try {
                e.setStatut(DossierEcheance.Statut.valueOf(patch.statut()));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Statut d'échéance inconnu : " + patch.statut());
            }
        }
        if (patch.responsableId() != null) {
            e.setResponsableId(exigerMembre(tenant, patch.responsableId()));
        }
        if (patch.libelle() != null && !patch.libelle().isBlank()) {
            e.setLibelle(patch.libelle().trim());
        }
        e.setUpdatedAt(OffsetDateTime.now(clock));
        echeanceRepository.save(e);
        Map<String, Object> details = new HashMap<>();
        details.put("avant", avant);
        details.put("date", e.getDateEcheance().toString());
        details.put("statut", e.getStatut().name());
        details.put("responsableId", e.getResponsableId() == null ? null : e.getResponsableId().toString());
        audit.enregistrerActionHumaine(tenant, principal.getId(), "ECHEANCE_DOSSIER_MODIFIEE", "ECHEANCE_DOSSIER", e.getId().toString(), details);
        return versEcheance(e, null, Map.of(), LocalDate.now(clock));
    }

    private static DossierEcheance.Type typeEcheance(String type) {
        try {
            return DossierEcheance.Type.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Type d'échéance inconnu : " + type);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ affectation

    /** Remplace l'affectation d'un dossier (les deux champs : null efface). Réservé à l'expert-comptable ; tracé dans l'audit. */
    @Transactional
    public DossierSummaryDto affecter(UtilisateurPrincipal principal, UUID entrepriseId, UUID exerciceId, AffectationRequest req) {
        exigerExpert(principal);
        appliquer(principal, entrepriseId, exerciceId, req.collaborateurId(), req.expertId(), true);
        return resume(principal.getTenantId(), entrepriseId, exerciceId);
    }

    /** Affecte plusieurs dossiers : seuls les champs fournis (non nuls) sont appliqués, l'autre responsable est conservé. Tout ou rien. */
    @Transactional
    public int affecterEnMasse(UtilisateurPrincipal principal, AffectationEnMasseRequest req) {
        exigerExpert(principal);
        if (req.dossiers() == null || req.dossiers().isEmpty() || (req.collaborateurId() == null && req.expertId() == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Sélectionnez au moins un dossier et un responsable");
        }
        if (req.dossiers().size() > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "500 dossiers au maximum par affectation");
        }
        for (DossierRef d : req.dossiers()) {
            appliquer(principal, d.entrepriseId(), d.exerciceId(), req.collaborateurId(), req.expertId(), false);
        }
        return req.dossiers().size();
    }

    private void appliquer(UtilisateurPrincipal principal, UUID entrepriseId, UUID exerciceId, UUID collaborateurId, UUID expertId, boolean remplacer) {
        UUID tenant = principal.getTenantId();
        exigerEntreprise(tenant, entrepriseId);
        exigerExercice(entrepriseId, exerciceId);
        UUID collaborateur = exigerMembre(tenant, collaborateurId);
        UUID expert = exigerExpert(tenant, expertId);
        DossierAffectation a = affectationRepository.findByEntrepriseIdAndExerciceId(entrepriseId, exerciceId).orElse(null);
        boolean creation = a == null;
        UUID ancienCollaborateur = creation ? null : a.getCollaborateurId();
        UUID ancienExpert = creation ? null : a.getExpertId();
        if (creation) {
            a = new DossierAffectation();
            a.setTenantId(tenant);
            a.setEntrepriseId(entrepriseId);
            a.setExerciceId(exerciceId);
        }
        UUID nouveauCollaborateur = remplacer || collaborateur != null ? collaborateur : ancienCollaborateur;
        UUID nouvelExpert = remplacer || expert != null ? expert : ancienExpert;
        if (!creation && java.util.Objects.equals(ancienCollaborateur, nouveauCollaborateur) && java.util.Objects.equals(ancienExpert, nouvelExpert)) {
            return;
        }
        a.setCollaborateurId(nouveauCollaborateur);
        a.setExpertId(nouvelExpert);
        a.setUpdatedAt(OffsetDateTime.now(clock));
        a.setUpdatedBy(principal.getId());
        affectationRepository.save(a);
        Map<String, Object> details = new HashMap<>();
        details.put("entrepriseId", entrepriseId.toString());
        details.put("exerciceId", exerciceId.toString());
        details.put("collaborateurAvant", ancienCollaborateur == null ? null : ancienCollaborateur.toString());
        details.put("collaborateurApres", nouveauCollaborateur == null ? null : nouveauCollaborateur.toString());
        details.put("expertAvant", ancienExpert == null ? null : ancienExpert.toString());
        details.put("expertApres", nouvelExpert == null ? null : nouvelExpert.toString());
        audit.enregistrerActionHumaine(tenant, principal.getId(), creation ? "DOSSIER_AFFECTE" : "RESPONSABLE_DOSSIER_MODIFIE", "DOSSIER", exerciceId.toString(), details);
    }

    private DossierSummaryDto resume(UUID tenantId, UUID entrepriseId, UUID exerciceId) {
        return portefeuille(tenantId).stream().filter(d -> d.entrepriseId().equals(entrepriseId) && d.exerciceId().equals(exerciceId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable"));
    }

    /** Collaborateurs et experts ACTIFS du cabinet (sélecteurs d'affectation et de filtre) : un membre désactivé n'est plus proposé. */
    @Transactional(readOnly = true)
    public List<UtilisateurRef> membres(UtilisateurPrincipal principal) {
        exigerCabinet(principal);
        return utilisateurRepository.findByTenantIdAndRoleCodeIn(principal.getTenantId(), List.of("COLLABORATEUR", "EXPERT_COMPTABLE")).stream()
                .filter(u -> "ACTIF".equals(u.getStatut())).map(u -> new UtilisateurRef(u.getId(), (u.getNom() + " " + u.getPrenom()).trim())).toList();
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------ garde-fous

    private void exigerCabinet(UtilisateurPrincipal principal) {
        if (!DossierRoles.peutPreparer(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Le Cabinet Workspace est réservé aux collaborateurs et experts-comptables");
        }
    }

    private void exigerExpert(UtilisateurPrincipal principal) {
        if (!DossierRoles.peutRevoir(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'affectation des dossiers est réservée à l'expert-comptable");
        }
    }

    /** 404 (et non 403) pour une entreprise d'un autre cabinet : on ne révèle jamais son existence. */
    private void exigerEntreprise(UUID tenantId, UUID entrepriseId) {
        Integer n = entrepriseId == null ? 0 : jdbc.queryForObject("select count(*) from entreprise where id = ? and tenant_id = ?", Integer.class, entrepriseId, tenantId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable");
        }
    }

    private void exigerExercice(UUID entrepriseId, UUID exerciceId) {
        Integer n = exerciceId == null ? 0 : jdbc.queryForObject("select count(*) from exercice_comptable where id = ? and entreprise_id = ?", Integer.class, exerciceId, entrepriseId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable");
        }
    }

    /** Un responsable doit appartenir au même cabinet et porter un rôle de cabinet : collaborateur ou expert. */
    private UUID exigerMembre(UUID tenantId, UUID utilisateurId) {
        return exigerRole(tenantId, utilisateurId, Set.of("COLLABORATEUR", "EXPERT_COMPTABLE"), "un collaborateur ou un expert-comptable du cabinet");
    }

    private UUID exigerExpert(UUID tenantId, UUID utilisateurId) {
        return exigerRole(tenantId, utilisateurId, Set.of("EXPERT_COMPTABLE"), "un expert-comptable du cabinet");
    }

    private UUID exigerRole(UUID tenantId, UUID utilisateurId, Set<String> roles, String attendu) {
        if (utilisateurId == null) {
            return null;
        }
        Utilisateur u = utilisateurRepository.findById(utilisateurId).filter(x -> x.getTenant() != null && tenantId.equals(x.getTenant().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        if (!"ACTIF".equals(u.getStatut())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Ce membre est désactivé");
        }
        if (u.getRoles().stream().noneMatch(r -> roles.contains(r.getCode()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le responsable doit être " + attendu);
        }
        return u.getId();
    }
}
