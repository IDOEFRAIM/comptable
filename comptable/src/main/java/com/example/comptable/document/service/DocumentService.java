package com.example.comptable.document.service;

import com.example.comptable.agent.imputation.ImputationSuggestionBuilder;
import com.example.comptable.agent.fraude.AlerteFraude;
import com.example.comptable.agent.fraude.AnalyseFraudeService;
import com.example.comptable.agent.fraude.RapportFraude;
import com.example.comptable.agent.whatsapp.dto.AlerteFraudeDTO;
import com.example.comptable.agent.whatsapp.dto.DonneesExtraitesDTO;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.ocr.OcrExtractionService;
import com.example.comptable.document.ocr.OcrResult;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.storage.DocumentStorageService;
import com.example.comptable.document.storage.StoredFile;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final long TAILLE_MAX_OCTETS = 10L * 1024 * 1024;
    private static final Set<String> CONTENT_TYPES_AUTORISES = Set.of("application/pdf", "image/jpeg", "image/png");

    private final EntrepriseAccessService entrepriseAccessService;
    private final EntrepriseRepository entrepriseRepository;
    private final DocumentRepository documentRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final DocumentStorageService storageService;
    private final OcrExtractionService ocrExtractionService;
    private final AnalyseFraudeService analyseFraudeService;
    private final ImputationSuggestionBuilder imputationSuggestionBuilder;
    private final ObjectMapper objectMapper;
    private final DocumentPipeline documentPipeline;

    /** Mission P0, desactive par defaut comme les autres briques IA de ce projet
     * (agent.vlm.enabled, agent.matching-ia.enabled...) : construit une ImputationSuggestion apres
     * extraction reussie, sur les deux canaux (comme le controleur de fraude ci-dessus), mais ne
     * declenche jamais elle-meme une comptabilisation reelle (reservee au Niveau 3, verifie
     * uniquement dans agent.pipeline.EcritureNode). */
    @Value("${agent.accounting-proposal.enabled:false}")
    private boolean accountingProposalActif;

    @Transactional
    public DocumentDto uploader(UUID entrepriseId, TypeDocument typeDocument, MultipartFile fichier,
                                 UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        validerFichier(fichier);

        byte[] contenu;
        try {
            contenu = fichier.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier illisible");
        }

        validerSignature(contenu, fichier.getContentType());
        Utilisateur deposePar = utilisateurRepository.getReferenceById(principal.getId());
        Document document = creerEtStockerDocument(entreprise, typeDocument, CanalSource.WEB, deposePar,
                contenu, fichier.getOriginalFilename(), fichier.getContentType());
        return DocumentDto.from(document);
    }

    /**
     * Point d'entree pour les canaux non interactifs (WhatsApp, email...) : aucun
     * {@link UtilisateurPrincipal} n'existe puisque personne n'est connecte au portail. Le
     * controle d'acces n'est donc pas {@link EntrepriseAccessService#verifierAcces}, mais la
     * resolution en amont (par l'appelant) d'une entreprise a partir d'une identite deja fiable
     * pour ce canal (ex: numero WhatsApp lie a un compte CLIENT existant) - voir
     * {@code WhatsAppIngestionService}. {@code deposePar} reste null : aucun utilisateur du
     * systeme n'a physiquement depose la piece.
     */
    @Transactional
    public DocumentDto ingererDepuisCanalExterne(UUID entrepriseId, TypeDocument typeDocument, CanalSource canal,
                                                  byte[] contenu, String nomFichierOriginal, String contentType) {
        Entreprise entreprise = entrepriseRepository.findById(entrepriseId)
                .orElseThrow(() -> new IllegalArgumentException("Entreprise introuvable : " + entrepriseId));
        validerContenu(contenu, contentType);
        validerSignature(contenu, contentType);                  // canaux ouverts au public (lien de dépôt, WhatsApp) : le contenu réel doit être celui annoncé

        Document document = creerEtStockerDocument(entreprise, typeDocument, canal, null,
                contenu, nomFichierOriginal, contentType);
        return DocumentDto.from(document);
    }

    /**
     * Variante de {@link #ingererDepuisCanalExterne} pour le microservice IA (ai-service,
     * FastAPI, cf. {@code WhatsAppInternalIngestionController}) : l'extraction VLM et l'analyse
     * de fraude visuelle ont deja ete calculees cote Python (memes prompts que
     * {@code VlmOcrExtractionService}/{@code AnalyseFraudeService.analyserVisuellement}, portes
     * verbatim) - on ne rappelle donc ni {@link #ocrExtractionService} ni l'analyse visuelle
     * d'{@link #analyseFraudeService}, seulement ses controles deterministes
     * ({@link AnalyseFraudeService#alertesDeterministes}), qui necessitent la base de donnees
     * (doublon) et l'entreprise (regime de TVA) donc ne peuvent pas etre calcules cote Python.
     */
    @Transactional
    public DocumentDto ingererAvecExtractionExterne(UUID entrepriseId, TypeDocument typeDocument, CanalSource canal,
                                                      byte[] contenu, String nomFichierOriginal, String contentType,
                                                      DonneesExtraitesDTO donnees, List<AlerteFraudeDTO> alertesVisuelles) {
        Entreprise entreprise = entrepriseRepository.findById(entrepriseId)
                .orElseThrow(() -> new IllegalArgumentException("Entreprise introuvable : " + entrepriseId));
        validerContenu(contenu, contentType);

        StoredFile stocke;
        try {
            stocke = storageService.stocker(entreprise.getTenant().getId(), entreprise.getId(),
                    nomFichierOriginal, new ByteArrayInputStream(contenu));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Echec du stockage du fichier");
        }

        Document document = new Document();
        document.setTenant(entreprise.getTenant());
        document.setEntreprise(entreprise);
        document.setDeposePar(null);
        document.setTypeDocument(typeDocument);
        document.setCanalSource(canal);
        document.setNomFichierOriginal(nomFichierOriginal);
        document.setContentType(contentType);
        document.setTailleOctets(stocke.tailleOctets());
        document.setCheminFichier(stocke.cheminFichier());

        document.setContentHash(ContentHasher.sha256(contenu));
        appliquerExtractionExterne(document, donnees, alertesVisuelles);

        Document enregistre = documentRepository.save(document);
        documentPipeline.apresReception(enregistre);
        return DocumentDto.from(enregistre);
    }

    public List<DocumentDto> lister(UUID entrepriseId, StatutDocument statut, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Document> documents = statut != null
                ? documentRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(entrepriseId, statut)
                : documentRepository.findByEntrepriseIdOrderByCreatedAtDesc(entrepriseId);
        return documents.stream().map(DocumentDto::from).toList();
    }

    /** Sert le fichier original (facture/reçu) pour affichage - ex: volet gauche de l'inbox de
     * validation (Sprint 4). */
    @Transactional(readOnly = true)
    public FichierDocument telecharger(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = documentRepository.findById(documentId)
                .filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        Resource resource = storageService.charger(document.getCheminFichier());
        return new FichierDocument(resource, document.getContentType(), document.getNomFichierOriginal());
    }

    public record FichierDocument(Resource resource, String contentType, String nomFichier) {
    }

    private Document creerEtStockerDocument(Entreprise entreprise, TypeDocument typeDocument, CanalSource canal,
                                             Utilisateur deposePar, byte[] contenu, String nomFichierOriginal,
                                             String contentType) {
        StoredFile stocke;
        try {
            stocke = storageService.stocker(entreprise.getTenant().getId(), entreprise.getId(),
                    nomFichierOriginal, new ByteArrayInputStream(contenu));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Echec du stockage du fichier");
        }

        Document document = new Document();
        document.setTenant(entreprise.getTenant());
        document.setEntreprise(entreprise);
        document.setDeposePar(deposePar);
        document.setTypeDocument(typeDocument);
        document.setCanalSource(canal);
        document.setNomFichierOriginal(nomFichierOriginal);
        document.setContentType(contentType);
        document.setTailleOctets(stocke.tailleOctets());
        document.setCheminFichier(stocke.cheminFichier());

        document.setContentHash(ContentHasher.sha256(contenu));
        appliquerExtraction(document, contenu, contentType);

        Document enregistre = documentRepository.save(document);
        documentPipeline.apresReception(enregistre);
        return enregistre;
    }

    private void appliquerExtraction(Document document, byte[] contenu, String contentType) {
        OcrResult resultat = ocrExtractionService.extraire(contenu, contentType);
        document.setStatut(resultat.reussite() ? StatutDocument.TRAITE : StatutDocument.ERREUR);
        document.setMontantHt(resultat.montantHt());
        document.setMontantTva(resultat.montantTva());
        document.setMontantTtc(resultat.montantTtc());
        document.setDatePiece(resultat.datePiece());
        Map<String, Object> donneesOcr = new HashMap<>();
        donneesOcr.put("message", resultat.message());
        donneesOcr.put("confiance", resultat.confiance());
        donneesOcr.put("fournisseur", resultat.fournisseur());
        document.setNumeroFacture(resultat.numeroFacture());
        document.setDevise(resultat.devise());
        donneesOcr.put("numeroFacture", resultat.numeroFacture());
        donneesOcr.put("devise", resultat.devise());
        donneesOcr.put("moteur", ocrExtractionService.nomMoteur());
        donneesOcr.put("champs", champsExtraits(document, ocrExtractionService.nomMoteur(), resultat.confiance(), resultat.fournisseur()));
        document.setDonneesOcr(donneesOcr);

        if (resultat.reussite()) {
            appliquerAnalyseFraude(document, resultat, contenu, contentType);
            proposerComptabilisation(document);
        }
    }

    /** Mission P0 : proposition de comptabilisation deterministe (jamais un appel LLM), voir
     * {@link ImputationSuggestionBuilder}. Ne remplace jamais {@link #appliquerAnalyseFraude} ni
     * n'applique quoi que ce soit automatiquement - seul {@code agent.pipeline.EcritureNode}
     * decide d'auto-appliquer une proposition, et uniquement pour le canal WhatsApp (Niveau 3). */
    private void proposerComptabilisation(Document document) {
        if (accountingProposalActif) {
            imputationSuggestionBuilder.suggerer(document.getEntreprise(), document);
        }
    }

    /**
     * Controleur de fraude et d'anomalies fiscales (Sprint 7) : execute pour les deux canaux
     * (upload web et canaux externes), directement ici plutot que dans le seul graphe
     * LangGraph4j (agent.pipeline) - ce dernier ne demarre aujourd'hui que pour les pieces
     * ingerees par WhatsApp, ce qui aurait laisse les uploads web sans aucun controle. Une
     * piece au score CRITIQUE bascule immediatement en revue humaine obligatoire, quel que soit
     * le canal : voir {@link com.example.comptable.agent.pipeline.AnalyseFraudeNode}, qui relit
     * ce verdict deja calcule au lieu de le recalculer pour les pieces qui transitent ensuite
     * par le graphe.
     */
    private void appliquerAnalyseFraude(Document document, OcrResult resultat, byte[] contenu, String contentType) {
        RapportFraude rapport = analyseFraudeService.analyser(document, resultat, contenu, contentType);
        document.setRapportFraude(objectMapper.convertValue(rapport, new TypeReference<Map<String, Object>>() {
        }));
        if (rapport.estCritique()) {
            document.setStatut(StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);
        }
    }

    /** Pendant d'{@link #appliquerExtraction} pour le microservice IA : memes effets sur
     * {@link Document} (statut, montants, donneesOcr, rapportFraude), a partir d'une extraction
     * et d'alertes visuelles deja calculees plutot que d'un appel local a
     * {@link #ocrExtractionService}/{@link #analyseFraudeService}. */
    private void appliquerExtractionExterne(Document document, DonneesExtraitesDTO donnees,
                                             List<AlerteFraudeDTO> alertesVisuelles) {
        OcrResult resultat = versOcrResult(donnees);
        document.setStatut(resultat.reussite() ? StatutDocument.TRAITE : StatutDocument.ERREUR);
        document.setMontantHt(resultat.montantHt());
        document.setMontantTva(resultat.montantTva());
        document.setMontantTtc(resultat.montantTtc());
        document.setDatePiece(resultat.datePiece());

        Map<String, Object> donneesOcr = new HashMap<>();
        donneesOcr.put("message", resultat.message());
        donneesOcr.put("confiance", resultat.confiance());
        donneesOcr.put("fournisseur", donnees.fournisseur());
        donneesOcr.put("devise", donnees.devise());
        donneesOcr.put("modePaiement", donnees.modePaiement());
        document.setNumeroFacture(donnees.numeroFacture());
        document.setDevise(donnees.devise());
        donneesOcr.put("numeroFacture", donnees.numeroFacture());
        donneesOcr.put("moteur", "ai-service");
        donneesOcr.put("champs", champsExtraits(document, "ai-service", resultat.confiance(), donnees.fournisseur()));
        document.setDonneesOcr(donneesOcr);

        if (resultat.reussite()) {
            List<AlerteFraude> alertes = new ArrayList<>(analyseFraudeService.alertesDeterministes(document, resultat));
            versAlertesFraude(alertesVisuelles).forEach(alertes::add);
            RapportFraude rapport = RapportFraude.depuisAlertes(alertes);
            document.setRapportFraude(objectMapper.convertValue(rapport, new TypeReference<Map<String, Object>>() {
            }));
            if (rapport.estCritique()) {
                document.setStatut(StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);
            }
            proposerComptabilisation(document);
        }
    }

    /** {@code confiance <= 0.0} signale un echec cote ai-service (piece illisible, panne du
     * modele...) exactement comme {@link OcrResult#echec}, meme convention que le moteur local. */
    private OcrResult versOcrResult(DonneesExtraitesDTO donnees) {
        boolean reussite = donnees.confiance() > 0.0;
        if (!reussite) {
            return OcrResult.echec("Piece illisible ou extraction indisponible (microservice IA).");
        }
        return new OcrResult(donnees.montantHt(), donnees.montantTva(), donnees.montantTtc(),
                donnees.datePiece(), true, "Extraction par IA (microservice) ; a verifier avant validation.",
                donnees.confiance(), donnees.fournisseur());
    }

    private List<AlerteFraude> versAlertesFraude(List<AlerteFraudeDTO> dtos) {
        return dtos.stream().map(AlerteFraudeDTO::versAlerteFraude).toList();
    }

    /**
     * Provenance de chaque champ extrait : {@code valeur}, {@code confiance} (celle du moteur pour la piece, les moteurs
     * actuels n'en donnent pas une par champ) et {@code source}. Un champ non lu n'apparait pas.
     */
    private Map<String, Object> champsExtraits(Document document, String moteur, double confiance, String fournisseur) {
        Map<String, Object> champs = new HashMap<>();
        ajouterChamp(champs, "fournisseur", fournisseur, confiance, moteur);
        ajouterChamp(champs, "numeroFacture", document.getNumeroFacture(), confiance, moteur);
        ajouterChamp(champs, "datePiece", document.getDatePiece(), confiance, moteur);
        ajouterChamp(champs, "devise", document.getDevise(), confiance, moteur);
        ajouterChamp(champs, "montantHt", document.getMontantHt(), confiance, moteur);
        ajouterChamp(champs, "montantTva", document.getMontantTva(), confiance, moteur);
        ajouterChamp(champs, "montantTtc", document.getMontantTtc(), confiance, moteur);
        return champs;
    }

    private void ajouterChamp(Map<String, Object> champs, String nom, Object valeur, double confiance, String source) {
        if (valeur != null) {
            champs.put(nom, Map.of("valeur", String.valueOf(valeur), "confiance", confiance, "source", source != null ? source : "inconnu"));
        }
    }

    private void validerFichier(MultipartFile fichier) {
        if (fichier.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier vide");
        }
        validerTailleEtType(fichier.getSize(), fichier.getContentType());
    }

    private void validerContenu(byte[] contenu, String contentType) {
        if (contenu == null || contenu.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier vide");
        }
        validerTailleEtType(contenu.length, contentType);
    }

    /**
     * Le contenu réel doit correspondre au type annoncé : un exécutable ou une page HTML renommés en .png / .pdf sont refusés (415). Contrôle des octets de tête (« signature »), sans bibliothèque :
     * PDF « %PDF- » dans le premier kilo-octet, JPEG FF D8 FF, PNG 89 50 4E 47 0D 0A 1A 0A.
     */
    static void validerSignature(byte[] contenu, String contentType) {
        boolean conforme = switch (contentType == null ? "" : contentType) {
            case "image/png" -> commencePar(contenu, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> commencePar(contenu, 0xFF, 0xD8, 0xFF);
            case "application/pdf" -> contientEnTete(contenu, "%PDF-", 1024);
            default -> false;
        };
        if (!conforme) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Le contenu du fichier ne correspond pas à son type (PDF, JPEG ou PNG authentiques uniquement)");
        }
    }

    private static boolean commencePar(byte[] contenu, int... octets) {
        if (contenu == null || contenu.length < octets.length) {
            return false;
        }
        for (int i = 0; i < octets.length; i++) {
            if ((contenu[i] & 0xFF) != octets[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contientEnTete(byte[] contenu, String motif, int fenetre) {
        if (contenu == null) {
            return false;
        }
        String tete = new String(contenu, 0, Math.min(contenu.length, fenetre), java.nio.charset.StandardCharsets.ISO_8859_1);
        return tete.contains(motif);
    }

    private void validerTailleEtType(long taille, String contentType) {
        if (taille > TAILLE_MAX_OCTETS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Fichier trop volumineux (10 Mo max)");
        }
        if (!CONTENT_TYPES_AUTORISES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Type de fichier non supporte (PDF, JPEG, PNG uniquement)");
        }
    }
}
