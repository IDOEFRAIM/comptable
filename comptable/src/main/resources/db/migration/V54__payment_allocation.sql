-- Phase D : affectation ECONOMIQUE d'un reglement a une facture, montant par montant.
-- La verite du paiement reste l'ecriture comptable : une allocation relie la LIGNE TIERS d'un reglement a la LIGNE TIERS d'une facture.
-- Jamais supprimee physiquement : une allocation annulee passe REVERSED (auteur, date, motif) et ne compte plus.
CREATE TABLE payment_allocation (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id    UUID NOT NULL REFERENCES entreprise(id),
    payment_line_id  UUID NOT NULL REFERENCES ligne_ecriture(id),
    invoice_line_id  UUID NOT NULL REFERENCES ligne_ecriture(id),
    allocated_amount NUMERIC(15,2) NOT NULL CHECK (allocated_amount > 0),
    currency         VARCHAR(3) NOT NULL DEFAULT 'XOF',
    source           VARCHAR(30) NOT NULL,
    status           VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    created_by       UUID REFERENCES utilisateur(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversed_by      UUID REFERENCES utilisateur(id),
    reversed_at      TIMESTAMPTZ,
    reversal_reason  VARCHAR(300),
    CONSTRAINT ck_alloc_status CHECK (status IN ('ACTIVE', 'REVERSED'))
);
-- Une seule allocation ACTIVE par couple de lignes : une double confirmation ne cree rien de plus.
CREATE UNIQUE INDEX ux_payment_allocation_active_pair ON payment_allocation(payment_line_id, invoice_line_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_payment_allocation_payment ON payment_allocation(payment_line_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_payment_allocation_invoice ON payment_allocation(invoice_line_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_payment_allocation_entreprise ON payment_allocation(entreprise_id);
