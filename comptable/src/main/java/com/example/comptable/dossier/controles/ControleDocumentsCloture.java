package com.example.comptable.dossier.controles;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.dossier.web.dto.ControlItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Pieces justificatives : factures jamais comptabilisees et pieces manquantes deja signalees (lot 1.10). */
@Component
public class ControleDocumentsCloture implements ClotureControle {

    private static final List<TypeDocument> TYPES_COMPTABILISABLES = List.of(TypeDocument.FACTURE_ACHAT, TypeDocument.FACTURE_VENTE);
    private static final List<StatutDocument> STATUTS_PRETS = List.of(StatutDocument.TRAITE, StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);

    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;

    public ControleDocumentsCloture(DocumentRepository documentRepository, FindingRepository findingRepository) {
        this.documentRepository = documentRepository;
        this.findingRepository = findingRepository;
    }

    @Override
    public List<ControlItem> evaluer(Contexte contexte) {
        return List.of(documentsNonComptabilises(contexte), piecesManquantes(contexte));
    }

    /** Facture extraite mais sans ecriture : son montant manque dans la balance de l'exercice. */
    private ControlItem documentsNonComptabilises(Contexte c) {
        List<Document> documents = documentRepository.rechercherNonComptabilises(c.entrepriseId(), TYPES_COMPTABILISABLES, STATUTS_PRETS)
                .stream()
                .filter(d -> d.getDatePiece() == null
                        || (!d.getDatePiece().isBefore(c.exercice().getDateDebut()) && !d.getDatePiece().isAfter(c.exercice().getDateFin())))
                .toList();
        if (documents.isEmpty()) {
            return ClotureControle.ok("documents_non_comptabilises", "Factures non comptabilisees",
                    "Aucune facture traitee n'attend sa comptabilisation.", Map.of());
        }
        BigDecimal totalTtc = documents.stream().map(Document::getMontantTtc)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        long sansMontant = documents.stream().filter(d -> d.getMontantTtc() == null).count();
        String message = documents.size() + " facture(s) traitee(s) mais non comptabilisee(s) (" + totalTtc.toPlainString() + " TTC cumules).";
        String explication = message + (sansMontant > 0 ? " " + sansMontant + " sans montant TTC exploitable." : "")
                + " Elles ne figurent pas dans les comptes de l'exercice tant qu'elles ne sont pas comptabilisees.";
        return ClotureControle.avertissement("documents_non_comptabilises", "Factures non comptabilisees", message, explication,
                "DOCUMENT", documents.get(0).getId().toString(),
                documents.stream().map(d -> d.getId().toString()).toList(),
                Map.of("nbDocuments", documents.size(), "totalTtc", totalTtc, "sansMontantTtc", sansMontant));
    }

    /** Les pieces manquantes sont deja detectees (Finding MISSING_EVIDENCE) : ce point les rend visibles a la cloture. */
    private ControlItem piecesManquantes(Contexte c) {
        List<Finding> ouverts = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(c.entrepriseId(), StatutFinding.OPEN).stream()
                .filter(f -> f.getType() == TypeFinding.MISSING_EVIDENCE)
                .toList();
        if (ouverts.isEmpty()) {
            return ClotureControle.ok("pieces_manquantes", "Pieces justificatives manquantes",
                    "Aucune piece manquante signalee.", Map.of());
        }
        String message = ouverts.size() + " piece(s) justificative(s) manquante(s) signalee(s).";
        return ClotureControle.avertissement("pieces_manquantes", "Pieces justificatives manquantes", message,
                message + " Chaque ligne bancaire sans justificatif doit etre justifiee ou classee avant la cloture.",
                "FINDING", ouverts.get(0).getId().toString(),
                ouverts.stream().map(f -> f.getId().toString()).toList(),
                Map.of("nbPiecesManquantes", ouverts.size()));
    }
}
