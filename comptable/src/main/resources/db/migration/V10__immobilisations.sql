-- Immobilisations et plan d'amortissement.

CREATE TABLE immobilisation (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id           UUID NOT NULL REFERENCES entreprise(id),
    compte_id               UUID NOT NULL REFERENCES compte_comptable(id),
    compte_amortissement_id UUID NOT NULL REFERENCES compte_comptable(id),
    libelle                 VARCHAR(200) NOT NULL,
    date_acquisition        DATE NOT NULL,
    valeur_origine          NUMERIC(15,2) NOT NULL,
    duree_annees            SMALLINT NOT NULL,
    mode                    VARCHAR(20) NOT NULL DEFAULT 'LINEAIRE',
    statut                  VARCHAR(20) NOT NULL DEFAULT 'EN_SERVICE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_immobilisation_entreprise ON immobilisation(entreprise_id);

CREATE TABLE dotation_amortissement (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    immobilisation_id UUID NOT NULL REFERENCES immobilisation(id) ON DELETE CASCADE,
    annee             SMALLINT NOT NULL,
    montant           NUMERIC(15,2) NOT NULL,
    ligne_ecriture_id UUID REFERENCES ligne_ecriture(id),
    UNIQUE (immobilisation_id, annee)
);
