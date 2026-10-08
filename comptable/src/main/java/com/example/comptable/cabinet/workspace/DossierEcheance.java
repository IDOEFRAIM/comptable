package com.example.comptable.cabinet.workspace;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Échéance interne du cabinet sur un dossier (première notion : ni fiscalité complète ni télédéclaration). */
@Entity
@Table(name = "dossier_echeance")
@Getter
@Setter
@NoArgsConstructor
public class DossierEcheance extends UuidEntity {

    public enum Type {
        CLOTURE_MENSUELLE("Clôture mensuelle"),
        CLOTURE_ANNUELLE("Clôture annuelle"),
        RELANCE_CLIENT("Relance client"),
        ECHEANCE_INTERNE("Échéance interne"),
        DECLARATION_FISCALE("Déclaration fiscale");

        private final String libelle;

        Type(String libelle) {
            this.libelle = libelle;
        }

        public String libelle() {
            return libelle;
        }
    }

    public enum Statut {
        OUVERTE,
        FAITE
    }

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "exercice_id")
    private UUID exerciceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String libelle;

    @Column(name = "date_echeance", nullable = false)
    private LocalDate dateEcheance;

    @Column(name = "responsable_id")
    private UUID responsableId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Statut statut = Statut.OUVERTE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
