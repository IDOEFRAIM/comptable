package com.example.comptable.comptabilite.allocation;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Affectation economique d'un reglement a une facture : relie la ligne tiers du reglement a la ligne tiers de la facture pour un
 * montant donne. Ce n'est PAS le lettrage (rapprochement comptable des mouvements du tiers) : l'allocation peut l'alimenter, jamais
 * le remplacer. Les lignes sont des references libres (comme {@code LigneReleve.ligneEcritureId}) : la verite reste l'ecriture.
 */
@Entity
@Table(name = "payment_allocation")
@Getter
@Setter
@NoArgsConstructor
public class PaymentAllocation extends UuidEntity {

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "payment_line_id", nullable = false)
    private UUID paymentLineId;

    @Column(name = "invoice_line_id", nullable = false)
    private UUID invoiceLineId;

    @Column(name = "allocated_amount", nullable = false)
    private BigDecimal allocatedAmount;

    @Column(nullable = false)
    private String currency = "XOF";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceAllocation source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutAllocation status = StatutAllocation.ACTIVE;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "reversal_reason")
    private String reversalReason;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
