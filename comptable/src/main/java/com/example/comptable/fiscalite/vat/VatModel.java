package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.core.TaxTreatment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Modèle du moteur TVA : toutes les valeurs monétaires sont des {@link BigDecimal} (jamais de flottant ; Angular n'effectue aucun calcul fiscal). */
public final class VatModel {

    private VatModel() {
    }

    /**
     * Opération = une écriture validée qui touche un compte de TVA du dossier. {@code outputVat} : crédit − débit sur le compte de TVA collectée ; {@code inputVat} : débit − crédit sur le compte de TVA
     * récupérable ; {@code totalDebit} : total de l'écriture ; {@code invoiceBase} : HT de la facture liée, quand il y en a une.
     */
    public record Operation(UUID ecritureId, LocalDate date, String piece, String libelle, String journal, String tiers, BigDecimal outputVat, BigDecimal inputVat, BigDecimal totalDebit,
                            String document, BigDecimal invoiceBase) {
    }

    /** Décision humaine sur une opération (jamais une modification de l'écriture). */
    public record Decision(TaxTreatment treatment, BigDecimal deductiblePercent, String reason) {
    }

    public record Adjustment(UUID id, String kind, String effect, BigDecimal amount, UUID ecritureId) {
    }

    /** {@code ecart} : TVA comptabilisée − TVA calculée au taux du cadre ; {@code explication} : DECISION, ADJUSTMENT, ROUNDING, NONE ou null (non calculable). */
    public record Line(UUID ecritureId, LocalDate date, String piece, String document, String tiers, String libelle, String side, TaxTreatment treatment, String treatmentSource,
                       BigDecimal base, BigDecimal impliedRatePercent, String rateCode, BigDecimal vatAccounted, BigDecimal vatFiscal, BigDecimal vatDeductible, BigDecimal ecart,
                       String explication, String status, List<String> issues) {
    }

    /** {@code key} : sujet stable d'une anomalie de période (PACK, LEDGER, ACCOUNT_OUTPUT…) ; {@code null} pour une anomalie d'opération (identifiée par l'écriture). */
    public record Anomaly(String code, UUID ecritureId, String key, String message) {
        public boolean bloquePreparation() {
            return !"TAX_FRAMEWORK_NOT_VERIFIED".equals(code);
        }
    }

    public record Totals(BigDecimal outputBase, BigDecimal outputVatAccounted, BigDecimal outputVatFiscal, BigDecimal inputBase, BigDecimal inputVatAccounted, BigDecimal inputVatDeductible,
                         BigDecimal adjustmentsOutput, BigDecimal adjustmentsInput, BigDecimal declaredOutput, BigDecimal declaredInput, BigDecimal netDeclared, BigDecimal netAccounted,
                         BigDecimal unexplained, int toReview, int operations, boolean fiscalComplete) {
    }

    public record Reconciliation(BigDecimal ledgerOutput, BigDecimal ledgerInput, BigDecimal operationsOutput, BigDecimal operationsInput, BigDecimal ledgerGap) {
    }

    public record DeclarationLine(String code, String label, String source, BigDecimal amount) {
    }

    /** {@code basis} : FISCAL_VERIFIED (montants calculés avec un cadre vérifié) ou ACCOUNTED_UNVERIFIED (montants comptabilisés, jamais finalisables). */
    public record Computation(List<Line> lines, Totals totals, Reconciliation reconciliation, List<Anomaly> anomalies, List<DeclarationLine> declaration, String basis, boolean officialStructure) {
    }
}
