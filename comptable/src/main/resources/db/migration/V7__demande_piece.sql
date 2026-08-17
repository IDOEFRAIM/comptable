-- Demandes de pieces cibles adressees par un collaborateur a une entreprise cliente :
-- le client les voit sur son portail et depose la piece directement dessus.

CREATE TABLE demande_piece (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id   UUID NOT NULL REFERENCES entreprise(id),
    libelle         VARCHAR(300) NOT NULL,
    description     TEXT,
    date_limite     DATE,
    statut          VARCHAR(20) NOT NULL DEFAULT 'EN_ATTENTE',
    document_id     UUID REFERENCES document(id),
    demandee_par_id UUID REFERENCES utilisateur(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolue_at      TIMESTAMPTZ
);

CREATE INDEX idx_demande_piece_entreprise_statut ON demande_piece(entreprise_id, statut);
