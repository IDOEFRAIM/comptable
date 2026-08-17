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
import java.util.UUID;

@Entity
@Table(name = "ligne_facture")
@Getter
@Setter
@NoArgsConstructor
public class LigneFacture extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facture_id", nullable = false)
    private Facture facture;

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

    /** Article de stock vendu par cette ligne, le cas echeant (null pour une prestation/service
     * sans suivi de stock). Reference volontairement en UUID nu (pas de relation JPA) pour ne pas
     * faire dependre le package comptabilite du package stock au niveau des entites - seule la
     * couche service (FacturationService -&gt; MouvementStockService) a connaissance des deux. */
    @Column(name = "article_id")
    private UUID articleId;

    /** Mouvement de stock (SORTIE) genere par la validation de cette ligne, trace pour permettre
     * une reintegration exacte (meme quantite, meme prix) si la facture est annulee. */
    @Column(name = "mouvement_stock_id")
    private UUID mouvementStockId;

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
