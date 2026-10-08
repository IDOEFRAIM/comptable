-- Phase G (G0/G1) — Fiscal Core + TVA. Aucune règle fiscale n'est semée : taux, périodicité, échéances et cases viennent d'un cadre fiscal (tax_framework) VERIFIED par un humain,
-- avec ses sources. Un cadre DRAFT ne produit jamais une déclaration FINAL.

-- Cadre fiscal versionné (jurisdiction + type d'impôt + version). tenant_id NULL = cadre de plateforme ; sinon cadre propre au cabinet (prioritaire à la résolution).
CREATE TABLE tax_framework (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID REFERENCES tenant(id),
    jurisdiction       VARCHAR(2) NOT NULL,
    tax_type           VARCHAR(20) NOT NULL,
    version            VARCHAR(30) NOT NULL,
    effective_from     DATE NOT NULL,
    effective_to       DATE,
    status             VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT', 'VERIFIED', 'RETIRED')),
    rules              JSONB NOT NULL,
    source_references  JSONB NOT NULL DEFAULT '[]'::jsonb,
    definition_hash    VARCHAR(64) NOT NULL,
    verified_by        UUID REFERENCES utilisateur(id),
    verified_at        TIMESTAMPTZ,
    created_by         UUID REFERENCES utilisateur(id),
    created_at         TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, jurisdiction, tax_type, version)
);

CREATE INDEX idx_tax_framework_resolution ON tax_framework(jurisdiction, tax_type, status);

-- Période déclarative (distincte de l'exercice comptable).
CREATE TABLE tax_period (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant(id),
    entreprise_id      UUID NOT NULL REFERENCES entreprise(id),
    tax_type           VARCHAR(20) NOT NULL,
    period_start       DATE NOT NULL,
    period_end         DATE NOT NULL,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'PREPARING', 'READY_FOR_REVIEW', 'REVIEWED', 'FINAL')),
    framework_id       UUID REFERENCES tax_framework(id),
    framework_version  VARCHAR(30),
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CHECK (period_end >= period_start),
    UNIQUE (entreprise_id, tax_type, period_start, period_end)
);

CREATE INDEX idx_tax_period_tenant_status ON tax_period(tenant_id, status);
CREATE INDEX idx_tax_period_entreprise ON tax_period(entreprise_id);

-- Décision fiscale humaine sur une opération (écriture) : jamais une modification de l'écriture source.
CREATE TABLE tax_operation_treatment (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant(id),
    entreprise_id      UUID NOT NULL REFERENCES entreprise(id),
    ecriture_id        UUID NOT NULL REFERENCES ecriture_comptable(id),
    treatment          VARCHAR(30) NOT NULL CHECK (treatment IN ('OUTPUT_TAX', 'INPUT_TAX', 'EXEMPT', 'OUT_OF_SCOPE', 'NON_DEDUCTIBLE', 'PARTIALLY_DEDUCTIBLE')),
    deductible_percent NUMERIC(7, 4),
    reason             TEXT NOT NULL,
    decided_by         UUID REFERENCES utilisateur(id),
    decided_at         TIMESTAMPTZ NOT NULL,
    UNIQUE (entreprise_id, ecriture_id)
);

-- Ajustement fiscal justifié (correction, régularisation, prorata, non-déductibilité, arrondi, manuel). Annulable, jamais supprimé.
CREATE TABLE tax_adjustment (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant(id),
    entreprise_id      UUID NOT NULL REFERENCES entreprise(id),
    tax_period_id      UUID NOT NULL REFERENCES tax_period(id),
    kind               VARCHAR(20) NOT NULL CHECK (kind IN ('CORRECTION', 'REGULARISATION', 'PRORATA', 'NON_DEDUCTIBILITY', 'ROUNDING', 'MANUAL')),
    effect             VARCHAR(10) NOT NULL CHECK (effect IN ('OUTPUT', 'INPUT')),
    amount             NUMERIC(19, 4) NOT NULL,
    reason             TEXT NOT NULL,
    evidence           TEXT,
    ecriture_id        UUID REFERENCES ecriture_comptable(id),
    created_by         UUID REFERENCES utilisateur(id),
    created_at         TIMESTAMPTZ NOT NULL,
    cancelled_at       TIMESTAMPTZ,
    cancelled_by       UUID REFERENCES utilisateur(id),
    cancel_reason      TEXT
);

CREATE INDEX idx_tax_adjustment_period ON tax_adjustment(tax_period_id);

CREATE TABLE tax_declaration (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant(id),
    entreprise_id      UUID NOT NULL REFERENCES entreprise(id),
    tax_period_id      UUID NOT NULL UNIQUE REFERENCES tax_period(id),
    tax_type           VARCHAR(20) NOT NULL,
    framework_id       UUID REFERENCES tax_framework(id),
    framework_version  VARCHAR(30),
    status             VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT', 'READY', 'REVIEWED', 'FINAL')),
    computed_data      JSONB,
    manual_data        JSONB NOT NULL DEFAULT '{}'::jsonb,
    controls           JSONB NOT NULL DEFAULT '[]'::jsonb,
    reviewed_by        UUID REFERENCES utilisateur(id),
    reviewed_at        TIMESTAMPTZ,
    finalized_by       UUID REFERENCES utilisateur(id),
    finalized_at       TIMESTAMPTZ,
    snapshot_id        UUID,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

-- Snapshot fiscal FINAL : immuable (aucune mise à jour ni suppression).
CREATE TABLE tax_declaration_snapshot (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant(id),
    entreprise_id      UUID NOT NULL REFERENCES entreprise(id),
    tax_period_id      UUID NOT NULL UNIQUE REFERENCES tax_period(id),
    declaration_id     UUID NOT NULL REFERENCES tax_declaration(id),
    framework_id       UUID NOT NULL REFERENCES tax_framework(id),
    framework_version  VARCHAR(30) NOT NULL,
    definition_hash    VARCHAR(64) NOT NULL,
    payload            TEXT NOT NULL,
    payload_sha256     VARCHAR(64) NOT NULL,
    created_by         UUID REFERENCES utilisateur(id),
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION tax_declaration_snapshot_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Un snapshot fiscal FINAL est immuable (aucune mise a jour ni suppression)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tax_declaration_snapshot_immutable
    BEFORE UPDATE OR DELETE ON tax_declaration_snapshot
    FOR EACH ROW EXECUTE FUNCTION tax_declaration_snapshot_immutable();

-- Les échéances du cabinet peuvent porter une déclaration fiscale (date fournie par le cadre fiscal VERIFIED, jamais codée en dur).
ALTER TABLE dossier_echeance DROP CONSTRAINT dossier_echeance_type_check;
ALTER TABLE dossier_echeance ADD CONSTRAINT dossier_echeance_type_check
    CHECK (type IN ('CLOTURE_MENSUELLE', 'CLOTURE_ANNUELLE', 'RELANCE_CLIENT', 'ECHEANCE_INTERNE', 'DECLARATION_FISCALE'));
