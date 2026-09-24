package com.example.comptable.fiscalite.regles.domain;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Regle fiscale versionnee par pays/regime (mission P1, section 31). Architecture prete a etre
 * peuplee, mais volontairement VIDE de toute donnee reelle dans ce chantier : aucune source
 * reglementaire verifiee pour le Burkina Faso n'est disponible dans cet environnement, et l'IA
 * n'est jamais la source juridique. Peupler cette table est une dependance P2 explicite (voir
 * docs/P1_ARCHITECTURE.md), pas un oubli.
 */
@Entity
@Table(name = "tax_rule")
@Getter
@Setter
@NoArgsConstructor
public class TaxRule extends UuidEntity {

    @Column(nullable = false, length = 2)
    private String pays;

    @Column(nullable = false, length = 100)
    private String regime;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false)
    private String libelle;

    private String description;

    /** Taux en pourcentage (ex. 18.00 pour 18%). Nullable : une TaxRule peut exister sans taux
     * chiffre tant que la source n'est pas verifiee - voir TvaRateResolver, qui traite l'absence
     * de taux comme "non supporte", jamais comme zero. */
    private BigDecimal taux;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
