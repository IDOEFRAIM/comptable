-- Abstraction legere d'emplacement/depot (mission stock, section 15 "TRANSFERTS") : prepare le
-- terrain pour des transferts inter-depots tracables, sans construire un vrai modele
-- multi-etablissements (aucun compte de liaison inter-etablissement n'est introduit ici).
CREATE TABLE depot (
    id            UUID PRIMARY KEY,
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    code          VARCHAR(30) NOT NULL,
    libelle       VARCHAR(200) NOT NULL,
    actif         BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL,
    UNIQUE(entreprise_id, code)
);
