package com.example.comptable.comptabilite.depreciation.domain;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.common.domain.UuidEntity;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Depreciation / perte de valeur. objetId reste une reference polymorphe libre (pas de FK stricte -
 * les categories {@link ObjetDepreciation} ne partagent pas de table commune). Si
 * valeurRecouvrable &gt;= valeurComptable, aucune perte n'est constatee : voir
 * comptabilite.operation.regularisation.DepreciationRule, qui ne genere alors aucune ligne.
 */
@Entity
@Table(name = "depreciation")
@Getter
@Setter
@NoArgsConstructor
public class Depreciation extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false)
    private ExerciceComptable exercice;

    @Enumerated(EnumType.STRING)
    @Column(name = "objet_type", nullable = false)
    private ObjetDepreciation objetType;

    @Column(name = "objet_id", nullable = false)
    private UUID objetId;

    @Column(name = "valeur_comptable", nullable = false)
    private BigDecimal valeurComptable;

    @Column(name = "valeur_recouvrable", nullable = false)
    private BigDecimal valeurRecouvrable;

    private String justification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_dotation_id", nullable = false)
    private CompteComptable compteDotation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_depreciation_id", nullable = false)
    private CompteComptable compteDepreciation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutDepreciation statut = StatutDepreciation.BROUILLON;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposition_id")
    private AccountingOperationProposal proposition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Utilisateur createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
