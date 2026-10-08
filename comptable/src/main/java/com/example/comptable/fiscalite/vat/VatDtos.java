package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.vat.VatModel.Anomaly;
import com.example.comptable.fiscalite.vat.VatModel.DeclarationLine;
import com.example.comptable.fiscalite.vat.VatModel.Line;
import com.example.comptable.fiscalite.vat.VatModel.Reconciliation;
import com.example.comptable.fiscalite.vat.VatModel.Totals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contrat HTTP de l'espace TVA : tous les montants sont calculés côté serveur en BigDecimal ; l'écran ne calcule rien. */
public final class VatDtos {

    private VatDtos() {
    }

    public record FrameworkInfo(UUID id, String jurisdiction, String version, String status, boolean verifie, String definitionHash, List<String> sourceReferences, LocalDate effectiveFrom,
                                LocalDate effectiveTo) {
    }

    public record Permissions(boolean prepare, boolean review, boolean finalise) {
    }

    public record PeriodSummary(UUID id, LocalDate periodStart, LocalDate periodEnd, String status, String frameworkVersion, boolean frameworkVerified, long version) {
    }

    /** Progression par étapes (jamais un pourcentage) et statut opérationnel unique, calculés par le backend. */
    public record Progress(String status, int readySteps, int totalSteps, List<String> blockingSteps, List<VatOperationalStatusEvaluator.Step> steps) {
    }

    /**
     * {@code finalisationReasons} : codes machine des raisons de blocage (ex. REGULATORY_PACK_NOT_VERIFIED) ; {@code regulatoryPackVerified} : un cadre VERIFIED couvre la période ; {@code version} : jeton
     * de concurrence à renvoyer en {@code expectedVersion} à chaque modification.
     */
    public record PeriodView(UUID id, UUID entrepriseId, LocalDate periodStart, LocalDate periodEnd, String status, FrameworkInfo framework, Totals totals, Reconciliation reconciliation,
                             String basis, boolean officialStructure, int anomalies, int toReview, boolean finalisable, List<String> finalisationBlockers, Permissions permissions, boolean snapshotAvailable,
                             long version, boolean regulatoryPackVerified, List<String> finalisationReasons, Progress progress, PaymentSummary payment, CreditView credit, LocalDate deadline) {
    }

    public record PaymentView(UUID id, BigDecimal amount, LocalDate paymentDate, String reference, String source, String status, OffsetDateTime createdAt, OffsetDateTime reversedAt, String reverseReason) {
    }

    /** Montant déclaré / payé / restant d'une déclaration finalisée (suivi interne) ; {@code declaredAmount} est null tant que la période n'est pas FINAL. */
    public record PaymentSummary(BigDecimal declaredAmount, BigDecimal paidAmount, BigDecimal remainingAmount, String status, boolean paymentOpen, List<PaymentView> payments) {
    }

    public record PaymentRequest(BigDecimal amount, LocalDate paymentDate, String reference, String source, String idempotencyKey, Long expectedVersion) {
    }

    public record ReverseRequest(String reason, Long expectedVersion) {
    }

    /** Crédit d'ouverture saisi et crédit de clôture informatif ; les états sont TO_REVIEW tant qu'aucun cadre vérifié ne prévoit le report. */
    public record CreditView(BigDecimal openingAmount, String reason, String source, UUID previousPeriodId, OffsetDateTime createdAt, String openingState, BigDecimal closingAmount, String closingState) {
    }

    public record CreditRequest(BigDecimal amount, String reason, String source, UUID previousPeriodId, Long expectedVersion) {
    }

    public record VersionRequest(Long expectedVersion) {
    }

    /** Validation en masse : tous les identifiants sont revalidés par le serveur (même sens de TVA, même traitement certain, aucun conflit) ; tout ou rien. */
    public record BulkTreatmentRequest(List<UUID> ecritureIds, String treatment, String reason, Long expectedVersion) {
    }

    public record BulkRejection(UUID ecritureId, String reason) {
    }

    public record BulkTreatmentResult(int applied, PeriodView period) {
    }

    public record Page<T>(List<T> items, int page, int taille, long total) {
    }

    public record AdjustmentView(UUID id, String kind, String effect, BigDecimal amount, String reason, String evidence, UUID ecritureId, OffsetDateTime createdAt, boolean cancelled, String cancelReason) {
    }

    public record ControlsView(List<Anomaly> anomalies, Reconciliation reconciliation, BigDecimal unexplained) {
    }

    public record DeclarationView(UUID id, String status, String basis, boolean officialStructure, FrameworkInfo framework, List<DeclarationLine> lines, OffsetDateTime reviewedAt, OffsetDateTime finalizedAt,
                                  UUID snapshotId, String snapshotSha256, boolean finalisable, List<String> finalisationBlockers, List<String> finalisationReasons) {
    }

    public record OpenRequest(LocalDate periodStart, LocalDate periodEnd) {
    }

    public record TreatmentRequest(String treatment, BigDecimal deductiblePercent, String reason, Long expectedVersion) {
        public TreatmentRequest(String treatment, BigDecimal deductiblePercent, String reason) {
            this(treatment, deductiblePercent, reason, null);
        }
    }

    public record AdjustmentRequest(String kind, String effect, BigDecimal amount, String reason, String evidence, UUID ecritureId, Long expectedVersion) {
        public AdjustmentRequest(String kind, String effect, BigDecimal amount, String reason, String evidence, UUID ecritureId) {
            this(kind, effect, amount, reason, evidence, ecritureId, null);
        }
    }

    public record CancelRequest(String reason, Long expectedVersion) {
        public CancelRequest(String reason) {
            this(reason, null);
        }
    }

    public record LineageView(Line operation, List<Map<String, Object>> ecritureLignes, Map<String, Object> facture, List<Map<String, Object>> documents) {
    }

    public record SnapshotView(UUID id, OffsetDateTime createdAt, String sha256, String frameworkVersion, Map<String, Object> payload) {
    }
}
