package com.example.comptable.agent.imputation.domain;

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
 * SUGGESTION D'IMPUTATION d'un document (mission P0, sections 14/15) : quel tiers et quel compte de
 * charge/produit proposer pour une piece, avec un niveau de confiance et les raisons de cette
 * confiance. Ce n'est PAS une proposition d'ecriture : les lignes, le journal, l'exercice et la
 * numerotation sont produits par {@code AccountingOperationEngine} (entite
 * {@code AccountingOperationProposal}) au moment de la comptabilisation. Une suggestion acceptee ne
 * fait que preremplir le choix de l'utilisateur ; {@code ComptabilisationService} reste le seul point
 * d'entree vers le moteur. Distincte d'{@code AiMatchProposal} (rapprochement bancaire). Meme
 * discipline "l'IA propose, Java decide" : jamais un compte pose sans validation.
 */
@Entity
@Table(name = "imputation_suggestion")
@Getter
@Setter
@NoArgsConstructor
public class ImputationSuggestion extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

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
    private StatutImputationSuggestion status = StatutImputationSuggestion.PROPOSED;

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
