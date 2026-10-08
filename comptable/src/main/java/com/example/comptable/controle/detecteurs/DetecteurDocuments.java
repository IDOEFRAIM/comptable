package com.example.comptable.controle.detecteurs;

import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.QualiteClassification;
import com.example.comptable.document.domain.StatutRapprochementDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.repository.DocumentRepository.EcartMontantRow;
import com.example.comptable.document.service.DocumentMatcher;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Constats DOCUMENTAIRES (Phase C, lots C5 et C16). Un constat = une decision du comptable ; au-dela de
 * {@link #SEUIL_AGREGATION} constats d'un meme code, un seul constat resume (les identifiants restent dans
 * {@code relatedEntityIds}).
 * <ul>
 *   <li>{@code EXACT_DUPLICATE} : meme fichier (SHA-256) - CERTAIN ;</li>
 *   <li>{@code DUPLICATE_INVOICE} : meme tiers, meme numero de facture normalise - CERTAIN ;</li>
 *   <li>{@code POSSIBLE_DUPLICATE} : meme tiers, meme TTC, dates proches, numero absent d'un cote - SUSPICION ;</li>
 *   <li>{@code DOCUMENT_UNREADABLE}, {@code DOCUMENT_AMBIGUOUS}, {@code DOCUMENT_AMOUNT_MISMATCH}, {@code DOCUMENT_UNMATCHED}.</li>
 * </ul>
 * La piece originale (la plus ancienne du groupe) n'est jamais signalee : le constat porte sur la ou les pieces recues apres.
 * Une piece marquee doublon par un humain sort du perimetre : son constat se ferme, et un constat ecarte ne reapparait pas.
 */
@Component
public class DetecteurDocuments implements ControlDetector {

    static final int SEUIL_AGREGATION = 20;
    private static final int MAX_IDS_LIES = 50;

    private final DocumentRepository documentRepository;
    private final DocumentMatcher matcher;
    private final int fenetreDoublonJours;
    private final int joursNonRapproche;

    public DetecteurDocuments(DocumentRepository documentRepository, DocumentMatcher matcher,
                              @Value("${agent.fraude.doublon.fenetre-jours:3}") int fenetreDoublonJours,
                              @Value("${documents.non-rapproche-jours:7}") int joursNonRapproche) {
        this.documentRepository = documentRepository;
        this.matcher = matcher;
        this.fenetreDoublonJours = fenetreDoublonJours;
        this.joursNonRapproche = joursNonRapproche;
    }

    @Override
    public String nom() {
        return "documents";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.EXACT_DUPLICATE, ControlCodes.DUPLICATE_INVOICE, ControlCodes.POSSIBLE_DUPLICATE,
                ControlCodes.DOCUMENT_UNREADABLE, ControlCodes.DOCUMENT_AMBIGUOUS, ControlCodes.DOCUMENT_UNMATCHED,
                ControlCodes.DOCUMENT_AMOUNT_MISMATCH);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<ControlResult> resultats = new ArrayList<>();
        UUID entrepriseId = entreprise.getId();
        Set<UUID> dejaSignales = new HashSet<>();

        List<Constat> exacts = doublonsExacts(entrepriseId);
        exacts.forEach(c -> dejaSignales.add(c.document.getId()));
        List<Constat> factures = new ArrayList<>();
        for (Constat c : doublonsDeFacture(entrepriseId)) {
            if (dejaSignales.add(c.document.getId())) {
                factures.add(c);
            }
        }
        List<Constat> suspects = new ArrayList<>();
        for (Constat c : doublonsSuspects(entrepriseId)) {
            if (dejaSignales.add(c.document.getId())) {
                suspects.add(c);
            }
        }

        emettre(resultats, entreprise, ControlCodes.EXACT_DUPLICATE, ControlSeverity.WARNING, exacts,
                "Fichier deja recu", "Doublons de fichier", "Marquer comme doublon (l'original est conserve).");
        emettre(resultats, entreprise, ControlCodes.DUPLICATE_INVOICE, ControlSeverity.WARNING, factures,
                "Facture deja recue", "Factures recues en double", "Verifier puis marquer comme doublon, ou ecarter si ce sont deux factures distinctes.");
        emettre(resultats, entreprise, ControlCodes.POSSIBLE_DUPLICATE, ControlSeverity.INFO, suspects,
                "Doublon possible", "Doublons possibles", "Comparer les deux pieces : marquer comme doublon, ou ecarter si elles sont distinctes.");

