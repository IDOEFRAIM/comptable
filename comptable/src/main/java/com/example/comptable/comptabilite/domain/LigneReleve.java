package com.example.comptable.comptabilite.domain;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une ligne de releve bancaire brut. Montant signe : positif = entree d'argent (credit bancaire,
 * cote de la banque), negatif = sortie d'argent - convention standard des exports CSV bancaires.
 * ligneEcritureId reste une reference libre (pas de FK geree par JPA) vers la ligne d'ecriture
 * comptable avec laquelle elle a ete pointee ; null tant que non rapprochee.
 */
@Entity
@Table(name = "ligne_releve")
@Getter
@Setter
@NoArgsConstructor
public class LigneReleve extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "releve_id", nullable = false)
    private ReleveBancaire releve;

    @Column(name = "date_operation", nullable = false)
    private LocalDate dateOperation;

    private String libelle;

    @Column(nullable = false)
    private BigDecimal montant;

    @Column(name = "ligne_ecriture_id")
    private UUID ligneEcritureId;

    public LigneReleve(LocalDate dateOperation, String libelle, BigDecimal montant) {
        this.dateOperation = dateOperation;
        this.libelle = libelle;
        this.montant = montant;
    }
}
