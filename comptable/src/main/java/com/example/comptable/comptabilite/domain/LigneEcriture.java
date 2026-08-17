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

@Entity
@Table(name = "ligne_ecriture")
@Getter
@Setter
@NoArgsConstructor
public class LigneEcriture extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ecriture_id", nullable = false)
    private EcritureComptable ecriture;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_id", nullable = false)
    private CompteComptable compte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tiers_id")
    private Tiers tiers;

    private String libelle;

    @Column(nullable = false)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal credit = BigDecimal.ZERO;

    private String lettrage;

    /** Date d'application du lettrage (integral ou partiel), null tant que non lettree.
     * Distincte de la date de la piece : sert de trace d'audit et, pour un lettrage partiel,
     * date le rapprochement effectif du reglement plutot que la facture elle-meme. */
    @Column(name = "date_lettrage")
    private LocalDate dateLettrage;

    @Column(name = "numero_ligne", nullable = false)
    private Integer numeroLigne;

    public static LigneEcriture debit(CompteComptable compte, BigDecimal montant, String libelle) {
        LigneEcriture ligne = new LigneEcriture();
        ligne.setCompte(compte);
        ligne.setDebit(montant);
        ligne.setCredit(BigDecimal.ZERO);
        ligne.setLibelle(libelle);
        return ligne;
    }

    public static LigneEcriture credit(CompteComptable compte, BigDecimal montant, String libelle) {
        LigneEcriture ligne = new LigneEcriture();
        ligne.setCompte(compte);
        ligne.setDebit(BigDecimal.ZERO);
        ligne.setCredit(montant);
        ligne.setLibelle(libelle);
        return ligne;
    }
}
