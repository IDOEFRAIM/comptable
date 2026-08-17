package com.example.comptable.comptabilite.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Avoir commercial (note de credit) extournant tout ou partie d'une {@link Facture} de vente
 * VALIDEE ou PAYEE. Suit BROUILLON -> VALIDEE ; la validation genere l'ecriture d'extourne
 * (701/443 au debit, 411 au credit) et, si {@code retourStock}, reintegre en stock chaque ligne
 * dont la ligne d'origine porte un article, au PUMP courant de l'article.
 */
@Entity
@Table(name = "facture_avoir")
@Getter
@Setter
@NoArgsConstructor
public class FactureAvoir extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facture_origine_id", nullable = false)
    private Facture factureOrigine;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tiers_id", nullable = false)
    private Tiers tiers;

    @Column(nullable = false)
    private String numero;

    @Column(name = "date_emission", nullable = false)
    private LocalDate dateEmission;

    private String motif;

    @Column(name = "retour_stock", nullable = false)
    private boolean retourStock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutFactureAvoir statut = StatutFactureAvoir.BROUILLON;

    @Column(name = "ecriture_id")
    private UUID ecritureId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "avoir", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numeroLigne asc")
    private List<LigneFactureAvoir> lignes = new ArrayList<>();

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void ajouterLigne(LigneFactureAvoir ligne) {
        ligne.setAvoir(this);
        ligne.setNumeroLigne(lignes.size() + 1);
        lignes.add(ligne);
    }

    public BigDecimal montantHt() {
        return lignes.stream().map(LigneFactureAvoir::montantHt).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal montantTva() {
        return lignes.stream().map(LigneFactureAvoir::montantTva).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal montantTtc() {
        return lignes.stream().map(LigneFactureAvoir::montantTtc).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
