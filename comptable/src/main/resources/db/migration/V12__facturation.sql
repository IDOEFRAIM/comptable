-- Devis et factures clients. Meme table pour les deux types (structure identique, seul le
-- cycle de vie differe) : un devis accepte se convertit en facture via devis_origine_id.

CREATE TABLE facture (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id    UUID NOT NULL REFERENCES entreprise(id),
    tiers_id         UUID NOT NULL REFERENCES tiers(id),
    type             VARCHAR(20) NOT NULL,
    numero           VARCHAR(30) NOT NULL,
    date_emission    DATE NOT NULL,
    date_echeance    DATE,
    statut           VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    objet            VARCHAR(300),
    devis_origine_id UUID REFERENCES facture(id),
    ecriture_id      UUID REFERENCES ecriture_comptable(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entreprise_id, numero)
);

CREATE INDEX idx_facture_entreprise_type ON facture(entreprise_id, type);

CREATE TABLE ligne_facture (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    facture_id    UUID NOT NULL REFERENCES facture(id) ON DELETE CASCADE,
    designation   VARCHAR(300) NOT NULL,
    quantite      NUMERIC(12,2) NOT NULL,
    prix_unitaire NUMERIC(15,2) NOT NULL,
    taux_tva      NUMERIC(5,2) NOT NULL DEFAULT 0,
    numero_ligne  INT NOT NULL
);

CREATE INDEX idx_ligne_facture_facture ON ligne_facture(facture_id);
