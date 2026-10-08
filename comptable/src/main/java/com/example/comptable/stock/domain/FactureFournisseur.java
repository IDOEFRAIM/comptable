package com.example.comptable.stock.domain;

import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Facture fournisseur generee depuis un {@link CommandeAchat} receptionne : cloture la chaine
 * Commande -&gt; Reception -&gt; Facture. Les lignes ne sont pas dupliquees ici, elles restent
 * portees par {@code CommandeAchat.lignes} (relation 1-1 via {@code commandeId}) ; cette entite
 * ne stocke que les montants agreges et le lien vers l'ecriture d'achat generee.
 */
@Entity
@Table(name = "facture_fournisseur")
@Getter
@Setter
@NoArgsConstructor
public class FactureFournisseur extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commande_id", nullable = false)
    private CommandeAchat commande;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fournisseur_id", nullable = false)
    private Tiers fournisseur;

    @Column(nullable = false)
    private String numero;

    /** Numero de la facture telle qu'emise par le FOURNISSEUR (le {@link #numero} ci-dessus est interne). Facultatif :
     * renseigne quand la piece du fournisseur est rapprochee. */
    @Column(name = "numero_fournisseur")
    private String numeroFournisseur;

    @Column(name = "numero_fournisseur_normalise")
    private String numeroFournisseurNormalise;

    public void setNumeroFournisseur(String numeroFournisseur) {
        this.numeroFournisseur = numeroFournisseur;
        this.numeroFournisseurNormalise = com.example.comptable.document.service.NumeroFactureNormalizer.normaliser(numeroFournisseur);
    }

    @Column(name = "date_facture", nullable = false)
    private LocalDate dateFacture;

    @Column(name = "montant_ht", nullable = false)
    private BigDecimal montantHt;

    @Column(name = "montant_tva", nullable = false)
    private BigDecimal montantTva;

    @Column(name = "montant_ttc", nullable = false)
    private BigDecimal montantTtc;

    @Column(name = "ecriture_id")
    private UUID ecritureId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
