package com.example.comptable.agent.whatsapp;

import com.example.comptable.agent.pipeline.PieceJustificativeAgentService;
import com.example.comptable.agent.whatsapp.domain.WhatsappEvenementTraite;
import com.example.comptable.agent.whatsapp.dto.WhatsAppDocumentPayloadDTO;
import com.example.comptable.agent.whatsapp.repository.WhatsappEvenementTraiteRepository;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Point d'entree pour le microservice IA (ai-service, FastAPI), qui recoit desormais directement
 * le webhook Meta (verification de signature HMAC, telechargement du media, extraction VLM et
 * analyse visuelle de fraude - cf. ai-service/app/api/v1/endpoints/whatsapp.py), et appelle
 * {@link #ingererAvecExtractionExterne} une fois ce travail termine, via
 * {@link WhatsAppInternalIngestionController}.
 * <p>
 * Type de document par defaut {@link TypeDocument#RECU} : les pieces reçues par ce canal sont
 * majoritairement des reçus de paiement mobile (Wave, Orange Money, Moov) plutot que des
 * factures structurees ; le type reste une metadonnee de tri, corrigible par le comptable avant
 * comptabilisation, pas une decision engageante.
 */
@Service
public class WhatsAppIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppIngestionService.class);

    private final WhatsappEvenementTraiteRepository evenementTraiteRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    private final DocumentService documentService;
    private final PieceJustificativeAgentService pieceJustificativeAgentService;

    public WhatsAppIngestionService(WhatsappEvenementTraiteRepository evenementTraiteRepository,
                                     UtilisateurRepository utilisateurRepository,
                                     UtilisateurEntrepriseRepository utilisateurEntrepriseRepository,
                                     DocumentService documentService,
                                     PieceJustificativeAgentService pieceJustificativeAgentService) {
        this.evenementTraiteRepository = evenementTraiteRepository;
        this.utilisateurRepository = utilisateurRepository;
        this.utilisateurEntrepriseRepository = utilisateurEntrepriseRepository;
        this.documentService = documentService;
        this.pieceJustificativeAgentService = pieceJustificativeAgentService;
    }

    /**
     * Point d'entree pour le microservice IA (ai-service, FastAPI) : appele par
     * {@link WhatsAppInternalIngestionController} une fois qu'une piece a deja ete telechargee,
     * extraite (VLM) et analysee visuellement pour la fraude de son cote. Reutilise la
     * resolution telephone -> entreprise ({@link #resoudreEntreprise}) et l'idempotence sur
     * {@code messageId} ({@link #reclamerMessage}) - le reste (telechargement media, extraction,
     * analyse visuelle) n'est pas refait ici, il a deja ete fait cote Python.
     * <p>
     * Appelee de facon synchrone par un appelant (ai-service) qui attend une reponse HTTP : les
     * exceptions ne sont donc PAS avalees ici, elles remontent au controleur pour etre traduites
     * en erreur HTTP (ai-service logue et abandonne sans retry, cf. son README) plutot que d'etre
     * silencieusement perdues.
     */
    public Optional<DocumentDto> ingererAvecExtractionExterne(WhatsAppDocumentPayloadDTO payload,
                                                                byte[] contenu, String contentType) {
        String messageId = payload.messageId();
        if (!reclamerMessage(messageId)) {
            log.info("Message WhatsApp {} deja reclame (traite ou en cours), ignore", messageId);
            return Optional.empty();
        }

        Optional<UUID> entrepriseId = resoudreEntreprise(payload.clientPhone());
        if (entrepriseId.isEmpty()) {
            return Optional.empty();
        }

        String nomFichier = "whatsapp-" + messageId + extensionDepuisMimeType(contentType);
        DocumentDto document = documentService.ingererAvecExtractionExterne(entrepriseId.get(), TypeDocument.RECU,
                CanalSource.WHATSAPP, contenu, nomFichier, contentType, payload.donnees(), payload.alertesFraude());

        log.info("Piece WhatsApp {} ingeree via microservice IA (document {}) pour l'entreprise {}",
                messageId, document.id(), entrepriseId.get());

        // demarrer() n'expose jamais d'exception (elle logue et retourne Optional.empty()) : un
        // souci du pipeline agentique n'efface donc jamais le succes de l'ingestion elle-meme,
        // le Document reste consultable/traitable manuellement dans tous les cas.
        pieceJustificativeAgentService.demarrer(document.id(), entrepriseId.get());
        return Optional.of(document);
    }

    /** Resout l'entreprise a partir du numero WhatsApp expediteur, via le compte CLIENT existant
     * qui lui est associe. Ne devine jamais : un numero inconnu ou rattache a plusieurs
     * entreprises (cas d'un collaborateur du cabinet, non gere pour l'instant) est refuse plutot
     * que d'archiver une piece dans la mauvaise comptabilite. */
    private Optional<UUID> resoudreEntreprise(String numeroExpediteur) {
        if (numeroExpediteur == null || numeroExpediteur.isBlank()) {
            log.warn("Message WhatsApp sans numero expediteur, ignore");
            return Optional.empty();
        }
        String numeroNormalise = numeroExpediteur.replaceAll("[^0-9]", "");

        Optional<Utilisateur> utilisateur = utilisateurRepository.findByTelephoneNormalise(numeroNormalise);
        if (utilisateur.isEmpty()) {
            log.warn("Aucun utilisateur ne correspond au numero WhatsApp {} (aucune piece ingeree)", numeroExpediteur);
            return Optional.empty();
        }

        List<UtilisateurEntreprise> rattachements = utilisateurEntrepriseRepository
                .findByIdUtilisateurId(utilisateur.get().getId());
        if (rattachements.size() != 1) {
            log.warn("Le numero WhatsApp {} correspond a {} entreprise(s) (attendu: 1), piece non ingeree "
                            + "(cas non gere : expediteur collaborateur multi-entreprises)",
                    numeroExpediteur, rattachements.size());
            return Optional.empty();
        }
        return Optional.of(rattachements.get(0).getId().getEntrepriseId());
    }

    /** Insertion protegee par la contrainte unique de {@code whatsapp_evenement_traite.message_id} :
     * {@code save()} d'un repository Spring Data est deja transactionnel de par sa propre
     * implementation, pas besoin d'un {@code @Transactional} explicite ici (qui serait de toute
     * facon sans effet sur un appel interne a la meme instance - self-invocation). */
    private boolean reclamerMessage(String messageId) {
        if (evenementTraiteRepository.existsByMessageId(messageId)) {
            return false;
        }
        try {
            evenementTraiteRepository.save(new WhatsappEvenementTraite(messageId));
            return true;
        } catch (DataIntegrityViolationException e) {
            // Course concurrente entre deux livraisons du meme webhook : l'autre a gagne la
            // reclamation entre notre existsByMessageId() et notre save().
            log.debug("Message WhatsApp {} reclame entre-temps par un traitement concurrent", messageId);
            return false;
        }
    }

    private String extensionDepuisMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }
}
