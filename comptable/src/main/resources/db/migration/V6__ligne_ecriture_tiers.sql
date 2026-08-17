ALTER TABLE ligne_ecriture ADD COLUMN tiers_id UUID REFERENCES tiers(id);

CREATE INDEX idx_ligne_ecriture_tiers ON ligne_ecriture(tiers_id);
