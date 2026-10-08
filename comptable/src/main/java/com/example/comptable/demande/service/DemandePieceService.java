package com.example.comptable.demande.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.demande.web.dto.CreerDemandePieceRequest;
import com.example.comptable.demande.web.dto.DemanderPieceRequest;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.PieceEcritureLinker;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demandes de pieces cibles : le collaborateur pointe precisement ce qui manque
 * ("la facture d'electricite de juin"), le client depose directement dessus depuis
 * son portail, ce qui evite le tri en vrac des pieces recues par WhatsApp/email.
 */
@Service
@RequiredArgsConstructor
public class DemandePieceService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final EntrepriseRepository entrepriseRepository;
    private final DemandePieceRepository demandePieceRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final AuditTrailService auditTrailService;
    private final FindingRepository findingRepository;
    private final PieceEcritureLinker pieceEcritureLinker;
    private final DocumentRepository documentRepository;
    private final com.example.comptable.collecte.service.CollecteService collecteService;
    private final com.example.comptable.collecte.repository.OutboundMessageRepository outboundMessageRepository;

    @Transactional(readOnly = true)
    public List<DemandePieceDto> lister(UUID entrepriseId, StatutDemandePiece statut, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<DemandePiece> demandes = statut != null
                ? demandePieceRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(entrepriseId, statut)
                : demandePieceRepository.findByEntrepriseIdOrderByCreatedAtDesc(entrepriseId);
        return enrichir(demandes);
    }

    @Transactional(readOnly = true)
    public List<com.example.comptable.demande.web.dto.DemandePieceClientDto> listerPourClient(UUID entrepriseId, StatutDemandePiece statut,
                                                                                               UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<DemandePiece> demandes = statut != null
                ? demandePieceRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(entrepriseId, statut)
                : demandePieceRepository.findByEntrepriseIdOrderByCreatedAtDesc(entrepriseId);
        return demandes.stream().filter(d -> d.getStatut() != StatutDemandePiece.ANNULEE)
                .map(com.example.comptable.demande.web.dto.DemandePieceClientDto::from).toList();
    }

    /** Ajoute le dernier message de chaque demande, charge EN UNE requete (pas de requete par ligne). */
    public List<DemandePieceDto> enrichir(List<DemandePiece> demandes) {
        if (demandes.isEmpty()) {
            return List.of();
        }
        Map<UUID, DemandePieceDto.DernierMessageDto> derniers = new java.util.HashMap<>();
        for (Object[] ligne : outboundMessageRepository.derniersMessages(demandes.stream().map(DemandePiece::getId).toList())) {
            com.example.comptable.collecte.domain.OutboundMessage m = (com.example.comptable.collecte.domain.OutboundMessage) ligne[1];
            derniers.putIfAbsent((UUID) ligne[0], new DemandePieceDto.DernierMessageDto(m.getId(), m.getStatus().name(), m.getKind().name(),
                    m.getAttemptCount(), m.getSentAt(), m.getLastError(),
                    m.getStatus() == com.example.comptable.collecte.domain.StatutMessage.PENDING ? m.getNextAttemptAt() : null));
        }
        return demandes.stream().map(d -> DemandePieceDto.from(d).avecDernierMessage(derniers.get(d.getId()))).toList();
    }

    @Transactional
    public DemandePieceDto creer(UUID entrepriseId, CreerDemandePieceRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        DemandePiece demande = new DemandePiece();
        demande.setEntreprise(entreprise);
        demande.setLibelle(request.libelle());
        demande.setDescription(request.description());
        demande.setDateLimite(request.dateLimite());
        demande.setDemandeePar(utilisateurRepository.getReferenceById(principal.getId()));
        boolean envoyer = Boolean.TRUE.equals(request.envoyer());
        if (envoyer) {
            demande.setStatut(StatutDemandePiece.A_ENVOYER);
        }
        DemandePiece enregistree = demandePieceRepository.save(demande);
        if (envoyer) {
            collecteService.enfiler(entreprise, List.of(enregistree), com.example.comptable.collecte.domain.TypeMessage.INITIAL);
        }
        DemandePieceDto dto = DemandePieceDto.from(enregistree);
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(),
                "DEMANDE_CREEE", "DEMANDE_PIECE", dto.id().toString(), Map.of("libelle", dto.libelle()));
        return dto;
    }

    /**
     * Variante de {@link #creer} pour l'agent comptable autonome (outil
     * {@code demander_clarification_client}, cf. agent.tools.AgentToolsService) : aucun
     * {@link UtilisateurPrincipal} n'existe (personne n'est connecte), donc pas de controle
     * d'acces via {@link EntrepriseAccessService} ni de {@code demandeePar} - meme principe que
     * {@code DocumentService.ingererDepuisCanalExterne}/{@code ingererAvecExtractionExterne}
     * pour les canaux non interactifs. {@code demandeePar} reste null : aucun collaborateur du
     * cabinet n'est a l'origine de cette demande, {@code notifierComptable}-like logic (cf.
     * RelancePieceService) l'ignore deja proprement dans ce cas.
     */
    @Transactional
    public DemandePieceDto creerDepuisAgent(UUID entrepriseId, String libelle, String description) {
        return creerSansPrincipal(entrepriseId, libelle, description, null);
    }

    /**
     * Variante pour la boucle de resolution WhatsApp (mission P0, section 13) : demande creee a
     * partir d'un {@code Finding} de type preuve manquante (cf.
     * {@code agent.finding.WhatsAppResolutionService}), jamais directement par le Missing
     * Evidence Engine lui-meme - "le moteur produit d'abord Finding + RecommendedAction, puis
     * Java decide" (section 12).
     */
    @Transactional
    public DemandePieceDto creerDepuisFinding(UUID entrepriseId, UUID findingId, String libelle, String description) {
        return creerSansPrincipal(entrepriseId, libelle, description, findingId);
    }

    private DemandePieceDto creerSansPrincipal(UUID entrepriseId, String libelle, String description, UUID findingId) {
        Entreprise entreprise = entrepriseRepository.findById(entrepriseId)
                .orElseThrow(() -> new IllegalArgumentException("Entreprise introuvable : " + entrepriseId));

        DemandePiece demande = new DemandePiece();
        demande.setEntreprise(entreprise);
        demande.setLibelle(libelle);
        demande.setDescription(description);
        demande.setFindingId(findingId);
        return DemandePieceDto.from(demandePieceRepository.save(demande));
    }

    /**
     * Cycle de collecte : constat {@code MISSING_DOCUMENT} -&gt; demande -&gt; MESSAGE reel (boite d'envoi) -&gt; relances -&gt; piece recue
     * -&gt; rapprochee, constat resolu. Une seule demande ouverte par ecriture (409 sinon).
     */
    @Transactional
    public DemandePieceDto demanderPourConstat(UUID entrepriseId, UUID findingId, DemanderPieceRequest request, UtilisateurPrincipal principal) {
        com.example.comptable.collecte.service.CollecteService.ResultatDemande r = collecteService.demander(entrepriseId, List.of(findingId), principal);
        if (r.demandeIds().isEmpty()) {
            Finding finding = findingRepository.findById(findingId).filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Constat introuvable"));
            if (ControlCodes.MISSING_DOCUMENT.equals(finding.getControlCode()) && finding.getStatus() == StatutFinding.OPEN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Une demande de piece existe deja pour cette operation");
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Seul un constat de piece manquante ouvert, portant sur une ecriture, peut donner lieu a une demande de piece");
        }
        DemandePiece demande = demandePieceRepository.findById(r.demandeIds().get(0)).orElseThrow();
        if (request != null) {
            demande.setDateLimite(request.dateLimite());
            if (request.canal() != null) {
                demande.setCanalPrefere(request.canal());
            }
            if (request.message() != null && !request.message().isBlank()) {
                demande.setDescription(request.message());
            }
            demandePieceRepository.save(demande);
        }
        return DemandePieceDto.from(demande);
    }

    @Transactional
    public DemandePieceDto annuler(UUID entrepriseId, UUID demandeId, UtilisateurPrincipal principal) {
        return DemandePieceDto.from(collecteService.annuler(entrepriseId, demandeId, principal));
    }

    /** Le client (ou un collaborateur pour son compte) depose la piece directement sur la demande. */
    @Transactional
    public DemandePieceDto repondre(UUID entrepriseId, UUID demandeId, TypeDocument typeDocument,
                                     MultipartFile fichier, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DemandePiece demande = charger(entrepriseId, demandeId);

        if (demande.getStatut() != StatutDemandePiece.EN_ATTENTE && demande.getStatut() != StatutDemandePiece.A_ENVOYER
                && demande.getStatut() != StatutDemandePiece.ECHEC_ENVOI) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette demande a deja ete traitee");
        }

        DocumentDto document = documentService.uploader(entrepriseId, typeDocument, fichier, principal);

        demande.setDocumentId(document.id());
        demande.setStatut(StatutDemandePiece.RECU);
        demande.setResolueAt(OffsetDateTime.now());
        demande = demandePieceRepository.save(demande);
        if (demande.getEcritureId() != null) {
            // La piece repond a une operation precise : elle en devient le justificatif (constat ferme, demande rapprochee).
            UUID ecritureId = demande.getEcritureId();
            documentRepository.findById(document.id()).ifPresent(d -> pieceEcritureLinker.lier(d, ecritureId));
        }

        if (demande.getDemandeePar() != null) {
            String contenu = "Pièce reçue pour \"" + demande.getLibelle() + "\" (" + entreprise.getRaisonSociale() + ")";
            notificationService.notifier(demande.getDemandeePar(), TypeNotification.PIECE_RECUE, contenu);
        }
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(),
                "DEMANDE_COMPLETEE", "DEMANDE_PIECE", demandeId.toString(),
                Map.of("documentId", String.valueOf(document.id())));

        return DemandePieceDto.from(demande);
    }

    private DemandePiece charger(UUID entrepriseId, UUID demandeId) {
        DemandePiece demande = demandePieceRepository.findById(demandeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande introuvable"));
        if (!demande.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande introuvable");
        }
        return demande;
    }
}
