-- Rapprochement bancaire : import d'un releve (CSV) et pointage de ses lignes contre les
-- lignes d'ecriture du compte de tresorerie concerne.

CREATE TABLE releve_bancaire (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    compte_id     UUID NOT NULL REFERENCES compte_comptable(id),
    libelle       VARCHAR(200) NOT NULL,
    date_debut    DATE NOT NULL,
    date_fin      DATE NOT NULL,
    solde_debut   NUMERIC(15,2) NOT NULL,
    solde_fin     NUMERIC(15,2) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_releve_bancaire_compte ON releve_bancaire(compte_id);

CREATE TABLE ligne_releve (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    releve_id         UUID NOT NULL REFERENCES releve_bancaire(id) ON DELETE CASCADE,
    date_operation    DATE NOT NULL,
    libelle           VARCHAR(300),
    montant           NUMERIC(15,2) NOT NULL,
    ligne_ecriture_id UUID REFERENCES ligne_ecriture(id)
);

CREATE INDEX idx_ligne_releve_releve ON ligne_releve(releve_id);
CREATE INDEX idx_ligne_releve_ecriture ON ligne_releve(ligne_ecriture_id);
