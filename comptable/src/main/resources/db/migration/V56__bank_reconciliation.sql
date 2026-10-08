-- Phase D : le rapprochement bancaire devient une entite metier tracable (qui, quand, pourquoi, avec quelle confiance), annulable.
-- ligne_releve.ligne_ecriture_id reste le pointeur lu par l'existant ; il n'est desormais ecrit QUE par BankReconciliationService
-- (invariant : une reconciliation CONFIRMED <=> ligne_ecriture_id renseigne).
CREATE TABLE bank_reconciliation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    bank_transaction_id UUID NOT NULL REFERENCES ligne_releve(id) ON DELETE CASCADE,
    target_type         VARCHAR(30) NOT NULL DEFAULT 'LIGNE_ECRITURE',
    target_id           UUID NOT NULL,
    decision            VARCHAR(12) NOT NULL,
    confidence          NUMERIC(5,2),
    source              VARCHAR(20) NOT NULL,
    status              VARCHAR(12) NOT NULL,
    explanation_json    JSONB,
    created_by          UUID REFERENCES utilisateur(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_by        UUID REFERENCES utilisateur(id),
    confirmed_at        TIMESTAMPTZ,
    rejected_by         UUID REFERENCES utilisateur(id),
    rejected_at         TIMESTAMPTZ,
    reversed_by         UUID REFERENCES utilisateur(id),
    reversed_at         TIMESTAMPTZ,
    reversal_reason     VARCHAR(300),
    CONSTRAINT ck_bank_reconciliation_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'REVERSED')),
    CONSTRAINT ck_bank_reconciliation_source CHECK (source IN ('SYSTEM_EXACT', 'SYSTEM_PROPOSAL', 'USER', 'AI_PROPOSAL', 'LEGACY'))
);

-- Une transaction n'a qu'UN rapprochement confirme, et une ecriture n'est confirmee que pour UNE transaction.
CREATE UNIQUE INDEX ux_bank_reconciliation_confirmed_tx ON bank_reconciliation(bank_transaction_id) WHERE status = 'CONFIRMED';
CREATE UNIQUE INDEX ux_bank_reconciliation_confirmed_target ON bank_reconciliation(target_type, target_id) WHERE status = 'CONFIRMED';
-- Un seul etat « vivant » par couple transaction/cible : rejouer le matching ne cree rien de plus, et un rejet est memorise.
CREATE UNIQUE INDEX ux_bank_reconciliation_pair ON bank_reconciliation(bank_transaction_id, target_type, target_id)
    WHERE status IN ('PROPOSED', 'CONFIRMED', 'REJECTED');
CREATE INDEX idx_bank_reconciliation_entreprise ON bank_reconciliation(entreprise_id, status);

-- Reprise de l'existant : chaque pointage historique devient un rapprochement confirme d'origine LEGACY. Une ecriture pointee par deux
-- transactions (possible avec l'ancien code, qui ne verifiait rien) n'est retenue que pour la premiere ; l'autre pointeur, erronne, est retire.
INSERT INTO bank_reconciliation (id, entreprise_id, bank_transaction_id, target_type, target_id, decision, confidence, source, status, confirmed_at)
SELECT gen_random_uuid(), entreprise_id, id, 'LIGNE_ECRITURE', ligne_ecriture_id, 'MANUAL', 100, 'LEGACY', 'CONFIRMED', now()
  FROM (SELECT DISTINCT ON (ligne_ecriture_id) id, entreprise_id, ligne_ecriture_id
          FROM ligne_releve WHERE ligne_ecriture_id IS NOT NULL ORDER BY ligne_ecriture_id, date_operation, id) premiers;

UPDATE ligne_releve l SET ligne_ecriture_id = NULL
 WHERE l.ligne_ecriture_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM bank_reconciliation b WHERE b.bank_transaction_id = l.id AND b.status = 'CONFIRMED');
