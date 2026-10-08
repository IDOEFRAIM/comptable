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

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "compte_id", nullable = false)
    private UUID compteId;

    @Column(name = "date_valeur")
    private LocalDate dateValeur;

    @Column(nullable = false)
    private String currency = "XOF";

    /** CREDIT = entree d'argent (montant positif), DEBIT = sortie. Derive du signe, jamais saisi. */
    @Column(nullable = false)
    private String direction;

    /** Libelle normalise (casse, espaces, ponctuation) : sert au matching ; {@link #libelle} garde toujours l'original. */
    @Column(name = "libelle_normalise")
    private String libelleNormalise;

    @Column(name = "reference")
    private String reference;

    /** Indices extraits du libelle (jamais des verites) : nom probable du tiers, moyen de paiement, numero de facture probable. */
    @Column(name = "counterparty_name")
    private String counterpartyName;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "invoice_hint")
    private String invoiceHint;

    @Column(nullable = false)
    private String source = "CSV";

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    /** Identite stable de la transaction (SHA-256) : voir {@code BankFingerprint}. */
    @Column(name = "content_fingerprint")
    private String contentFingerprint;

    /** {@code NONE} ou {@code POSSIBLE_DUPLICATE} (meme compte, date, montant et sens qu'une autre ligne, mais autre libelle/reference). */
    @Column(name = "duplicate_status", nullable = false)
    private String duplicateStatus = "NONE";

    public LigneReleve(LocalDate dateOperation, String libelle, BigDecimal montant) {
        this.dateOperation = dateOperation;
        this.libelle = libelle;
        this.montant = montant;
        this.direction = montant.signum() >= 0 ? "CREDIT" : "DEBIT";
        this.libelleNormalise = com.example.comptable.banque.BankLabelNormalizer.normaliser(libelle);
    }
}
