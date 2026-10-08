package com.example.comptable.fiscalite.paiement.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
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
 * Tentative de telepaiement d'une {@link DeclarationFiscale} aupres d'un fournisseur externe
 * (e-Syntaxe, Faso Arzeka). Une declaration peut avoir plusieurs tentatives : une precedente en
 * {@link StatutPaiement#ECHEC} n'empeche pas d'en relancer une nouvelle.
 */
@Entity
@Table(name = "paiement_fiscal")
@Getter
@Setter
@NoArgsConstructor
public class PaiementFiscal extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "declaration_fiscale_id", nullable = false)
    private DeclarationFiscale declarationFiscale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaiementFournisseur fournisseur;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutPaiement statut = StatutPaiement.INITIE;

    @Column(nullable = false)
    private BigDecimal montant;

    /** Mobile money uniquement (Faso Arzeka) ; ignore par e-Syntaxe. */
    @Column(name = "numero_telephone_payeur")
    private String numeroTelephonePayeur;

    /** Identifiant attribue par le fournisseur, utilise pour la confirmation asynchrone (webhook). */
    @Column(name = "reference_externe")
    private String referenceExterne;

    @Column(name = "message_statut", length = 500)
    private String messageStatut;

    @Column(name = "date_initiation", nullable = false)
    private OffsetDateTime dateInitiation;

    @Column(name = "date_confirmation")
    private OffsetDateTime dateConfirmation;

    @PrePersist
    private void onCreate() {
        if (dateInitiation == null) {
            dateInitiation = OffsetDateTime.now();
        }
    }
}
