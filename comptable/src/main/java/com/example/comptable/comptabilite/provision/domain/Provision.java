package com.example.comptable.comptabilite.provision.domain;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.identite.domain.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Provision (dotation/reprise totale/reprise partielle - voir {@link TypeMouvementProvision}).
 * nature/objet restent du texte libre : aucune liste fermee de natures de provision n'est
 * verifiee dans ce perimetre, en inventer une serait deviner. Les 3 comptes sont toujours saisis
 * et valides par l'utilisateur (voir ProvisionRule / CompteComptableValidationService), jamais
 * resolus via un role systeme non seede pour ce cas.
 */
@Entity
@Table(name = "provision")
@Getter
@Setter
@NoArgsConstructor
public class Provision extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false)
    private ExerciceComptable exercice;

    @Column(nullable = false)
    private String nature;

    private String objet;

    @Column(name = "montant_precedent", nullable = false)
    private BigDecimal montantPrecedent = BigDecimal.ZERO;

    @Column(name = "montant_nouveau", nullable = false)
    private BigDecimal montantNouveau;

    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_mouvement", nullable = false)
    private TypeMouvementProvision typeMouvement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_dotation_id", nullable = false)
    private CompteComptable compteDotation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_provision_id", nullable = false)
    private CompteComptable compteProvision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_reprise_id", nullable = false)
    private CompteComptable compteReprise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutProvision statut = StatutProvision.BROUILLON;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposition_id")
    private AccountingOperationProposal proposition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Utilisateur createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
