package com.example.comptable.comptabilite.domain;

import com.example.comptable.common.domain.UuidEntity;
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

/** Immobilisation corporelle amortissable : le compte d'actif (classe 2) et son compte
 * d'amortissement (classe 28) associe sont choisis explicitement, l'entreprise pouvant avoir
 * plusieurs comptes d'immobilisation (terrains, materiel, batiments...). */
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

    @Column(name = "date_acquisition", nullable = false)
    private LocalDate dateAcquisition;

    @Column(name = "valeur_origine", nullable = false)
    private BigDecimal valeurOrigine;

    @Column(name = "duree_annees", nullable = false)
    private Integer dureeAnnees;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModeAmortissement mode = ModeAmortissement.LINEAIRE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutImmobilisation statut = StatutImmobilisation.EN_SERVICE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
