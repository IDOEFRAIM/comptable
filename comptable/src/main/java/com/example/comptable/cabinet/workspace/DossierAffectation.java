package com.example.comptable.cabinet.workspace;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Affectation d'un dossier (entreprise + exercice) : collaborateur principal et expert responsable. Seule donnée persistante propre au dossier, avec les échéances. */
@Entity
@Table(name = "dossier_affectation")
@Getter
@Setter
@NoArgsConstructor
public class DossierAffectation extends UuidEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "exercice_id", nullable = false)
    private UUID exerciceId;

    @Column(name = "collaborateur_id")
    private UUID collaborateurId;

    @Column(name = "expert_id")
    private UUID expertId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
