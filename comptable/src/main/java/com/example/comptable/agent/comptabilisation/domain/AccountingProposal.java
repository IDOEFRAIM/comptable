package com.example.comptable.agent.comptabilisation.domain;

import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.document.domain.Document;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.identite.domain.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Proposition de comptabilisation Document -> Ecriture (mission P0, sections 14/15) : distincte
 * d'{@code AiMatchProposal} (domaine different - comptabilisation, pas rapprochement - et forme
 * plus riche : journal + tiers + compte + repartition TVA proposes plutot qu'un simple couple
 * candidat/score). Meme discipline "l'IA propose, Java decide" : cette table trace uniquement la
 * proposition, {@code ComptabilisationService.comptabiliser} reste le seul point d'ecriture reelle
 * (jamais ecrit directement depuis cette classe).
 */
@Entity
@Table(name = "accounting_proposal")
@Getter
@Setter
@NoArgsConstructor
public class AccountingProposal extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id")
    private Journal journal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tiers_id")
    private Tiers tiers;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_charge_produit_id")
    private CompteComptable compteChargeProduit;

    @Column(name = "montant_ht")
    private BigDecimal montantHt;

    @Column(name = "montant_tva")
    private BigDecimal montantTva;

    @Column(name = "montant_ttc")
    private BigDecimal montantTtc;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutAccountingProposal status = StatutAccountingProposal.PROPOSED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons_json")
    private List<String> reasonsJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "validated_at")
    private OffsetDateTime validatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by_id")
    private Utilisateur validatedBy;

    /** Mission P1.9 (section 31) : motif libre du rejet humain, meme pattern que
     * {@code Finding.rejectionReason} et {@code AiMatchProposal.rejectionReason}. */
    @Column(name = "rejection_reason")
    private String rejectionReason;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
