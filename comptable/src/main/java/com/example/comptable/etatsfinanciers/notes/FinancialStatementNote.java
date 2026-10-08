package com.example.comptable.etatsfinanciers.notes;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Etat de travail persistant d'une note annexe pour un exercice. {@code generatedData} (calcule) et {@code manualData} (saisie humaine) sont deux colonnes distinctes : un recalcul
 * remplace la premiere, jamais la seconde. {@code applicabilityDecidedBy} non nul = decision humaine ; nul = statut calcule (recalcule a chaque lecture).
 */
@Entity
@jakarta.persistence.EntityListeners(com.example.comptable.etatsfinanciers.sync.NoteControlListener.class)
@Table(name = "financial_statement_note")
@Getter
@Setter
@NoArgsConstructor
public class FinancialStatementNote extends UuidEntity {

    @Column(name = "entreprise_id", nullable = false, updatable = false)
    private UUID entrepriseId;

    @Column(name = "exercice_id", nullable = false, updatable = false)
    private UUID exerciceId;

    @Column(nullable = false, updatable = false)
    private String framework;

    @Column(name = "definition_version", nullable = false, updatable = false)
    private String definitionVersion;

    @Column(name = "note_code", nullable = false, updatable = false)
    private String noteCode;

    @Column(name = "applicability_status", nullable = false)
    private String applicabilityStatus;

    @Column(name = "applicability_decided_by")
    private UUID applicabilityDecidedBy;

    @Column(name = "applicability_decided_at")
    private OffsetDateTime applicabilityDecidedAt;

    @Column(name = "applicability_reason")
    private String applicabilityReason;

    @Column(name = "completion_status", nullable = false)
    private String completionStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_data", columnDefinition = "jsonb")
    private String generatedData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manual_data", nullable = false, columnDefinition = "jsonb")
    private String manualData = "{\"sections\":{}}";

    @Column(name = "manual_comment")
    private String manualComment;

    @Column(name = "prepared_by")
    private UUID preparedBy;

    @Column(name = "prepared_at")
    private OffsetDateTime preparedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void avantCreation() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void avantMiseAJour() {
        updatedAt = OffsetDateTime.now();
    }
}
