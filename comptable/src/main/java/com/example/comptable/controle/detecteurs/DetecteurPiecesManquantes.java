package com.example.comptable.controle.detecteurs;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository.PieceManquanteRow;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ecriture &lt;-&gt; Piece (Phase B, lot B2) : {@code MISSING_DOCUMENT} pour une ecriture d'achat
 * (facture fournisseur, avoir d'achat, acquisition d'immobilisation) produite par le moteur et
 * qu'aucune piece justificative ne soutient. Le lien piece &lt;-&gt; ecriture est
 * {@code Document.ecritureId} : rattacher une piece fait disparaitre le constat au passage suivant.
 * Au-dela de {@link #SEUIL_AGREGATION} ecritures sans piece, un seul constat resume le manque.
 */
@Component
public class DetecteurPiecesManquantes implements ControlDetector {

    static final int SEUIL_AGREGATION = 20;
    private static final int MAX_IDS_LIES = 50;
    private static final Set<AccountingOperationType> TYPES_AVEC_PIECE = Set.of(AccountingOperationType.ACHAT,
            AccountingOperationType.AVOIR_ACHAT, AccountingOperationType.IMMOBILISATION_ACQUISITION);

    private final AccountingOperationProposalRepository proposalRepository;

    public DetecteurPiecesManquantes(AccountingOperationProposalRepository proposalRepository) {
        this.proposalRepository = proposalRepository;
    }

    @Override
    public String nom() {
        return "pieces-manquantes";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.MISSING_DOCUMENT);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<PieceManquanteRow> sansPiece = proposalRepository.rechercherSansPiece(entreprise.getId(), TYPES_AVEC_PIECE);
        if (sansPiece.isEmpty()) {
            return List.of();
        }
        if (sansPiece.size() > SEUIL_AGREGATION) {
            BigDecimal total = sansPiece.stream().map(PieceManquanteRow::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
            return List.of(ControlResult.ouvert(ControlCodes.MISSING_DOCUMENT, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                    entreprise.getId(), "PIECES_MANQUANTES", entreprise.getId().toString(),
                    sansPiece.size() + " ecritures d'achat sans piece justificative",
                    sansPiece.size() + " ecritures d'achat (total " + total.toPlainString()
                            + ") n'ont aucune piece rattachee : un seul constat resume le manque pour ne pas noyer la revue.",
                    sansPiece.stream().limit(MAX_IDS_LIES).map(r -> r.ecritureId().toString()).toList(),
                    Map.of("nbEcritures", sansPiece.size(), "montantTotal", total),
                    "Rattacher les pieces aux ecritures depuis le Hub documents."));
        }
        return sansPiece.stream().map(r -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("numeroPiece", r.numeroPiece());
            metadata.put("sourceType", r.sourceType());
            metadata.put("sourceId", r.sourceId().toString());
            metadata.put("montant", r.montant());
            metadata.put("dateOperation", r.dateOperation().toString());
            // Instantane lisible, repris par la demande de piece : le client ne doit jamais voir d'identifiant interne.
            if (r.tiersNom() != null) {
                metadata.put("tiersNom", r.tiersNom());
                metadata.put("tiersId", r.tiersId().toString());
            }
            return ControlResult.ouvert(ControlCodes.MISSING_DOCUMENT, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                    entreprise.getId(), "ECRITURE_COMPTABLE", r.ecritureId().toString(),
                    "Piece justificative manquante : " + r.libelle(),
                    "L'ecriture " + r.numeroPiece() + " (" + r.montant().toPlainString() + ", " + r.dateOperation()
                            + ", source " + r.sourceType() + ") ne s'appuie sur aucune piece justificative.",
                    List.of(r.sourceId().toString()), metadata,
                    "Rattacher la facture ou le justificatif a cette ecriture.");
        }).toList();
    }
}
