-- Tiers (clients/fournisseurs) d'une entreprise. Chaque tiers est adosse a un
-- sous-compte auxiliaire dedie (ex: 411001) cree automatiquement, pour permettre
-- le lettrage et le suivi individuel des soldes.

CREATE TABLE tiers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    type                VARCHAR(20) NOT NULL,  -- CLIENT, FOURNISSEUR
    nom                 VARCHAR(200) NOT NULL,
    numero_contribuable VARCHAR(50),
    compte_comptable_id UUID NOT NULL REFERENCES compte_comptable(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (compte_comptable_id)
);

CREATE INDEX idx_tiers_entreprise ON tiers(entreprise_id, type);
