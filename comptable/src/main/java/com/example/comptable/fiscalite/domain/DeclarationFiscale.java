package com.example.comptable.fiscalite.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Une echeance fiscale (declaration + paiement) pour une entreprise et une periode donnees. */
@Entity
@Table(name = "declaration_fiscale")
@Getter
@Setter
@NoArgsConstructor
public class DeclarationFiscale extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_impot", nullable = false)
    private TypeImpot typeImpot;

    /** Ex: "2026-01" (mensuel), "2026-T1" (trimestriel), "2026" (annuel). */
    @Column(nullable = false)
    private String periode;

    @Column(name = "date_echeance", nullable = false)
    private LocalDate dateEcheance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutDeclaration statut = StatutDeclaration.A_FAIRE;

    private BigDecimal montant;

    @Column(name = "date_declaration")
    private LocalDate dateDeclaration;

    @Column(name = "date_paiement")
    private LocalDate datePaiement;

    @Column(name = "piece_jointe_url")
    private String pieceJointeUrl;

    public boolean estEnRetard() {
        return statut != StatutDeclaration.PAYEE && dateEcheance.isBefore(LocalDate.now());
    }
}
