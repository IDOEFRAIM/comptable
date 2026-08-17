package com.example.comptable.cabinet;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CollaborateurDto;
import com.example.comptable.cabinet.web.dto.InboxItemDto;
import com.example.comptable.cabinet.web.dto.WorkloadDto;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * "Work Queue unifiee" (mission P1, section 12) : {@code Finding} et {@code DemandePiece}
 * EN_ATTENTE presentes dans une seule liste triee par severite/echeance, sans dupliquer leurs
 * donnees dans une nouvelle table - une projection de lecture batchee sur tout le portefeuille du
 * cabinet (meme principe de performance que {@link CabinetCockpitService}, section 54).
 */
@Service
public class InboxService {

    private final EntrepriseRepository entrepriseRepository;
    private final FindingRepository findingRepository;
    private final DemandePieceRepository demandePieceRepository;
    private final UtilisateurRepository utilisateurRepository;

    public InboxService(EntrepriseRepository entrepriseRepository,
                         FindingRepository findingRepository,
                         DemandePieceRepository demandePieceRepository,
                         UtilisateurRepository utilisateurRepository) {
        this.entrepriseRepository = entrepriseRepository;
        this.findingRepository = findingRepository;
        this.demandePieceRepository = demandePieceRepository;
        this.utilisateurRepository = utilisateurRepository;
    }

    @Transactional(readOnly = true)
    public List<InboxItemDto> lister(UtilisateurPrincipal principal, boolean assigneAMoiSeulement) {
        List<Entreprise> entreprises = entrepriseRepository.findByTenantId(principal.getTenantId());
        List<UUID> entrepriseIds = entreprises.stream().map(Entreprise::getId).toList();
        if (entrepriseIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> nomsEntreprises = entreprises.stream()
                .collect(Collectors.toMap(Entreprise::getId, Entreprise::getRaisonSociale));

        List<Finding> findings = findingRepository
                .findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseIds, StatutFinding.OPEN);
        Stream<InboxItemDto> itemsFinding = findings.stream()
                .filter(f -> !assigneAMoiSeulement
                        || (f.getAssignedTo() != null && f.getAssignedTo().getId().equals(principal.getId())))
                .map(f -> new InboxItemDto(f.getId(), "FINDING", f.getEntreprise().getId(),
                        nomsEntreprises.get(f.getEntreprise().getId()), f.getSeverity().name(), f.getTitle(),
                        f.getDueDate(),
                        f.getAssignedTo() != null ? f.getAssignedTo().getId() : null,
                        f.getAssignedTo() != null ? f.getAssignedTo().getNom() + " " + f.getAssignedTo().getPrenom() : null,
                        f.getCreatedAt()));

        // Les demandes n'ont pas d'assignation individuelle (voir InboxItemDto) : elles
        // n'apparaissent que dans la vue d'equipe, pas dans "mes taches assignees".
        Stream<InboxItemDto> itemsDemande = assigneAMoiSeulement ? Stream.empty()
                : demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(entrepriseIds, StatutDemandePiece.EN_ATTENTE)
                        .stream()
                        .map(d -> new InboxItemDto(d.getId(), "DEMANDE_PIECE", d.getEntreprise().getId(),
                                nomsEntreprises.get(d.getEntreprise().getId()), severiteDemande(d), d.getLibelle(),
                                d.getDateLimite(), null, null, d.getCreatedAt()));

        return Stream.concat(itemsFinding, itemsDemande)
                .sorted(Comparator.comparing(InboxItemDto::severite)
                        .thenComparing(InboxItemDto::createdAt, Comparator.reverseOrder()))
                .toList();
    }

    private String severiteDemande(DemandePiece demande) {
        return demande.getDateLimite() != null && demande.getDateLimite().isBefore(LocalDate.now())
                ? "IMPORTANT" : "REVIEW";
    }

    /** Mission P1 (section 13) : charge par collaborateur, calculee uniquement a partir des
     * Finding assignes (les demandes n'ont pas d'assignation, voir {@link #lister}). */
    @Transactional(readOnly = true)
    public List<WorkloadDto> chargeParCollaborateur(UtilisateurPrincipal principal) {
        List<UUID> entrepriseIds = entrepriseRepository.findByTenantId(principal.getTenantId()).stream()
                .map(Entreprise::getId).toList();
        if (entrepriseIds.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = findingRepository
                .findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseIds, StatutFinding.OPEN)
                .stream()
                .filter(f -> f.getAssignedTo() != null)
                .toList();

        Map<UUID, List<Finding>> parCollaborateur = findings.stream()
                .collect(Collectors.groupingBy(f -> f.getAssignedTo().getId()));

        return parCollaborateur.values().stream()
                .map(taches -> {
                    Utilisateur collaborateur = taches.get(0).getAssignedTo();
                    long critiques = taches.stream().filter(f -> f.getSeverity() == SeveriteFinding.CRITICAL).count();
                    return new WorkloadDto(collaborateur.getId(),
                            collaborateur.getNom() + " " + collaborateur.getPrenom(), taches.size(), (int) critiques);
                })
                .sorted(Comparator.comparing(WorkloadDto::totalTaches).reversed())
                .toList();
    }

    /** Mission P1.5 (section 51) : source du selecteur d'assignation d'un Finding - contrairement
     * a {@link #chargeParCollaborateur}, inclut les collaborateurs sans aucune tache assignee. */
    @Transactional(readOnly = true)
    public List<CollaborateurDto> listerCollaborateurs(UtilisateurPrincipal principal) {
        return utilisateurRepository
                .findByTenantIdAndRoleCodeIn(principal.getTenantId(), List.of("COLLABORATEUR", "EXPERT_COMPTABLE"))
                .stream()
                .map(u -> new CollaborateurDto(u.getId(), u.getNom() + " " + u.getPrenom()))
                .toList();
    }
}
