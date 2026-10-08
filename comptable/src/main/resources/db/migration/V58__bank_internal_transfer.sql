-- Phase E (E0.2) : virement interne = deux jambes (sortie du compte A, entree du compte B). Ce n'est NI une charge, NI un produit, NI le reglement d'un
-- fournisseur, NI l'encaissement d'un client : les deux transactions sont liees entre elles, jamais interpretees separement.
CREATE TABLE bank_internal_transfer (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id         UUID NOT NULL REFERENCES entreprise(id),
    debit_transaction_id  UUID NOT NULL REFERENCES ligne_releve(id) ON DELETE CASCADE,
    credit_transaction_id UUID NOT NULL REFERENCES ligne_releve(id) ON DELETE CASCADE,
    amount                NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    decision              VARCHAR(12) NOT NULL,
    confidence            NUMERIC(5,2),
    source                VARCHAR(20) NOT NULL,
    status                VARCHAR(12) NOT NULL,
    -- ecriture comptable de transfert deja existante (une seule ecriture touchant les deux comptes) : jamais creee par ce module.
    ecriture_id           UUID REFERENCES ecriture_comptable(id),
    explanation_json      JSONB,
    created_by            UUID REFERENCES utilisateur(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_by          UUID REFERENCES utilisateur(id),
    confirmed_at          TIMESTAMPTZ,
    rejected_by           UUID REFERENCES utilisateur(id),
    rejected_at           TIMESTAMPTZ,
    reversed_by           UUID REFERENCES utilisateur(id),
    reversed_at           TIMESTAMPTZ,
    reversal_reason       VARCHAR(300),
    CONSTRAINT ck_bank_internal_transfer_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'REVERSED')),
    CONSTRAINT ck_bank_internal_transfer_legs CHECK (debit_transaction_id <> credit_transaction_id)
);
-- Une jambe n'appartient qu'a UN virement vivant (propose ou confirme) ; un rejet ou une annulation est memorise et n'est jamais repropose.
CREATE UNIQUE INDEX ux_bank_internal_transfer_debit ON bank_internal_transfer(debit_transaction_id) WHERE status IN ('PROPOSED', 'CONFIRMED');
CREATE UNIQUE INDEX ux_bank_internal_transfer_credit ON bank_internal_transfer(credit_transaction_id) WHERE status IN ('PROPOSED', 'CONFIRMED');
CREATE UNIQUE INDEX ux_bank_internal_transfer_pair ON bank_internal_transfer(debit_transaction_id, credit_transaction_id)
    WHERE status IN ('PROPOSED', 'CONFIRMED', 'REJECTED');
CREATE INDEX idx_bank_internal_transfer_entreprise ON bank_internal_transfer(entreprise_id, status);
