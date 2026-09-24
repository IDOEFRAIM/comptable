package com.example.comptable.comptabilite.operation.common.domain;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
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

/** Ligne d'une {@link AccountingOperationProposal}. {@code compte} n'est jamais nullable : une
 * ligne dont le compte n'a pas pu etre resolu n'est simplement jamais creee (voir explicationsJson
 * sur le parent). */
@Entity
@Table(name = "accounting_operation_proposal_ligne")
@Getter
@Setter
@NoArgsConstructor
public class AccountingOperationProposalLigne extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private AccountingOperationProposal proposal;

    @Column(nullable = false)
    private int ordinal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_id", nullable = false)
    private CompteComptable compte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tiers_id")
    private Tiers tiers;

    @Column(nullable = false)
    private String libelle;

    @Column(nullable = false)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal credit = BigDecimal.ZERO;

    public static AccountingOperationProposalLigne debit(CompteComptable compte, BigDecimal montant, String libelle) {
        AccountingOperationProposalLigne ligne = new AccountingOperationProposalLigne();
        ligne.setCompte(compte);
        ligne.setLibelle(libelle);
        ligne.setDebit(montant);
        ligne.setCredit(BigDecimal.ZERO);
        return ligne;
    }

    public static AccountingOperationProposalLigne credit(CompteComptable compte, BigDecimal montant, String libelle) {
        AccountingOperationProposalLigne ligne = new AccountingOperationProposalLigne();
        ligne.setCompte(compte);
        ligne.setLibelle(libelle);
        ligne.setDebit(BigDecimal.ZERO);
        ligne.setCredit(montant);
        return ligne;
    }
}