        List<Constat> illisibles = documentRepository.rechercherIllisibles(entrepriseId).stream()
                .map(d -> new Constat(d, null, "Cette piece n'a pas pu etre lue (" + messageExtraction(d) + ") : saisie manuelle necessaire."))
                .toList();
        emettre(resultats, entreprise, ControlCodes.DOCUMENT_UNREADABLE, ControlSeverity.WARNING, illisibles,
                "Piece illisible", "Pieces illisibles", "Saisir les donnees a la main ou redeposer une meilleure copie.");

        List<Constat> ambigus = new ArrayList<>();
        for (Document d : documentRepository.rechercherAmbigus(entrepriseId)) {
            ambigus.add(constatAmbigu(d));
        }
        emettre(resultats, entreprise, ControlCodes.DOCUMENT_AMBIGUOUS, ControlSeverity.WARNING, ambigus,
                "Piece ambigue", "Pieces ambigues", "Choisir le bon tiers ou la bonne facture : le systeme ne tranche jamais.");

        OffsetDateTime avant = OffsetDateTime.now().minusDays(joursNonRapproche);
        List<Constat> nonRapproches = documentRepository.rechercherNonRapprochesAnciens(entrepriseId, avant).stream()
                .map(d -> new Constat(d, null, "Cette facture est recue depuis plus de " + joursNonRapproche
                        + " jours, sans facture associee ni ecriture : a comptabiliser ou a rapprocher."))
                .toList();
        emettre(resultats, entreprise, ControlCodes.DOCUMENT_UNMATCHED, ControlSeverity.INFO, nonRapproches,
                "Piece non rapprochee", "Pieces non rapprochees", "Comptabiliser la piece ou l'associer a la facture correspondante.");

