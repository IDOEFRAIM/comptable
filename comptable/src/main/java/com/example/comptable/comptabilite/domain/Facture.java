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

/** Devis ou facture client (meme structure, type discriminant). Un devis accepte peut etre
 * converti en facture ; la facture porte alors devisOrigineId vers le devis dont elle decoule. */
@Entity
@Table(name = "facture")
@Getter
@Setter
@NoArgsConstructor
public class Facture extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tiers_id", nullable = false)
    private Tiers tiers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeFacture type;

    @Column(nullable = false)
    private String numero;

    @Column(name = "date_emission", nullable = false)
    private LocalDate dateEmission;

    @Column(name = "date_echeance")
    private LocalDate dateEcheance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutFacture statut = StatutFacture.BROUILLON;

    private String objet;

    @Column(name = "devis_origine_id")
    private UUID devisOrigineId;

    @Column(name = "ecriture_id")
    private UUID ecritureId;

    /** Compte de produit (701/706...) choisi lors de la validation, trace en base pour que
     * FactureAvoirService sache quel compte extourner exactement lors d'un avoir - jusqu'ici ce
     * choix n'etait qu'un parametre de requete transitoire, jamais persiste. Null tant que la
     * facture n'est pas validee. */
    @Column(name = "compte_produit_id")
    private UUID compteProduitId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "facture", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numeroLigne asc")
    private List<LigneFacture> lignes = new ArrayList<>();

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void ajouterLigne(LigneFacture ligne) {
        ligne.setFacture(this);
        ligne.setNumeroLigne(lignes.size() + 1);
        lignes.add(ligne);
    }

    public BigDecimal montantHt() {
        return lignes.stream().map(LigneFacture::montantHt).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal montantTva() {
        return lignes.stream().map(LigneFacture::montantTva).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal montantTtc() {
        return lignes.stream().map(LigneFacture::montantTtc).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
