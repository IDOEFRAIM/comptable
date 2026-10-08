package com.example.comptable.etatsfinanciers.snapshot;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Etat financier FINAL fige (immuable, y compris en base : trigger qui refuse UPDATE et DELETE). {@code payload} est le resultat complet (montants N et N-1, comptes
 * par ligne, controles, empreinte des donnees) tel que calcule au moment de la finalisation ; {@code definitionHash} fige la definition reglementaire utilisee.
 */
@Entity
@Table(name = "financial_statement_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class FinancialStatementSnapshot extends UuidEntity {

    @Column(name = "entreprise_id", nullable = false, updatable = false)
    private UUID entrepriseId;

    @Column(name = "exercice_id", nullable = false, updatable = false)
    private UUID exerciceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statement_type", nullable = false, updatable = false)
    private StatementType statementType;

    @Column(nullable = false, updatable = false)
    private String framework;

    @Column(name = "definition_version", nullable = false, updatable = false)
    private String definitionVersion;

    @Column(name = "definition_hash", nullable = false, updatable = false)
    private String definitionHash;

    @Column(nullable = false, updatable = false)
    private String status = "FINAL";

    @Column(name = "dataset_fingerprint", nullable = false, updatable = false)
    private String datasetFingerprint;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "payload_sha256", nullable = false, updatable = false)
    private String payloadSha256;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
