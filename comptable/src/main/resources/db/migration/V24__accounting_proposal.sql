-- Proposition de comptabilisation Document -> Ecriture (mission P0, section 14/15) : distincte de
-- ai_match_proposal (rapprochement) car un domaine et une forme differents (journal + tiers +
-- compte + repartition TVA proposes, pas un simple candidat/score). Meme discipline "l'IA
-- propose, Java decide" : cette table trace la proposition, jamais l'ecriture elle-meme
-- (ComptabilisationService.comptabiliser reste le seul point d'ecriture reelle).
CREATE TABLE accounting_proposal (
    id                          UUID PRIMARY KEY,
    entreprise_id               UUID NOT NULL REFERENCES entreprise(id),
    document_id                 UUID NOT NULL REFERENCES document(id),
    journal_id                  UUID REFERENCES journal(id),
    tiers_id                    UUID REFERENCES tiers(id),
    compte_charge_produit_id    UUID REFERENCES compte_comptable(id),
    montant_ht                  NUMERIC(15,2),
    montant_tva                 NUMERIC(15,2),
    montant_ttc                 NUMERIC(15,2),
    confidence                  DOUBLE PRECISION NOT NULL,
    status                      VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    reasons_json                JSONB,
    created_at                  TIMESTAMPTZ NOT NULL,
    validated_at                TIMESTAMPTZ,
    validated_by_id             UUID REFERENCES utilisateur(id)
);

CREATE INDEX idx_accounting_proposal_entreprise ON accounting_proposal(entreprise_id);
CREATE INDEX idx_accounting_proposal_document ON accounting_proposal(document_id);
CREATE INDEX idx_accounting_proposal_status ON accounting_proposal(status);
