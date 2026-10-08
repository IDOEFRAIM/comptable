package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.domain.TypeResolution;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlResultAdapters;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Point d'entree unique pour creer/lire/resoudre des {@link Finding} (mission P0). Les moteurs
 * (ControlEngine, MissingEvidenceAnalyzer, phase 2) appellent {@link #signaler} et
 * {@link #resoudreParSource} ; l'Inbox humaine (phase 7) appelle {@link #listerOuverts},
 * {@link #resoudre} et {@link #rejeter}. Un Finding ne declenche jamais d'action automatique par
 * lui-meme - il trace un constat, exactement comme AiMatchProposal trace une proposition.
 */
@Service
@RequiredArgsConstructor
public class FindingService {

    private final FindingRepository findingRepository;
    private final EntrepriseAccessService entrepriseAccessService;
    private final UtilisateurRepository utilisateurRepository;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;

    /** Ce qu'a produit une declaration de constat (voir {@link #declarer}). */
    public enum Effet { CREE, MIS_A_JOUR, INCHANGE, ECARTE_CONSERVE }

    public record Declaration(Finding finding, Effet effet) {
    }

    /** Fenetre en dessous de laquelle une simple re-detection n'ecrit pas {@code lastDetectedAt} (evite une ecriture par constat et par passage). */
    private static final long RAFRAICHISSEMENT_DETECTION_MINUTES = 60;

    /**
     * Declare un constat de controle. Cle de deduplication metier : (entreprise, code de controle,
     * type d'objet, id d'objet). Un constat actif existant est MIS A JOUR (jamais duplique) ; un
     * constat ECARTE par un humain n'est pas recree tant que la cause persiste ; sinon un Finding
     * est cree. Un constat qui n'est pas OPEN n'a pas a etre declare.
     */
    @Transactional
    public Declaration declarer(Entreprise entreprise, ControlResult resultat, TypeFinding type) {
        UUID entrepriseId = entreprise.getId();
        Map<String, Object> evidence = new HashMap<>(resultat.metadata());
        if (!resultat.relatedEntityIds().isEmpty()) {
            evidence.put(ControlResultAdapters.META_RELATED, resultat.relatedEntityIds());
        }
        evidence = EvidenceJson.normaliser(evidence);
        SeveriteFinding severite = ControlResultAdapters.versSeveriteFinding(resultat.severity());
        OffsetDateTime maintenant = OffsetDateTime.now();

        Optional<Finding> actif = findingRepository.findActif(entrepriseId, resultat.code(), resultat.entityType(), resultat.entityId());
        if (actif.isPresent()) {
            Finding finding = actif.get();
            boolean change = finding.getControlSeverity() != resultat.severity()
                    || !Objects.equals(finding.getTitle(), resultat.message())
                    || !Objects.equals(finding.getDescription(), resultat.explanation())
                    || !Objects.equals(finding.getRecommendedAction(), resultat.suggestedAction())
                    || !Objects.equals(finding.getEvidenceJson(), evidence);
            String domaine = domaineDe(resultat);
            if (!Objects.equals(finding.getDomaine(), domaine)) {
                finding.setDomaine(domaine);
                change = true;
            }
            if (change) {
                finding.setControlSeverity(resultat.severity());
                finding.setSeverity(severite);
                finding.setTitle(resultat.message());
                finding.setDescription(resultat.explanation());
                finding.setRecommendedAction(resultat.suggestedAction());
                finding.setEvidenceJson(evidence);
                finding.setLastDetectedAt(maintenant);
                findingRepository.save(finding);
                auditTrailService.enregistrerEvenementSysteme(tenantDe(entreprise), "FINDING_MIS_A_JOUR", "FINDING",
                        String.valueOf(finding.getId()), Map.of("code", resultat.code(), "severite", resultat.severity().name()));
                return new Declaration(finding, Effet.MIS_A_JOUR);
            }
            if (finding.getLastDetectedAt() == null
                    || finding.getLastDetectedAt().isBefore(maintenant.minusMinutes(RAFRAICHISSEMENT_DETECTION_MINUTES))) {
                finding.setLastDetectedAt(maintenant);
                findingRepository.save(finding);
            }
            return new Declaration(finding, Effet.INCHANGE);
        }

        if (findingRepository.existsByEntrepriseIdAndControlCodeAndSourceTypeAndSourceIdAndStatus(
                entrepriseId, resultat.code(), resultat.entityType(), resultat.entityId(), StatutFinding.DISMISSED)) {
            return new Declaration(null, Effet.ECARTE_CONSERVE);
        }

        Finding finding = new Finding();
        finding.setDomaine(domaineDe(resultat));
        finding.setEntreprise(entreprise);
        finding.setType(type);
        finding.setControlCode(resultat.code());
        finding.setCategory(resultat.category());
        finding.setControlSeverity(resultat.severity());
        finding.setSeverity(severite);
        finding.setSourceType(resultat.entityType());
        finding.setSourceId(resultat.entityId());
        finding.setTitle(resultat.message());
        finding.setDescription(resultat.explanation());
        finding.setEvidenceJson(evidence);
        finding.setRecommendedAction(resultat.suggestedAction());
        finding.setLastDetectedAt(maintenant);
        Finding cree = findingRepository.save(finding);
        auditTrailService.enregistrerEvenementSysteme(tenantDe(entreprise), "FINDING_CREE", "FINDING",
                String.valueOf(cree.getId()), Map.of("code", resultat.code(), "type", type.name(),
                        "severite", resultat.severity().name(), "entityType", resultat.entityType(), "entityId", resultat.entityId()));
        return new Declaration(cree, Effet.CREE);
    }

    private static String domaineDe(ControlResult resultat) {
        Object typeTiers = resultat.metadata().get("typeTiers");
        return com.example.comptable.controle.ControlDomain.de(resultat.code(), typeTiers == null ? null : typeTiers.toString()).name();
    }

    /** Ferme (resolution SYSTEM) un constat actif dont la cause a disparu. Le constat reste en base et dans l'audit. */
    @Transactional
    public void resoudreAutomatiquement(Finding finding) {
        finding.setStatus(StatutFinding.RESOLVED);
        finding.setResolutionType(TypeResolution.SYSTEM);
        finding.setResolvedAt(OffsetDateTime.now());
        findingRepository.save(finding);
        Entreprise entreprise = finding.getEntreprise();
        auditTrailService.enregistrerEvenementSysteme(tenantDe(entreprise), "FINDING_RESOLU_AUTO", "FINDING",
                String.valueOf(finding.getId()), Map.of("code", finding.getControlCode()));
    }

    /** Ferme le constat actif d'un controle precis sur un objet (silencieux s'il n'y en a pas). */
    @Transactional
    public void resoudreParControle(UUID entrepriseId, String controlCode, String entityType, String entityId) {
        findingRepository.findActif(entrepriseId, controlCode, entityType, entityId).ifPresent(this::resoudreAutomatiquement);
    }

    /** Resolution par un HUMAIN (resolutionType MANUAL, resolvedBy) : la trace distingue toujours une decision humaine d'une resolution automatique. */
    @Transactional
    public void resoudreParControleManuellement(UUID entrepriseId, String controlCode, String entityType, String entityId,
                                                 UUID utilisateurId, String commentaire) {
        findingRepository.findActif(entrepriseId, controlCode, entityType, entityId).ifPresent(finding -> {
            finding.setStatus(StatutFinding.RESOLVED);
            finding.setResolutionType(TypeResolution.MANUAL);
            finding.setResolvedAt(OffsetDateTime.now());
            finding.setResolvedBy(utilisateurRepository.getReferenceById(utilisateurId));
            finding.setResolutionComment(commentaire);
            findingRepository.save(finding);
            auditTrailService.enregistrerActionHumaine(finding.getEntreprise().getTenant() != null ? finding.getEntreprise().getTenant().getId() : null,
                    utilisateurId, "FINDING_RESOLU", "FINDING", String.valueOf(finding.getId()), Map.of("code", finding.getControlCode()));
        });
    }

    /** Ferme les constats actifs de TOUS les controles sur un objet (ex. une ligne de releve devenue rapprochee). */
    @Transactional
    public void resoudreParSource(UUID entrepriseId, String sourceType, String sourceId) {
        findingRepository.findActifsParObjet(entrepriseId, sourceType, sourceId).forEach(this::resoudreAutomatiquement);
    }

    private UUID tenantDe(Entreprise entreprise) {
        return entreprise.getTenant() != null ? entreprise.getTenant().getId() : null;
    }

    @Transactional(readOnly = true)
    public List<FindingDto> listerOuverts(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)
                .stream()
                .map(FindingDto::from)
                .toList();
    }

    @Transactional
    public FindingDto resoudre(UUID entrepriseId, UUID findingId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Finding finding = charger(entrepriseId, findingId);
        finding.setStatus(StatutFinding.RESOLVED);
        finding.setResolutionType(TypeResolution.MANUAL);
        finding.setResolvedAt(OffsetDateTime.now());
        finding.setResolvedBy(utilisateurRepository.getReferenceById(principal.getId()));
        FindingDto dto = FindingDto.from(findingRepository.save(finding));
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(),
                "FINDING_RESOLU", "FINDING", findingId.toString(), Map.of("type", dto.type()));
        return dto;
    }

    @Transactional
    public FindingDto rejeter(UUID entrepriseId, UUID findingId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (motif == null || motif.isBlank()) {
            // Ecarter n'est pas supprimer : le constat reste auditable, donc on exige de savoir pourquoi.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un motif est obligatoire pour ecarter un constat");
        }
        Finding finding = charger(entrepriseId, findingId);
        finding.setStatus(StatutFinding.DISMISSED);
        finding.setResolvedAt(OffsetDateTime.now());
        finding.setResolvedBy(utilisateurRepository.getReferenceById(principal.getId()));
        finding.setRejectionReason(motif);
        finding.setResolutionType(TypeResolution.DISMISSED);
        finding.setResolutionComment(motif);
        FindingDto dto = FindingDto.from(findingRepository.save(finding));
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(),
                "FINDING_REJETE", "FINDING", findingId.toString(), Map.of("type", dto.type()));
        return dto;
    }

    /** Mission P1 (section 11) : assigner un Finding ouvert a un collaborateur, avec echeance
     * optionnelle. N'importe qui ayant acces a l'entreprise peut assigner (pas seulement
     * l'EXPERT_COMPTABLE) - le controle plus fin d'assignation reste au niveau du routage HTTP
     * (memes roles que resoudre/rejeter).
     * <p>
     * Mission P1.5 (correctif de securite, section 78) : {@code assigneAId} est fourni par le
     * client et doit etre verifie comme appartenant au meme tenant que l'entreprise avant toute
     * ecriture - sans ce controle, un collaborateur pouvait assigner un Finding a n'importe quel
     * UUID utilisateur, y compris d'un autre cabinet, ce qui faisait fuiter le titre du Finding
     * dans la notification d'un utilisateur etranger au tenant (bug reel trouve par audit,
     * corrige ici). */
    @Transactional
    public FindingDto assigner(UUID entrepriseId, UUID findingId, UUID assigneAId, LocalDate dueDate,
                                UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Finding finding = findingRepository.findById(findingId)
                .filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding introuvable"));
        Utilisateur assigne = assigneAId != null ? chargerCollaborateurDuTenant(assigneAId, principal) : null;
        finding.setAssignedTo(assigne);
        finding.setDueDate(dueDate);
        FindingDto dto = FindingDto.from(findingRepository.save(finding));
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(),
                "FINDING_ASSIGNE", "FINDING", findingId.toString(),
                Map.of("assignedToId", String.valueOf(assigneAId), "dueDate", String.valueOf(dueDate)));
        if (assigne != null) {
            String echeance = dueDate != null ? " (echeance " + dueDate + ")" : "";
            notificationService.notifier(assigne, TypeNotification.FINDING_ASSIGNE,
                    "Un dossier vous a ete assigne : " + dto.title() + echeance);
        }
        return dto;
    }

    /** Refuse d'assigner a un utilisateur hors du tenant de l'appelant - 404 plutot que 403 pour
     * ne pas confirmer l'existence d'un UUID utilisateur etranger (meme convention que
     * {@code EntrepriseAccessService} pour les entreprises d'un autre tenant). */
    private Utilisateur chargerCollaborateurDuTenant(UUID utilisateurId, UtilisateurPrincipal principal) {
        Utilisateur utilisateur = utilisateurRepository.findById(utilisateurId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        if (utilisateur.getTenant() == null || !utilisateur.getTenant().getId().equals(principal.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable");
        }
        return utilisateur;
    }

    private Finding charger(UUID entrepriseId, UUID findingId) {
        Finding finding = findingRepository.findById(findingId)
                .filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding introuvable"));
        if (finding.getStatus() != StatutFinding.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce finding a deja ete traite");
        }
        return finding;
    }
}