        for (EcartMontantRow r : documentRepository.rechercherEcartsDeMontant(entrepriseId)) {
            Document d = documentRepository.findById(r.documentId()).orElse(null);
            if (d == null) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("numeroFacture", r.numeroFacture());
            metadata.put("montantDocument", r.montantDocument());
            metadata.put("montantFacture", r.montantFacture());
            metadata.put("factureId", r.factureId().toString());
            resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_AMOUNT_MISMATCH, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                    entreprise.getId(), "DOCUMENT", d.getId().toString(), "Montant different de la facture : " + nom(d),
                    "La piece porte le numero " + r.numeroFacture() + " d'une facture deja enregistree pour ce fournisseur, mais pour "
                            + r.montantDocument().toPlainString() + " au lieu de " + r.montantFacture().toPlainString() + ".",
                    List.of(r.factureId().toString()), metadata,
                    "Verifier le montant de la piece (extraction) ou de la facture, puis associer ou corriger."));
        }
        return resultats;
    }

    // ------------------------------------------------------------------------------------------ groupes de doublons

    private record Constat(Document document, Document original, String explication) {
    }

    private List<Constat> doublonsExacts(UUID entrepriseId) {
        return parGroupe(documentRepository.rechercherDoublonsExacts(entrepriseId), Document::getContentHash,
                (d, o) -> "Ce fichier est strictement identique (meme empreinte SHA-256) a " + nom(o) + ", recu le "
                        + o.getCreatedAt().toLocalDate() + ".");
    }

    private List<Constat> doublonsDeFacture(UUID entrepriseId) {
        return parGroupe(documentRepository.rechercherFacturesEnDouble(entrepriseId), d -> d.getTiersId() + "#" + d.getNumeroFactureNormalise(),
                (d, o) -> "Meme fournisseur et meme numero de facture (" + d.getNumeroFacture() + ") que " + nom(o) + ", recu le "
                        + o.getCreatedAt().toLocalDate() + ".");
    }

    private List<Constat> doublonsSuspects(UUID entrepriseId) {
        List<Constat> constats = new ArrayList<>();
        Map<String, List<Document>> groupes = new LinkedHashMap<>();
        for (Document d : documentRepository.rechercherDoublonsSuspects(entrepriseId)) {
            groupes.computeIfAbsent(d.getTiersId() + "#" + d.getMontantTtc().toPlainString(), k -> new ArrayList<>()).add(d);
        }
        for (List<Document> groupe : groupes.values()) {
            groupe.sort(java.util.Comparator.comparing(Document::getCreatedAt).thenComparing(Document::getId));
            for (int i = 1; i < groupe.size(); i++) {
                Document d = groupe.get(i);
                for (int j = 0; j < i; j++) {
                    Document o = groupe.get(j);
                    boolean numerosDistincts = d.getNumeroFactureNormalise() != null && o.getNumeroFactureNormalise() != null
                            && !d.getNumeroFactureNormalise().equals(o.getNumeroFactureNormalise());
                    boolean datesProches = d.getDatePiece() == null || o.getDatePiece() == null
                            || Math.abs(ChronoUnit.DAYS.between(d.getDatePiece(), o.getDatePiece())) <= fenetreDoublonJours;
                    boolean memeNumero = d.getNumeroFactureNormalise() != null && d.getNumeroFactureNormalise().equals(o.getNumeroFactureNormalise());
                    if (!numerosDistincts && !memeNumero && datesProches) {
                        constats.add(new Constat(d, o, "Meme fournisseur, meme montant TTC (" + d.getMontantTtc().toPlainString()
                                + ") et dates proches que " + nom(o) + ", sans numero de facture pour les distinguer : suspicion de doublon, pas une certitude."));
                        break;
                    }
                }
            }
        }
        return constats;
    }

    /** Dans chaque groupe de meme cle, la piece la plus ancienne est l'original ; les suivantes sont les constats. */
    private List<Constat> parGroupe(List<Document> documents, Function<Document, String> cle,
                                    java.util.function.BiFunction<Document, Document, String> explication) {
        Map<String, List<Document>> groupes = new LinkedHashMap<>();
        for (Document d : documents) {
            groupes.computeIfAbsent(cle.apply(d), k -> new ArrayList<>()).add(d);
        }
        List<Constat> constats = new ArrayList<>();
        for (List<Document> groupe : groupes.values()) {
            groupe.sort(java.util.Comparator.comparing(Document::getCreatedAt).thenComparing(Document::getId));
            Document original = groupe.get(0);
            for (int i = 1; i < groupe.size(); i++) {
                constats.add(new Constat(groupe.get(i), original, explication.apply(groupe.get(i), original)));
            }
        }
        return constats;
    }

    private Constat constatAmbigu(Document d) {
        if (d.getStatutClassification() == QualiteClassification.AMBIGU) {
            return new Constat(d, null, "Le fournisseur lu sur la piece (" + com.example.comptable.document.service.DocumentClassifier.nomExtrait(d)
                    + ") correspond a plusieurs tiers : choisir le bon tiers.");
        }
        List<String> candidats = d.getStatutRapprochement() == StatutRapprochementDocument.AMBIGU
                ? matcher.proposer(d).candidats().stream().map(c -> c.numero() + " (" + c.montantTtc().toPlainString() + ")").toList()
                : List.of();
        return new Constat(d, null, "Plusieurs factures correspondent a cette piece" + (candidats.isEmpty() ? "" : " : " + String.join(", ", candidats))
                + ". Le systeme ne choisit jamais la premiere.");
    }

    // ------------------------------------------------------------------------------------------------ emission

    private void emettre(List<ControlResult> resultats, Entreprise entreprise, String code, ControlSeverity severite, List<Constat> constats,
                         String titre, String titreAgrege, String action) {
        if (constats.isEmpty()) {
            return;
        }
        if (constats.size() > SEUIL_AGREGATION) {
            resultats.add(ControlResult.ouvert(code, ControlCategory.DOCUMENT, severite, entreprise.getId(), "DOCUMENTS", code,
                    constats.size() + " " + titreAgrege.toLowerCase(),
                    constats.size() + " pieces sont concernees : un seul constat resume le sujet pour ne pas noyer la revue.",
                    constats.stream().limit(MAX_IDS_LIES).map(c -> c.document.getId().toString()).toList(),
                    Map.of("nbPieces", constats.size()), action));
            return;
        }
        for (Constat c : constats) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fichier", nom(c.document));
            if (c.document.getNumeroFacture() != null) {
                metadata.put("numeroFacture", c.document.getNumeroFacture());
            }
            if (c.document.getMontantTtc() != null) {
                metadata.put("montantTtc", c.document.getMontantTtc());
            }
            if (c.original != null) {
                metadata.put("originalId", c.original.getId().toString());
            }
            resultats.add(ControlResult.ouvert(code, ControlCategory.DOCUMENT, severite, entreprise.getId(), "DOCUMENT",
                    c.document.getId().toString(), titre + " : " + nom(c.document), c.explication,
                    c.original != null ? List.of(c.original.getId().toString()) : List.of(), metadata, action));
        }
    }

    private static String nom(Document d) {
        return d.getNomFichierOriginal() != null ? d.getNomFichierOriginal() : d.getId().toString();
    }

    private static String messageExtraction(Document d) {
        Object message = d.getDonneesOcr() != null ? d.getDonneesOcr().get("message") : null;
        return message != null ? String.valueOf(message) : "extraction impossible";
    }
}
