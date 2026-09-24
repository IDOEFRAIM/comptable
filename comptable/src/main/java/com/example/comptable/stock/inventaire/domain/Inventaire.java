package com.example.comptable.stock.inventaire.domain;

import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.identite.domain.Utilisateur;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Inventaire physique d'une entreprise. {@code proposition} n'est renseignee qu'a la validation
 * (voir {@code InventaireService.valider}) - avant cela l'inventaire n'a produit ni mouvement ni
 * proposition d'ecriture. La cloture (statut CLOTURE) exige que cette proposition soit APPROVED :
 * le moteur de stock ne cree jamais directement une ecriture validee. */
@Entity
@Table(name = "inventaire")
@Getter
@Setter
@NoArgsConstructor
public class Inventaire extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutInventaire statut = StatutInventaire.BROUILLON;

    private String commentaire;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposition_id")
    private AccountingOperationProposal proposition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Utilisateur createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "inventaire", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LigneInventaire> lignes = new ArrayList<>();

    public void ajouterLigne(LigneInventaire ligne) {
        ligne.setInventaire(this);
        lignes.add(ligne);
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
