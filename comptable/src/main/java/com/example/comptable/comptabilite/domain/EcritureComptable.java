package com.example.comptable.comptabilite.domain;

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

@Entity
@jakarta.persistence.EntityListeners({com.example.comptable.etatsfinanciers.sync.EcritureControlListener.class, com.example.comptable.fiscalite.vat.VatEcritureListener.class})
@Table(name = "ecriture_comptable")
@Getter
@Setter
@NoArgsConstructor
public class EcritureComptable extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false)
    private ExerciceComptable exercice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_id", nullable = false)
    private Journal journal;

    @Column(name = "numero_piece", nullable = false)
    private String numeroPiece;

    @Column(name = "date_ecriture", nullable = false)
    private LocalDate dateEcriture;

    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutEcriture statut = StatutEcriture.BROUILLON;

    /** Nature comptable (V61) : activite courante, inventaire, cloture... determine la vue de reporting dans laquelle l'ecriture entre. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NatureEcriture nature = NatureEcriture.BUSINESS;

    /** Evenement de tresorerie declare a la saisie (V63) ; null si non qualifie. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cash_flow_event")
    private CashFlowEvent cashFlowEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Utilisateur createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "ecriture", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numeroLigne asc")
    private List<LigneEcriture> lignes = new ArrayList<>();

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void ajouterLigne(LigneEcriture ligne) {
        ligne.setEcriture(this);
        ligne.setNumeroLigne(lignes.size() + 1);
        lignes.add(ligne);
    }

    public BigDecimal totalDebit() {
        return lignes.stream().map(LigneEcriture::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalCredit() {
        return lignes.stream().map(LigneEcriture::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Principe fondamental de la partie double : jamais d'ecriture desequilibree. */
    public boolean estEquilibree() {
        return !lignes.isEmpty() && totalDebit().compareTo(totalCredit()) == 0;
    }
}
