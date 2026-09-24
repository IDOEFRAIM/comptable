package com.example.comptable.comptabilite.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Referentiel global du plan comptable SYSCOHADA revise, partage entre tous les tenants.
 * Represente une arborescence (classe -&gt; compte parent -&gt; sous-compte -&gt; detail) via
 * auto-reference {@link #parent}. Reste un referentiel de demonstration : voir
 * V33__plan_comptable_reference_hierarchie_demo.sql et comptable/DOCUMENTATION.md pour la
 * distinction explicite avec la nomenclature SYSCOHADA officielle complete (non encore verifiee). */
@Entity
@Table(name = "plan_comptable_reference")
@Getter
@Setter
@NoArgsConstructor
public class PlanComptableReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String numero;

    private String intitule;

    private Short classe;

    @Enumerated(EnumType.STRING)
    private NatureCompte nature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private PlanComptableReference parent;

    @Column(nullable = false)
    private Short niveau;

    @Enumerated(EnumType.STRING)
    @Column(name = "sens_normal", nullable = false)
    private SensNormal sensNormal;

    /** Compte collectif au sens tiers (ex. 401/411) : resume des sous-comptes auxiliaires. */
    @Column(name = "compte_collectif", nullable = false)
    private boolean compteCollectif;

    /** Compte utilisable comme compte de detail (postable) - independant du fait qu'il ait ou
     * non des sous-comptes deja seedes en dessous. */
    @Column(name = "compte_detail", nullable = false)
    private boolean compteDetail;

    @Column(nullable = false)
    private boolean lettrable;

    /** Autorise une entreprise a creer une subdivision locale sous ce compte. Distinct de
     * {@link #compteCollectif} : la classification SYSCOHADA et la regle d'usage restent
     * separees pour les futurs moteurs Achats/Ventes/Stocks. */
    @Column(name = "auxiliaire_autorise", nullable = false)
    private boolean auxiliaireAutorise;

    /** Toujours recommande au niveau du referentiel global (independant de l'usage par une
     * entreprise donnee - voir CompteComptable.actif). */
    @Column(nullable = false)
    private boolean actif;

    /** Role technique joue par ce compte dans les traitements automatiques (TVA, dotations,
     * resultat...), voir CompteSystemeResolver. Null pour la grande majorite des comptes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role_systeme")
    private RoleCompteSysteme roleSysteme;
}
