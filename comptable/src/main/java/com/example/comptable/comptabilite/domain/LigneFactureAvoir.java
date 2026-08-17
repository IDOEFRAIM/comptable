package com.example.comptable.comptabilite.domain;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Ligne d'avoir, toujours rattachee a la {@link LigneFacture} d'origine qu'elle credite (meme
 * partiellement) : c'est ce lien qui permet de retrouver l'article a reintegrer en stock et de
 * garantir qu'un avoir ne peut jamais crediter plus que ce qui a ete reellement vendu. */
@Entity
@Table(name = "ligne_facture_avoir")
@Getter
@Setter
@NoArgsConstructor
public class LigneFactureAvoir extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "avoir_id", nullable = false)
    private FactureAvoir avoir;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ligne_facture_origine_id", nullable = false)
    private LigneFacture ligneFactureOrigine;

    @Column(nullable = false)
    private String designation;

    @Column(nullable = false)
    private BigDecimal quantite;

    @Column(name = "prix_unitaire", nullable = false)
    private BigDecimal prixUnitaire;

    @Column(name = "taux_tva", nullable = false)
    private BigDecimal tauxTva = BigDecimal.ZERO;

    @Column(name = "numero_ligne", nullable = false)
    private Integer numeroLigne;

    public BigDecimal montantHt() {
        return quantite.multiply(prixUnitaire).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal montantTva() {
        return montantHt().multiply(tauxTva).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal montantTtc() {
        return montantHt().add(montantTva());
    }
}
