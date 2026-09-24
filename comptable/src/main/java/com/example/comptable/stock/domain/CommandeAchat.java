package com.example.comptable.stock.domain;

import com.example.comptable.comptabilite.domain.Tiers;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bon de commande fournisseur. Suit un cycle minimal EN_COURS -> RECEPTIONNEE : la reception
 * est la seule transition (pas d'etape "envoye au fournisseur" separee pour l'instant) et
 * declenche, pour chaque ligne, une entree en stock via MouvementStockService - qui recalcule
 * le PUMP de l'article et genere l'ecriture 311/6031 correspondante. La facture fournisseur
 * (voir {@link FactureFournisseur}) est comptabilisee via AccountingOperationEngine (AchatRule) -
 * ce service ne construit plus d'EcritureComptable lui-meme (mission Etape 5).
 */
@Entity
@Table(name = "commande_achat")
@Getter
@Setter
@NoArgsConstructor
public class CommandeAchat extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fournisseur_id", nullable = false)
    private Tiers fournisseur;

    @Column(nullable = false)
    private String numero;

    @Column(name = "date_commande", nullable = false)
    private LocalDate dateCommande;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutCommandeAchat statut = StatutCommandeAchat.EN_COURS;

    @Column(name = "date_reception")
    private LocalDate dateReception;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "commande", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numeroLigne asc")
    private List<LigneCommandeAchat> lignes = new ArrayList<>();

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void ajouterLigne(LigneCommandeAchat ligne) {
        ligne.setCommande(this);
        ligne.setNumeroLigne(lignes.size() + 1);
        lignes.add(ligne);
    }
}
