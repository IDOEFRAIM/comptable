-- Espace de travail des Notes annexes (F8.1) : une ligne par (entreprise, exercice, cadre, version de definition, note).
-- generated_data (calcule, remplace a chaque recalcul) et manual_data (saisi par un humain, jamais ecrase par un recalcul) sont deux colonnes distinctes.
-- Schema applicatif strict : sections et champs connus de la definition versionnee, types valides a l'ecriture ; versionnage optimiste (version).
CREATE TABLE financial_statement_note (
    id                         UUID PRIMARY KEY,
    entreprise_id              UUID NOT NULL REFERENCES entreprise(id),
    exercice_id                UUID NOT NULL REFERENCES exercice_comptable(id),
    framework                  VARCHAR(60) NOT NULL,
    definition_version         VARCHAR(20) NOT NULL,
    note_code                  VARCHAR(20) NOT NULL,
    applicability_status       VARCHAR(30) NOT NULL CHECK (applicability_status IN ('TO_DETERMINE', 'NOT_APPLICABLE_CANDIDATE', 'NOT_APPLICABLE', 'APPLICABLE')),
    applicability_decided_by   UUID REFERENCES utilisateur(id),
    applicability_decided_at   TIMESTAMPTZ,
    applicability_reason       TEXT,
    completion_status          VARCHAR(20) NOT NULL CHECK (completion_status IN ('INCOMPLETE', 'READY', 'MANUAL_REQUIRED', 'NOT_APPLICABLE')),
    generated_data             JSONB,
    manual_data                JSONB NOT NULL DEFAULT '{"sections":{}}'::jsonb,
    manual_comment             TEXT,
    prepared_by                UUID REFERENCES utilisateur(id),
    prepared_at                TIMESTAMPTZ,
    reviewed_by                UUID REFERENCES utilisateur(id),
    reviewed_at                TIMESTAMPTZ,
    version                    BIGINT NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    UNIQUE (entreprise_id, exercice_id, framework, definition_version, note_code)
);

CREATE INDEX idx_fs_note_exercice ON financial_statement_note(entreprise_id, exercice_id);
