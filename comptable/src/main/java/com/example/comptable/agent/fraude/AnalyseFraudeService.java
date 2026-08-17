package com.example.comptable.agent.fraude;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.ocr.OcrResult;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Controleur de fraude et d'anomalies fiscales : analyse chaque piece juste apres l'extraction
 * VLM/OCR et avant toute validation, pour detecter les tentatives de fraude ou erreurs fiscales
 * grossieres. Appele une seule fois par piece, de facon synchrone, par
 * {@code DocumentService.appliquerExtraction} - donc pour les deux canaux existants (upload web
 * et WhatsApp), pas seulement pour celui qui transite ensuite par le graphe LangGraph4j (cf.
 * {@code agent.pipeline.AnalyseFraudeNode}, qui relit ce verdict au lieu de le recalculer).
 * <p>
 * Deux familles de controles, executees systematiquement l'une apres l'autre :
 * <ol>
 *   <li>Deterministe (Java pur, gratuit, toujours actif) : coherence du taux de TVA, montants
 *   ronds suspects sur les achats de marchandises, doublons stricts deja enregistres. Necessite
 *   la base de donnees (doublon) et l'entreprise (regime de TVA), reste donc en Java.</li>
 *   <li>Visuelle (microservice IA, derriere {@code agent.fraude.analyse-visuelle.enabled} -
 *   desactive par defaut comme les autres fonctionnalites IA du module) : incoherences
 *   graphiques/typographiques typiques d'un recu Mobile Money falsifie. Deleguee a ai-service
 *   via {@link AiServiceClient#analyserVisuellement} - prompt, appel modele et garde-fou
 *   (seuils de confiance/gravite) vivent desormais cote Python (memes regles, portees verbatim,
 *   cf. ai-service/app/services/fraude_service.py).</li>
 * </ol>
 */
@Service
public class AnalyseFraudeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyseFraudeService.class);

    private static final Set<String> CONTENT_TYPES_ANALYSE_VISUELLE = Set.of("image/jpeg", "image/png");
    private static final Set<TypeDocument> TYPES_FACTURATION = Set.of(TypeDocument.FACTURE_ACHAT, TypeDocument.RECU);

    private final DocumentRepository documentRepository;
    private final AiServiceClient aiServiceClient;
    private final boolean analyseVisuelleActive;
    private final BigDecimal tauxTvaStandard;
    private final BigDecimal toleranceEcartTvaXof;
    private final int fenetreDoublonJours;
    private final BigDecimal multipleMontantRondXof;

    public AnalyseFraudeService(DocumentRepository documentRepository,
                                 AiServiceClient aiServiceClient,
                                 @Value("${agent.fraude.analyse-visuelle.enabled:false}") boolean analyseVisuelleActive,
                                 @Value("${agent.fraude.tva.taux-standard:0.18}") BigDecimal tauxTvaStandard,
                                 @Value("${agent.fraude.tva.tolerance-xof:200}") BigDecimal toleranceEcartTvaXof,
                                 @Value("${agent.fraude.doublon.fenetre-jours:3}") int fenetreDoublonJours,
                                 @Value("${agent.fraude.montant-rond.multiple-xof:5000}") BigDecimal multipleMontantRondXof) {
        this.documentRepository = documentRepository;
        this.aiServiceClient = aiServiceClient;
        this.analyseVisuelleActive = analyseVisuelleActive;
        this.tauxTvaStandard = tauxTvaStandard;
        this.toleranceEcartTvaXof = toleranceEcartTvaXof;
        this.fenetreDoublonJours = fenetreDoublonJours;
        this.multipleMontantRondXof = multipleMontantRondXof;
    }

    public RapportFraude analyser(Document document, OcrResult extraction, byte[] contenu, String contentType) {
        List<AlerteFraude> alertes = new ArrayList<>(alertesDeterministes(document, extraction));

        if (analyseVisuelleActive) {
            alertes.addAll(analyserVisuellement(contenu, contentType, document.getTypeDocument()));
        }

        RapportFraude rapport = RapportFraude.depuisAlertes(alertes);
        if (!alertes.isEmpty()) {
            log.info("Analyse de fraude document (entreprise {}) : score={}, {} alerte(s)",
                    document.getEntreprise().getId(), rapport.scoreRisque(), alertes.size());
        }
        return rapport;
    }

    /**
     * Sous-ensemble deterministe (TVA, montant rond, doublon) d'{@link #analyser}, sans l'appel
     * VLM. Extrait pour l'ingestion via le microservice IA (ai-service) : celui-ci a deja calcule
     * l'analyse visuelle de son cote (memes prompts, cf. fraude_service.py), la refaire ici
     * coderait un second appel modele redondant. L'appelant (DocumentService) est responsable de
     * fusionner cette liste avec les alertes visuelles fournies par ai-service avant d'appeler
     * {@link RapportFraude#depuisAlertes}.
     */
    public List<AlerteFraude> alertesDeterministes(Document document, OcrResult extraction) {
        List<AlerteFraude> alertes = new ArrayList<>();
        verifierEcartTva(extraction, document.getEntreprise()).ifPresent(alertes::add);
        verifierMontantRond(extraction, document.getTypeDocument()).ifPresent(alertes::add);
        verifierDoublon(document, extraction).ifPresent(alertes::add);
        return alertes;
    }

    /** Compare la TVA declaree au taux standard applique au HT. Les entreprises dont le regime
     * fiscal mentionne une exoneration ne sont pas controlees : l'absence de TVA y est normale,
     * pas une anomalie - on ne dispose pas ici d'une table structuree des regimes/exonerations
     * sectorielles UEMOA, seule cette detection textuelle simple est faite pour eviter les faux
     * positifs evidents. */
    private Optional<AlerteFraude> verifierEcartTva(OcrResult extraction, Entreprise entreprise) {
        if (extraction.montantHt() == null || estExonereDeTva(entreprise)) {
            return Optional.empty();
        }
        BigDecimal tvaAttendue = extraction.montantHt().multiply(tauxTvaStandard).setScale(0, RoundingMode.HALF_UP);
        BigDecimal tvaDeclaree = extraction.montantTva() != null ? extraction.montantTva() : BigDecimal.ZERO;
        BigDecimal ecart = tvaAttendue.subtract(tvaDeclaree).abs();
        if (ecart.compareTo(toleranceEcartTvaXof) > 0) {
            return Optional.of(new AlerteFraude("ECART_TVA",
                    "TVA declaree de %s XOF incoherente avec le taux standard (%s%%) applique au HT de %s XOF : TVA attendue ~%s XOF, ecart de %s XOF"
                            .formatted(tvaDeclaree, tauxTvaStandard.movePointRight(2), extraction.montantHt(), tvaAttendue, ecart),
                    NiveauRisqueFraude.MOYEN,
                    "Vérifiez le calcul de TVA avec le fournisseur avant de valider cette pièce."));
        }
        return Optional.empty();
    }

    private boolean estExonereDeTva(Entreprise entreprise) {
        String regime = entreprise.getRegimeFiscal();
        return regime != null && regime.toUpperCase(Locale.ROOT).contains("EXONER");
    }

    /** Un montant TTC parfaitement rond (multiple exact de {@code multipleMontantRondXof}) sur
     * une facture d'achat ou un recu est un indice classique de facture de complaisance -
     * jamais une preuve a lui seul, d'ou une gravite MOYEN et un motif qui invite a verifier
     * plutot qu'affirmer une fraude averee. */
    private Optional<AlerteFraude> verifierMontantRond(OcrResult extraction, TypeDocument type) {
        if (extraction.montantTtc() == null || !TYPES_FACTURATION.contains(type)) {
            return Optional.empty();
        }
        BigDecimal montant = extraction.montantTtc();
        if (montant.signum() > 0 && montant.remainder(multipleMontantRondXof).signum() == 0) {
            return Optional.of(new AlerteFraude("MONTANT_ROND_SUSPECT",
                    "Montant TTC parfaitement rond (%s XOF, multiple de %s) : a verifier, frequent sur les factures de complaisance"
                            .formatted(montant, multipleMontantRondXof),
                    NiveauRisqueFraude.MOYEN,
                    "Demandez le détail de la facture (quantités, prix unitaires) avant de comptabiliser."));
        }
        return Optional.empty();
    }

    /** Doublon strict : meme entreprise, meme montant TTC exact, date de piece dans une fenetre
     * de +/- {@code fenetreDoublonJours} autour d'une piece deja enregistree (statut different
     * d'ERREUR, donc reellement exploitee). Signal fort et peu couteux : gravite CRITIQUE. */
    private Optional<AlerteFraude> verifierDoublon(Document document, OcrResult extraction) {
        if (extraction.montantTtc() == null || extraction.datePiece() == null) {
            return Optional.empty();
        }
        LocalDate debut = extraction.datePiece().minusDays(fenetreDoublonJours);
        LocalDate fin = extraction.datePiece().plusDays(fenetreDoublonJours);
        List<Document> existants = documentRepository.rechercherDoublonsPotentiels(
                document.getEntreprise().getId(), extraction.montantTtc(), debut, fin);
        if (!existants.isEmpty()) {
            return Optional.of(new AlerteFraude("DOUBLON_POTENTIEL",
                    "Une autre piece du meme montant (%s XOF) est deja enregistree entre le %s et le %s"
                            .formatted(extraction.montantTtc(), debut, fin),
                    NiveauRisqueFraude.CRITIQUE,
                    "Comparez avec la pièce déjà enregistrée avant de valider — évitez un double paiement."));
        }
        return Optional.empty();
    }

    /** N'analyse que les images (JPEG/PNG) : le PDF, generalement une facture texte structuree,
     * ne se prete pas a un controle typographique/graphique de ce type - court-circuite avant
     * meme d'appeler ai-service, qui appliquerait de toute facon la meme regle (evite un aller-
     * retour reseau inutile). Ne leve jamais d'exception : {@link AiServiceClient#analyserVisuellement}
     * degrade deja silencieusement vers "aucune alerte visuelle" en cas de panne. */
    private List<AlerteFraude> analyserVisuellement(byte[] contenu, String contentType, TypeDocument type) {
        if (!CONTENT_TYPES_ANALYSE_VISUELLE.contains(contentType)) {
            return List.of();
        }
        String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
        return aiServiceClient.analyserVisuellement(contenu, "piece" + extension, contentType, type);
    }
}
