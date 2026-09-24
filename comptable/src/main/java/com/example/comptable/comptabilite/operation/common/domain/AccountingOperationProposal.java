package com.example.comptable.comptabilite.operation.common.domain;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Proposition d'ecriture multi-lignes pour une operation metier (achat/vente/reglement/avoir) -
 * distincte de {@code agent.comptabilisation.domain.AccountingProposal} (celle-ci reste liee 1:1 a
 * un Document et porte un seul compte scalaire, forme incompatible avec une operation
 * multi-lignes). Une ligne n'existe que si son compte a ete reellement resolu (jamais de compte
 * devine) : voir {@link AccountingOperationProposalLigne}.
 */
@Entity
@Table(name = "accounting_operation_proposal")
@Getter
@Setter
@NoArgsConstructor
public class AccountingOperationProposal extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountingOperationType type;

    /** Nullable : une regularisation d'inventaire (AccountingOperationType.REGULARISATION_INVENTAIRE)
     * n'a pas de tiers - ce n'est ni un achat, ni une vente, ni un reglement. Toutes les autres
     * operations (achat/vente/reglement/avoir) continuent de porter un tiers obligatoire, verifie
     * cote application par AccountingOperationEngine. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tiers_id")
    private Tiers tiers;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id")
    private Journal journal;

    @Column(name = "date_operation", nullable = false)
    private LocalDate dateOperation;

    @Column(nullable = false)
    private String libelle;

    private String reference;

    @Column(name = "montant_total_debit", nullable = false)
    private BigDecimal montantTotalDebit = BigDecimal.ZERO;

    @Column(name = "montant_total_credit", nullable = false)
    private BigDecimal montantTotalCredit = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean equilibree;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutOperationProposal statut = StatutOperationProposal.PROPOSED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explications_json")
    private List<String> explicationsJson = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings_json")
    private List<String> warningsJson = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ecriture_id")
    private EcritureComptable ecriture;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "validated_at")
    private OffsetDateTime validatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by_id")
    private Utilisateur validatedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    /** Identifie le document metier d'origine (ex: "FACTURE"/factureId, "EXERCICE"/exerciceId,
     * "REGULARISATION"/regularisationId) - nullable car les operations achat/vente/reglement/avoir/
     * inventaire deja en place n'en portent pas necessairement. Sert a la fois a la tracabilite
     * (section 23) et a l'idempotence (section 24) : voir AccountingOperationEngine.assemblerEtSauvegarder. */
    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordinal asc")
    private List<AccountingOperationProposalLigne> lignes = new ArrayList<>();

    public void ajouterLigne(AccountingOperationProposalLigne ligne) {
        ligne.setProposal(this);
        ligne.setOrdinal(lignes.size() + 1);
        lignes.add(ligne);
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
