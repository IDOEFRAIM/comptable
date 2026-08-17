package com.example.comptable.stock.domain;

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
import java.util.UUID;

/**
 * Trace d'un mouvement d'inventaire permanent sur un {@link Article}. {@code quantite} est
 * strictement positive pour ENTREE/SORTIE ; pour AJUSTEMENT elle porte le signe de l'ecart
 * constate (positif = surplus, negatif = perte/casse). {@code valeurMouvement} et
 * {@code prixUnitaireHt} restent toujours des grandeurs positives. {@code quantiteApres}/
 * {@code pumpApres} sont des instantanes de l'etat de l'article juste apres ce mouvement,
 * conserves pour l'audit et l'historique meme si l'article continue d'evoluer ensuite.
 */
@Entity
@Table(name = "mouvement_stock")
@Getter
@Setter
@NoArgsConstructor
public class MouvementStock extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeMouvementStock type;

    @Column(nullable = false)
    private BigDecimal quantite;

    @Column(name = "prix_unitaire_ht", nullable = false)
    private BigDecimal prixUnitaireHt;

    @Column(name = "valeur_mouvement", nullable = false)
    private BigDecimal valeurMouvement;

    @Column(name = "quantite_apres", nullable = false)
    private BigDecimal quantiteApres;

    @Column(name = "pump_apres", nullable = false)
    private BigDecimal pumpApres;

    @Column(name = "date_mouvement", nullable = false)
    private LocalDate dateMouvement;

    private String libelle;

    @Column(name = "ecriture_id")
    private UUID ecritureId;

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
