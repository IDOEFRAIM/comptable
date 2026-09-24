package com.example.comptable.stock.domain;

import com.example.comptable.comptabilite.domain.CompteComptable;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Article du catalogue, valorise en inventaire permanent au PUMP (Prix Unitaire Moyen Pondere).
 * Les 4 comptes associes sont choisis explicitement a la creation (jamais devines), a l'image du
 * choix de compte de produit/charge dans FacturationService/ComptabilisationService : {@code
 * compteStock} (classe 3, ex 311), {@code compteAchat} (charge, ex 601), {@code compteVente}
 * (produit, ex 701) et {@code compteVariation} (contrepartie des mouvements de stock, ex 6031).
 */
@Entity
@Table(name = "article")
@Getter
@Setter
@NoArgsConstructor
public class Article extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Column(name = "code_article", nullable = false)
    private String codeArticle;

    @Column(nullable = false)
    private String designation;

    private String categorie;

    @Column(name = "unite_mesure", nullable = false)
    private String uniteMesure;

    @Column(name = "prix_vente_ht", nullable = false)
    private BigDecimal prixVenteHt;

    @Column(name = "taux_tva", nullable = false)
    private BigDecimal tauxTva = BigDecimal.ZERO;

    @Column(name = "quantite_en_stock", nullable = false)
    private BigDecimal quantiteEnStock = BigDecimal.ZERO;

    @Column(name = "valeur_stock_totale", nullable = false)
    private BigDecimal valeurStockTotale = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal pump = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_stock_id", nullable = false)
    private CompteComptable compteStock;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_achat_id", nullable = false)
    private CompteComptable compteAchat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_vente_id", nullable = false)
    private CompteComptable compteVente;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_variation_id", nullable = false)
    private CompteComptable compteVariation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeArticle type = TypeArticle.MARCHANDISE;

    @Column(nullable = false)
    private boolean actif = true;

    @Column(name = "stock_minimum")
    private BigDecimal stockMinimum;

    @Enumerated(EnumType.STRING)
    @Column(name = "politique_stock_negatif", nullable = false)
    private PolitiqueStockNegatif politiqueStockNegatif = PolitiqueStockNegatif.INTERDIT;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_gestion_stock", nullable = false)
    private ModeGestionStock modeGestionStock = ModeGestionStock.PERMANENT;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
