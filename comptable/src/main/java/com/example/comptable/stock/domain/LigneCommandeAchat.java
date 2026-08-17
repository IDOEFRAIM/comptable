package com.example.comptable.stock.domain;

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

@Entity
@Table(name = "ligne_commande_achat")
@Getter
@Setter
@NoArgsConstructor
public class LigneCommandeAchat extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commande_id", nullable = false)
    private CommandeAchat commande;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(nullable = false)
    private BigDecimal quantite;

    @Column(name = "prix_unitaire_ht", nullable = false)
    private BigDecimal prixUnitaireHt;

    @Column(name = "numero_ligne", nullable = false)
    private Integer numeroLigne;
}
