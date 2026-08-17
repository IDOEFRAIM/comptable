package com.example.comptable.agent.pipeline;

import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Regles de conformite fiscale/comptable, en Java pur (aucun appel LLM) : deterministes,
 * rejouables, auditables. Ne juge que la coherence interne de l'extraction, pas sa pertinence
 * metier (tiers, imputation comptable...), qui reste hors de portee d'une regle automatique.
 */
@Component
public class ConformiteNode implements NodeAction<PieceJustificativeState> {

    /** Tolerance d'arrondi sur HT + TVA = TTC (2 centimes, pour absorber les arrondis de TVA
     * ligne a ligne que le VLM peut legerement mal reconstituer par calcul plutot que lecture directe). */
    private static final BigDecimal TOLERANCE_ARRONDI = new BigDecimal("0.02");

    @Override
    public Map<String, Object> apply(PieceJustificativeState state) {
        ExtractionPieceResult extraction = state.extraction();
        if (extraction == null) {
            return nonConforme("Aucune extraction disponible pour cette piece");
        }

        String motif = premiereAnomalie(extraction);
        if (motif != null) {
            return nonConforme(motif);
        }

        return Map.of(
                PieceJustificativeState.CONFORMITE_OK, true,
                PieceJustificativeState.MOTIF_ALERTE_CONFORMITE, ""
        );
    }

    private String premiereAnomalie(ExtractionPieceResult extraction) {
        if (extraction.montantTtc() == null) {
            return "Montant TTC manquant";
        }
        if (extraction.datePiece() == null) {
            return "Date de la piece manquante";
        }
        if (extraction.montantTtc().signum() < 0
                || (extraction.montantHt() != null && extraction.montantHt().signum() < 0)
                || (extraction.montantTva() != null && extraction.montantTva().signum() < 0)) {
            return "Montant negatif detecte";
        }
        if (extraction.montantHt() != null) {
            BigDecimal tva = extraction.montantTva() != null ? extraction.montantTva() : BigDecimal.ZERO;
            BigDecimal ecart = extraction.montantHt().add(tva).subtract(extraction.montantTtc()).abs();
            if (ecart.compareTo(TOLERANCE_ARRONDI) > 0) {
                return "HT + TVA (%s) ne correspond pas au TTC (%s), ecart de %s"
                        .formatted(extraction.montantHt().add(tva), extraction.montantTtc(), ecart);
            }
        }
        return null;
    }

    private Map<String, Object> nonConforme(String motif) {
        return Map.of(
                PieceJustificativeState.CONFORMITE_OK, false,
                PieceJustificativeState.MOTIF_ALERTE_CONFORMITE, motif
        );
    }
}
