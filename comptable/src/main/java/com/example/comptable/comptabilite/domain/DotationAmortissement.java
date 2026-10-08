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
import java.util.UUID;

/** Trace la dotation d'amortissement deja generee pour une immobilisation sur une annee donnee,
 * pour empecher une double generation (contrainte unique immobilisation+annee en base). Le montant est
 * calcule par le domaine Immobilisation ; l'ecriture correspondante est creee par le moteur comptable. */
@Entity
@Table(name = "dotation_amortissement")
@Getter
@Setter
@NoArgsConstructor
public class DotationAmortissement extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "immobilisation_id", nullable = false)
    private Immobilisation immobilisation;

    @Column(nullable = false)
    private Integer annee;

    @Column(nullable = false)
    private BigDecimal montant;

    @Column(name = "ecriture_id")
    private UUID ecritureId;
}
