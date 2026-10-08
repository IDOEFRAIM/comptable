package com.example.comptable.comptabilite.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.entreprise.domain.Entreprise;
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
 * Immobilisation amortissable : le compte d'actif (classe 2) et son compte d'amortissement (classe 28)
 * sont choisis explicitement, l'entreprise pouvant avoir plusieurs comptes d'immobilisation (terrains,
 * materiel, batiments...). Cycle de vie : {@link StatutImmobilisation} ACQUISE -&gt; EN_SERVICE -&gt;
 * SORTIE. L'amortissement ne court qu'a partir de {@code dateMiseEnService} ; la date d'acquisition
 * reste celle de la piece d'achat.
 */
@Entity
@Table(name = "immobilisation")
@Getter
@Setter
@NoArgsConstructor
public class Immobilisation extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_id", nullable = false)
    private CompteComptable compte;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_amortissement_id", nullable = false)
    private CompteComptable compteAmortissement;

    @Column(nullable = false)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeImmobilisation type = TypeImmobilisation.CORPORELLE;

    @Column(name = "date_acquisition", nullable = false)
    private LocalDate dateAcquisition;

    /** Null tant que l'immobilisation n'est pas mise en service : aucun plan, aucune dotation. */
    @Column(name = "date_mise_en_service")
    private LocalDate dateMiseEnService;

    @Column(name = "valeur_origine", nullable = false)
    private BigDecimal valeurOrigine;

    @Column(name = "duree_annees", nullable = false)
    private Integer dureeAnnees;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModeAmortissement mode = ModeAmortissement.LINEAIRE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutImmobilisation statut = StatutImmobilisation.ACQUISE;

    /**
     * Consequence comptable de l'acquisition : la PROPOSITION qui l'a portee, qu'elle vienne du moteur
     * (source IMMOBILISATION/ACQUISITION) ou d'une facture fournisseur deja comptabilisee a laquelle
     * l'immobilisation a ete liee. Permet de savoir par quelle operation, quand et vers quelle ecriture ;
     * null tant que l'acquisition n'est rattachee a aucune comptabilisation.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acquisition_proposal_id")
    private AccountingOperationProposal acquisitionProposal;

    @Column(name = "date_sortie")
    private LocalDate dateSortie;

    @Enumerated(EnumType.STRING)
    @Column(name = "motif_sortie")
    private MotifSortie motifSortie;

    /** Prix de cession (CESSION uniquement), si connu. */
    @Column(name = "montant_cession")
    private BigDecimal montantCession;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
