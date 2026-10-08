package com.example.comptable.controle;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Journal d'une execution des detecteurs pour une entreprise : sert a ne pas tout recalculer a chaque lecture. */
@Entity
@Table(name = "control_run")
@Getter
@Setter
@NoArgsConstructor
public class ControlRun extends UuidEntity {

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "detected_count", nullable = false)
    private int detectedCount;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "resolved_count", nullable = false)
    private int resolvedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json")
    private Map<String, Object> detail;
}
