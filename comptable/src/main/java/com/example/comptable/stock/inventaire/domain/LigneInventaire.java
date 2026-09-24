package com.example.comptable.stock.inventaire.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.stock.domain.Article;
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

/** Ligne de comptage d'un {@link Inventaire}. {@code stockTheorique}/{@code coutUnitaire} sont des
 * instantanes figes au moment de la saisie (jamais recalcules retroactivement) : mode PERMANENT ->
 * solde/PUMP courants de l'article ; mode INTERMITTENT -> dernier inventaire valide pour cet
 * article (ou une saisie manuelle explicite si aucun n'existe encore). {@code ecart}/
 * {@code valeurEcart} sont deriveees et persistees pour l'affichage/la regularisation. */
@Entity
@Table(name = "ligne_inventaire")
@Getter
@Setter
@NoArgsConstructor
public class LigneInventaire extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventaire_id", nullable = false)
    private Inventaire inventaire;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "stock_theorique", nullable = false)
    private BigDecimal stockTheorique;

    @Column(name = "stock_compte", nullable = false)
    private BigDecimal stockCompte;

    @Column(nullable = false)
    private BigDecimal ecart;

    @Column(name = "cout_unitaire", nullable = false)
    private BigDecimal coutUnitaire;

    @Column(name = "valeur_ecart", nullable = false)
    private BigDecimal valeurEcart;
}
