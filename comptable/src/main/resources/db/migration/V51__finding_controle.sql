-- Phase B (B1/B2) : le Finding devient l'unite UNIQUE de travail humain, produite a partir de ControlResult.
-- Cle de deduplication metier : (entreprise, control_code, source_type = entityType, source_id = entityId)
-- tant que le constat est actif (OPEN / IN_PROGRESS). Un constat ecarte (DISMISSED) ou resolu reste en base
-- (auditable) ; il n'est jamais supprime.

ALTER TABLE finding ADD COLUMN control_code        VARCHAR(80);
ALTER TABLE finding ADD COLUMN category            VARCHAR(30);
ALTER TABLE finding ADD COLUMN control_severity    VARCHAR(20);
ALTER TABLE finding ADD COLUMN resolution_type     VARCHAR(20);
ALTER TABLE finding ADD COLUMN resolution_comment  VARCHAR(1000);
ALTER TABLE finding ADD COLUMN last_detected_at    TIMESTAMPTZ;

UPDATE finding SET
    control_code = COALESCE(evidence_json ->> 'controlCode', type),
    control_severity = COALESCE(evidence_json ->> 'controlSeverity',
        CASE severity WHEN 'CRITICAL' THEN 'BLOCKING' WHEN 'IMPORTANT' THEN 'WARNING' ELSE 'INFO' END),
    category = CASE type
        WHEN 'MATCH_AMBIGUOUS' THEN 'RECONCILIATION'
        WHEN 'LETTRAGE_AMBIGU' THEN 'RECONCILIATION'
        WHEN 'BANK_DIFFERENCE' THEN 'RECONCILIATION'
        WHEN 'MISSING_EVIDENCE' THEN 'DOCUMENT'
        WHEN 'DOCUMENT_REVIEW' THEN 'DOCUMENT'
        WHEN 'TAX_REVIEW' THEN 'TAX'
        WHEN 'CLOSING_BLOCKER' THEN 'CLOSING'
        WHEN 'ANOMALY' THEN 'ANOMALY'
        ELSE 'ACCOUNTING' END,
    last_detected_at = created_at,
    resolution_type = CASE status WHEN 'RESOLVED' THEN 'MANUAL' WHEN 'DISMISSED' THEN 'DISMISSED' ELSE NULL END,
    resolution_comment = rejection_reason;

ALTER TABLE finding ALTER COLUMN control_code SET NOT NULL;
ALTER TABLE finding ALTER COLUMN category SET NOT NULL;
ALTER TABLE finding ALTER COLUMN control_severity SET NOT NULL;
ALTER TABLE finding ALTER COLUMN last_detected_at SET NOT NULL;

-- Un seul constat actif par (controle, objet) : garanti par la base, pas seulement par le code.
CREATE UNIQUE INDEX uq_finding_actif ON finding(entreprise_id, control_code, source_type, source_id)
    WHERE status IN ('OPEN', 'IN_PROGRESS');
CREATE INDEX idx_finding_revue ON finding(entreprise_id, status, category, control_severity);

-- Derniere execution des detecteurs par entreprise : evite de tout recalculer a chaque GET.
CREATE TABLE control_run (
    id               UUID PRIMARY KEY,
    entreprise_id    UUID NOT NULL REFERENCES entreprise(id),
    started_at       TIMESTAMPTZ NOT NULL,
    duration_ms      BIGINT NOT NULL,
    detected_count   INTEGER NOT NULL,
    created_count    INTEGER NOT NULL,
    updated_count    INTEGER NOT NULL,
    resolved_count   INTEGER NOT NULL,
    detail_json      JSONB
);
CREATE INDEX idx_control_run_entreprise ON control_run(entreprise_id, started_at DESC);
