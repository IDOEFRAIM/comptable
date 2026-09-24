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

    /** Subdivision locale : compte parent au sein du PCE de la meme entreprise (jamais un lien
     * vers le referentiel global). Null pour un compte de premier niveau. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private CompteComptable parent;

    @Column(nullable = false)
    private String numero;

    @Column(nullable = false)
    private String intitule;

    @Column(nullable = false)
    private boolean lettrable;

    @Column(nullable = false)
    private Short niveau = 1;

    /** Compte utilise par l'entreprise (independant du champ actif du referentiel global). */
    @Column(nullable = false)
    private boolean actif = true;

    /** Nature du compte (ACTIF/PASSIF/CHARGE/PRODUIT), null pour un sous-compte auxiliaire de tiers. */
    public NatureCompte getNature() {
        return reference != null ? reference.getNature() : null;
    }

    /** Sens comptable normal (DEBIT/CREDIT), null si le compte n'est pas derive du referentiel. */
    public SensNormal getSensNormal() {
        return reference != null ? reference.getSensNormal() : null;
    }

    /** Compte collectif au sens tiers (ex. 401/411), false si non derive du referentiel. */
    public boolean isCompteCollectif() {
        return reference != null && reference.isCompteCollectif();
    }

    /** Autorise la creation d'une subdivision locale sous ce compte. Pour un compte purement
     * local (sans reference), l'autorisation reste implicite : rien n'empeche aujourd'hui de
     * creer un enfant sous un compte local, seule isUsableCommeDetail() se met a jour ensuite. */
    public boolean isAuxiliaireAutorise() {
        return reference == null || reference.isAuxiliaireAutorise();
    }
}
