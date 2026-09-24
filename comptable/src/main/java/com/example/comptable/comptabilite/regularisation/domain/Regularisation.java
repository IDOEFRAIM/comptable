package com.example.comptable.comptabilite.regularisation.domain;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Regularisation de fin de periode (CCA/PCA/FNP/CAP/PAR - voir {@link RegularisationType}).
 * compteOrigine/compteContrepartie sont toujours saisis et valides par l'utilisateur (jamais
 * resolus via un role systeme non verifie - voir CompteComptableValidationService dans
 * RegularisationRule). montantExercice/montantFutur sont calcules par
 * RegularisationService au prorata des dates (jamais une simple saisie manuelle libre lorsque
 * dateDebut/dateFin permettent un calcul deterministe). La reprise (contre-passation) n'est
 * jamais automatique : repriseAutomatique n'autorise qu'un appel explicite ulterieur, jamais un
 * declenchement planifie sur dateReprise seule.
 */
@Entity
@Table(name = "regularisation")
@Getter
@Setter
@NoArgsConstructor
public class Regularisation extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false)
    private ExerciceComptable exercice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegularisationType type;

    @Column(name = "date_origine", nullable = false)
    private LocalDate dateOrigine;

    @Column(name = "date_debut")
    private LocalDate dateDebut;

    @Column(name = "date_fin")
    private LocalDate dateFin;

    @Column(nullable = false)
    private BigDecimal montant;

    @Column(name = "montant_exercice")
    private BigDecimal montantExercice;

    @Column(name = "montant_futur")
    private BigDecimal montantFutur;

    @Column(nullable = false)
    private String libelle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_origine_id", nullable = false)
    private CompteComptable compteOrigine;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_contrepartie_id", nullable = false)
    private CompteComptable compteContrepartie;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutRegularisation statut = StatutRegularisation.BROUILLON;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposition_id")
    private AccountingOperationProposal proposition;

    @Column(name = "reprise_automatique", nullable = false)
    private boolean repriseAutomatique;

    @Column(name = "date_reprise")
    private LocalDate dateReprise;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposition_reprise_id")
    private AccountingOperationProposal propositionReprise;

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
