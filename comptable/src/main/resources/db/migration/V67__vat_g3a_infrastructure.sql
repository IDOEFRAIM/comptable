-- Phase G3A — infrastructure TVA indépendante de la réglementation : verrouillage optimiste de la période, suivi interne des paiements, crédit d'ouverture.
-- Aucune règle fiscale n'est codée ici : ni taux, ni échéance, ni règle de report.

-- Jeton de concurrence de la période : toute mutation (décision, ajustement, transition, paiement, crédit) l'incrémente ; une écriture avec une version périmée est refusée (409).
ALTER TABLE tax_period ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Suivi INTERNE du paiement d'une déclaration finalisée (aucun télépaiement, aucune API de l'administration).
-- idempotency_key : un même appel rejoué ne double jamais le montant payé. Un paiement n'est jamais supprimé : il est annulé (REVERSED) avec motif.
CREATE TABLE tax_payment (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    entreprise_id    UUID NOT NULL REFERENCES entreprise(id),
    tax_period_id    UUID NOT NULL REFERENCES tax_period(id),
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    payment_date     DATE NOT NULL,
    reference        VARCHAR(100),
    source           VARCHAR(50) NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    status           VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVERSED')),
    reversed_by      UUID REFERENCES utilisateur(id),
    reversed_at      TIMESTAMPTZ,
    reverse_reason   TEXT,
    created_by       UUID REFERENCES utilisateur(id),
    created_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (tax_period_id, idempotency_key)
);

CREATE INDEX idx_tax_payment_period ON tax_payment(tax_period_id);
CREATE INDEX idx_tax_payment_tenant ON tax_payment(tenant_id, entreprise_id);

-- Crédit d'ouverture saisi (infrastructure générique) : il n'est jamais déduit d'une règle ni repris automatiquement de la période précédente.
-- status : TO_REVIEW tant qu'aucun cadre VERIFIED ne prévoit le report ; VALIDATED seulement si le cadre vérifié l'autorise. Une modification crée une nouvelle ligne (l'ancienne est marquée superseded).
CREATE TABLE tax_opening_credit (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant(id),
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    tax_period_id       UUID NOT NULL REFERENCES tax_period(id),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    reason              TEXT NOT NULL,
    source              VARCHAR(200) NOT NULL,
    previous_period_id  UUID REFERENCES tax_period(id),
    status              VARCHAR(12) NOT NULL CHECK (status IN ('TO_REVIEW', 'VALIDATED')),
    created_by          UUID REFERENCES utilisateur(id),
    created_at          TIMESTAMPTZ NOT NULL,
    superseded_at       TIMESTAMPTZ,
    superseded_by       UUID REFERENCES utilisateur(id)
);

CREATE UNIQUE INDEX uq_tax_opening_credit_active ON tax_opening_credit(tax_period_id) WHERE superseded_at IS NULL;

-- Recherche et tri SQL des opérations de TVA (filtre sur l'écriture).
CREATE INDEX IF NOT EXISTS idx_ecriture_entreprise_date ON ecriture_comptable(entreprise_id, date_ecriture);
