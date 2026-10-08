package com.example.comptable.document.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.OrigineRapprochement;
import com.example.comptable.document.domain.QualiteClassification;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.StatutRapprochementDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentMatcher.Candidat;
import com.example.comptable.document.service.DocumentMatcher.Proposition;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline de traitement d'une piece apres son extraction : identification du tiers, rapprochement a une facture,
 * lien comptable. Regle : CERTAIN -&gt; automatique (tiers EXACT + facture EXACT) ; PROBABLE -&gt; propose ; AMBIGU -&gt; constat
 * (jamais la premiere candidate) ; rien -&gt; la piece attend. Aucune ecriture n'est creee ici : la comptabilisation reste
 * celle du moteur ({@code AccountingOperationEngine}).
 */
@Component
public class DocumentPipeline {

    private final DocumentClassifier classifier;
    private final DocumentMatcher matcher;
    private final PieceEcritureLinker linker;
    private final DocumentRepository documentRepository;
    private final FactureFournisseurRepository factureFournisseurRepository;
    private final AuditTrailService auditTrailService;
    private final com.example.comptable.collecte.service.DemandeMatcher demandeMatcher;

    public DocumentPipeline(DocumentClassifier classifier, DocumentMatcher matcher, PieceEcritureLinker linker,
                            DocumentRepository documentRepository, FactureFournisseurRepository factureFournisseurRepository,
                            AuditTrailService auditTrailService, com.example.comptable.collecte.service.DemandeMatcher demandeMatcher) {
        this.classifier = classifier;
        this.matcher = matcher;
        this.linker = linker;
        this.documentRepository = documentRepository;
        this.factureFournisseurRepository = factureFournisseurRepository;
        this.auditTrailService = auditTrailService;
        this.demandeMatcher = demandeMatcher;
    }

    /** Appele une fois la piece enregistree (tous canaux) : trace la reception puis lance l'analyse. */
    @Transactional
    public void apresReception(Document document) {
        Map<String, Object> details = new HashMap<>();
        details.put("canal", document.getCanalSource().name());
        details.put("type", document.getTypeDocument().name());
        details.put("contentHash", String.valueOf(document.getContentHash()));
        details.put("doublonExact", document.getContentHash() != null
                && documentRepository.existsByEntrepriseIdAndContentHashAndIdNot(document.getEntreprise().getId(), document.getContentHash(), document.getId()));
        evenement(document, "DOCUMENT_RECU", details);
        if (document.getStatut() != StatutDocument.ERREUR) {
            evenement(document, "DOCUMENT_EXTRAIT", Map.of("confiance", document.confianceExtraction(),
                    "fournisseur", String.valueOf(DocumentClassifier.nomExtrait(document)),
                    "numeroFacture", String.valueOf(document.getNumeroFacture())));
        }
        analyser(document);
        // La piece repond-elle a une demande de piece ouverte ? (certain : rattachee ; probable : proposee ; ambigu : constat)
        if (document.getStatut() != StatutDocument.ERREUR && document.getDoublonDeId() == null) {
            demandeMatcher.appliquer(document, null);
        }
    }

    /** Identifie le tiers puis tente le rapprochement. Rejouable (apres creation d'un fournisseur, par exemple). */
    @Transactional
    public void analyser(Document document) {
        if (!document.getTypeDocument().estFacture() || document.getStatut() == StatutDocument.ERREUR
                || document.getDoublonDeId() != null || document.getStatutRapprochement() == StatutRapprochementDocument.RAPPROCHE) {
            return;
        }
        DocumentClassifier.Resultat tiers = classifier.identifierTiers(document);
        document.setStatutClassification(tiers.qualite());
        document.setConfianceTiers(tiers.confiance());
        document.setTiersId(tiers.tiers() != null ? tiers.tiers().getId() : null);
        evenement(document, "DOCUMENT_CLASSE", Map.of("qualiteTiers", tiers.qualite().name(), "confianceTiers", tiers.confiance(),
                "tiersId", String.valueOf(document.getTiersId())));

        document.setStatutRapprochement(StatutRapprochementDocument.NON_RAPPROCHE);
        document.setCibleType(null);
        document.setCibleId(null);
        document.setConfianceRapprochement(null);
        // Seul un tiers EXACT permet d'aller plus loin sans humain ; un tiers PROBABLE reste une proposition a confirmer d'abord.
        // (une facture de vente se retrouve par NOTRE numero : le tiers n'est pas requis)
        if (tiers.qualite() == QualiteClassification.EXACT || document.getTypeDocument() == TypeDocument.FACTURE_VENTE) {
            Proposition proposition = matcher.proposer(document);
            switch (proposition.decision()) {
                case EXACT -> {
                    lier(document, proposition.unique(), OrigineRapprochement.SYSTEME, 1.0, null);
                    evenement(document, "DOCUMENT_RAPPROCHE", Map.of("cibleType", proposition.unique().cibleType(), "cibleId", proposition.unique().id().toString()));
                }
                case PROBABLE -> {
                    Candidat c = proposition.unique();
                    document.setStatutRapprochement(StatutRapprochementDocument.A_CONFIRMER);
                    document.setCibleType(c.cibleType());
                    document.setCibleId(c.id());
                    document.setConfianceRapprochement(0.8);
                    evenement(document, "DOCUMENT_RAPPROCHEMENT_PROPOSE", Map.of("cibleType", c.cibleType(), "cibleId", c.id().toString()));
                }
                case AMBIGUOUS -> document.setStatutRapprochement(StatutRapprochementDocument.AMBIGU);
                case UNMATCHED -> {
                }
            }
        }
        documentRepository.save(document);
    }

    /**
     * Pose l'association piece -&gt; facture et ses consequences : numero du fournisseur reporte sur la facture s'il lui manquait,
     * piece rattachee a l'ecriture de la facture quand elle existe (donc plus de piece manquante, plus de seconde comptabilisation).
     */
    @Transactional
    public void lier(Document document, Candidat cible, OrigineRapprochement origine, double confiance, UUID validePar) {
        document.setCibleType(cible.cibleType());
        document.setCibleId(cible.id());
        document.setStatutRapprochement(StatutRapprochementDocument.RAPPROCHE);
        document.setConfianceRapprochement(confiance);
        document.setRapprocheAt(OffsetDateTime.now());
        document.setRapprochePar(origine);
        if (validePar != null) {
            document.setValideAt(OffsetDateTime.now());
            document.setValidePar(validePar);
        }
        if (DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR.equals(cible.cibleType())) {
            FactureFournisseur facture = factureFournisseurRepository.findById(cible.id()).orElse(null);
            if (facture != null && facture.getNumeroFournisseur() == null && document.getNumeroFacture() != null) {
                facture.setNumeroFournisseur(document.getNumeroFacture());
                factureFournisseurRepository.save(facture);
            }
        }
        documentRepository.save(document);
        if (cible.ecritureId() != null) {
            linker.lier(document, cible.ecritureId(), validePar);
        }
    }

    private void evenement(Document document, String action, Map<String, Object> details) {
        auditTrailService.enregistrerEvenementSysteme(document.getTenant() != null ? document.getTenant().getId() : null,
                action, "DOCUMENT", document.getId().toString(), details);
    }
}
