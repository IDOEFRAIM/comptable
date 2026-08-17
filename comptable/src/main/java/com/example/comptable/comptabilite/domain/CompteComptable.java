package com.example.comptable.comptabilite.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Compte utilise par une entreprise, derive (ou non) du referentiel SYSCOHADA. */
@Entity
@Table(name = "compte_comptable")
@Getter
@Setter
@NoArgsConstructor
public class CompteComptable extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_id")
    private PlanComptableReference reference;

    @Column(nullable = false)
    private String numero;

    @Column(nullable = false)
    private String intitule;

    @Column(nullable = false)
    private boolean lettrable;

    /** Nature du compte (ACTIF/PASSIF/CHARGE/PRODUIT), null pour un sous-compte auxiliaire de tiers. */
    public NatureCompte getNature() {
        return reference != null ? reference.getNature() : null;
    }
}
