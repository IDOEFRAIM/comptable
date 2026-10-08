-- Phase D : identite stable des transactions bancaires, lot d'import tracable, soldes facultatifs.
-- ligne_releve reste LA transaction bancaire (pas de seconde table) : on l'etend.

CREATE TABLE bank_import_batch (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    compte_id           UUID NOT NULL REFERENCES compte_comptable(id),
    file_name           VARCHAR(300),
    file_hash           VARCHAR(64) NOT NULL,
    imported_by         UUID REFERENCES utilisateur(id),
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_rows          INTEGER NOT NULL,
    valid_rows          INTEGER NOT NULL,
    rejected_rows       INTEGER NOT NULL,
    exact_duplicates    INTEGER NOT NULL DEFAULT 0,
    possible_duplicates INTEGER NOT NULL DEFAULT 0,
    imported_rows       INTEGER NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'XOF',
    date_min            DATE,
    date_max            DATE,
    status              VARCHAR(20) NOT NULL,
    rejections_json     JSONB
);
CREATE INDEX idx_bank_import_batch_entreprise ON bank_import_batch(entreprise_id, imported_at DESC);
CREATE INDEX idx_bank_import_batch_hash ON bank_import_batch(entreprise_id, compte_id, file_hash);

-- Les soldes d'un releve sont facultatifs : « si disponibles » (verification d'equilibre quand les deux existent).
ALTER TABLE releve_bancaire ALTER COLUMN solde_debut DROP NOT NULL;
ALTER TABLE releve_bancaire ALTER COLUMN solde_fin DROP NOT NULL;
ALTER TABLE releve_bancaire ADD COLUMN import_batch_id UUID REFERENCES bank_import_batch(id);

ALTER TABLE ligne_releve ADD COLUMN entreprise_id       UUID;
ALTER TABLE ligne_releve ADD COLUMN compte_id           UUID;
ALTER TABLE ligne_releve ADD COLUMN date_valeur         DATE;
ALTER TABLE ligne_releve ADD COLUMN currency            VARCHAR(3) NOT NULL DEFAULT 'XOF';
ALTER TABLE ligne_releve ADD COLUMN direction           VARCHAR(6);
ALTER TABLE ligne_releve ADD COLUMN libelle_normalise   VARCHAR(300);
ALTER TABLE ligne_releve ADD COLUMN reference           VARCHAR(100);
ALTER TABLE ligne_releve ADD COLUMN counterparty_name   VARCHAR(200);
ALTER TABLE ligne_releve ADD COLUMN payment_method      VARCHAR(20);
ALTER TABLE ligne_releve ADD COLUMN invoice_hint        VARCHAR(100);
ALTER TABLE ligne_releve ADD COLUMN source              VARCHAR(20) NOT NULL DEFAULT 'CSV';
ALTER TABLE ligne_releve ADD COLUMN import_batch_id     UUID REFERENCES bank_import_batch(id);
ALTER TABLE ligne_releve ADD COLUMN content_fingerprint VARCHAR(64);
ALTER TABLE ligne_releve ADD COLUMN duplicate_status    VARCHAR(20) NOT NULL DEFAULT 'NONE';

UPDATE ligne_releve l
   SET entreprise_id = r.entreprise_id,
       compte_id = r.compte_id,
       direction = CASE WHEN l.montant >= 0 THEN 'CREDIT' ELSE 'DEBIT' END,
       libelle_normalise = upper(regexp_replace(trim(coalesce(l.libelle, '')), '\s+', ' ', 'g'))
  FROM releve_bancaire r
 WHERE r.id = l.releve_id;

ALTER TABLE ligne_releve ALTER COLUMN entreprise_id SET NOT NULL;
ALTER TABLE ligne_releve ALTER COLUMN compte_id SET NOT NULL;
ALTER TABLE ligne_releve ALTER COLUMN direction SET NOT NULL;
ALTER TABLE ligne_releve ADD CONSTRAINT ck_ligne_releve_direction CHECK (direction IN ('CREDIT', 'DEBIT'));

-- Identite d'une transaction : un meme releve reimporte ne cree aucun doublon. Les lignes historiques (sans empreinte) ne sont pas concernees.
CREATE UNIQUE INDEX ux_ligne_releve_fingerprint ON ligne_releve(entreprise_id, compte_id, content_fingerprint) WHERE content_fingerprint IS NOT NULL;
CREATE INDEX idx_ligne_releve_compte_date ON ligne_releve(entreprise_id, compte_id, date_operation);
