-- Phase H (Cabinet Workspace). Un « dossier » reste (entreprise, exercice) : seules deux informations PERSISTANTES nouvelles existent,
-- l'affectation (collaborateur principal, expert responsable) et les échéances. Tout le reste (statut opérationnel, progression, priorité) est calculé.

CREATE TABLE dossier_affectation (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    entreprise_id   UUID NOT NULL REFERENCES entreprise(id),
    exercice_id     UUID NOT NULL REFERENCES exercice_comptable(id),
    collaborateur_id UUID REFERENCES utilisateur(id),
    expert_id       UUID REFERENCES utilisateur(id),
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      UUID REFERENCES utilisateur(id),
    UNIQUE (entreprise_id, exercice_id)
);

CREATE INDEX idx_dossier_affectation_tenant ON dossier_affectation(tenant_id);
CREATE INDEX idx_dossier_affectation_collab ON dossier_affectation(collaborateur_id);
CREATE INDEX idx_dossier_affectation_expert ON dossier_affectation(expert_id);

CREATE TABLE dossier_echeance (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    entreprise_id   UUID NOT NULL REFERENCES entreprise(id),
    exercice_id     UUID REFERENCES exercice_comptable(id),
    type            VARCHAR(30) NOT NULL CHECK (type IN ('CLOTURE_MENSUELLE', 'CLOTURE_ANNUELLE', 'RELANCE_CLIENT', 'ECHEANCE_INTERNE')),
    libelle         VARCHAR(200) NOT NULL,
    date_echeance   DATE NOT NULL,
    responsable_id  UUID REFERENCES utilisateur(id),
    statut          VARCHAR(10) NOT NULL DEFAULT 'OUVERTE' CHECK (statut IN ('OUVERTE', 'FAITE')),
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_dossier_echeance_tenant_date ON dossier_echeance(tenant_id, date_echeance);
CREATE INDEX idx_dossier_echeance_entreprise ON dossier_echeance(entreprise_id);
