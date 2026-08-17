package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
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
import java.util.List;
import java.util.Map;
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

    /** Cree un Finding, sauf si un Finding OPEN existe deja pour exactement cette source (evite
     * qu'un moteur relance a chaque cycle duplique le meme constat) - retourne alors l'existant
     * tel quel, sans le modifier (une nouvelle execution du moteur ne doit pas effacer une
     * resolution/rejet humain en cours, ni re-notifier pour rien). */
    @Transactional
    public Finding signaler(Entreprise entreprise, TypeFinding type, SeveriteFinding severity,
                             String sourceType, String sourceId, String title, String description,
                             Double confidence, Map<String, Object> evidence, String recommendedAction) {
        return findingRepository
                .findByEntrepriseIdAndSourceTypeAndSourceIdAndStatus(
                        entreprise.getId(), sourceType, sourceId, StatutFinding.OPEN)
                .orElseGet(() -> {
                    Finding finding = new Finding();
                    finding.setEntreprise(entreprise);
                    finding.setType(type);
                    finding.setSeverity(severity);
                    finding.setSourceType(sourceType);
                    finding.setSourceId(sourceId);
                    finding.setTitle(title);
                    finding.setDescription(description);
                    finding.setConfidence(confidence);
                    finding.setEvidenceJson(evidence);
                    finding.setRecommendedAction(recommendedAction);
                    return findingRepository.save(finding);
                });
    }

    /** Ferme automatiquement le Finding OPEN attache a cette source, si un moteur constate que le
     * probleme sous-jacent a disparu (ex: une ligne NO_MATCH finit par etre rapprochee). Silencieux
     * si aucun Finding ouvert n'existe pour cette source - appele preventivement par les moteurs. */
    @Transactional
    public void resoudreParSource(UUID entrepriseId, String sourceType, String sourceId) {
        findingRepository.findByEntrepriseIdAndSourceTypeAndSourceIdAndStatus(
                        entrepriseId, sourceType, sourceId, StatutFinding.OPEN)
                .ifPresent(finding -> {
                    finding.setStatus(StatutFinding.RESOLVED);
                    finding.setResolvedAt(OffsetDateTime.now());
                    findingRepository.save(finding);
                });
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
        Finding finding = charger(entrepriseId, findingId);
        finding.setStatus(StatutFinding.DISMISSED);
        finding.setResolvedAt(OffsetDateTime.now());
        finding.setResolvedBy(utilisateurRepository.getReferenceById(principal.getId()));
        finding.setRejectionReason(motif);
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
