-- Parametrage comptable par entreprise (mission Etape 6) : associe un AccountPurpose (+ un
-- contexte optionnel) a un compte du PCE de l'entreprise. Complete AccountingAccountResolver, ne
-- duplique pas CompteSystemeResolver (role_systeme, deja existant, reste utilise en dernier
-- recours par le resolveur).
CREATE TABLE accounting_account_mapping (
    id             UUID PRIMARY KEY,
    entreprise_id  UUID NOT NULL REFERENCES entreprise(id),
    purpose        VARCHAR(40) NOT NULL,
    context_key    VARCHAR(60),
    compte_id      UUID NOT NULL REFERENCES compte_comptable(id),
    actif          BOOLEAN NOT NULL DEFAULT true,
    priorite       INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ,
    created_by     UUID REFERENCES utilisateur(id)
);

CREATE INDEX idx_aam_entreprise ON accounting_account_mapping(entreprise_id);
CREATE INDEX idx_aam_compte ON accounting_account_mapping(compte_id);

-- Un doublon actif de meme (purpose, contextKey) est rendu impossible en base plutot que
-- departage arbitrairement au moment de la resolution (section 8-9 de la mission) : un index
-- partiel classique UNIQUE(entreprise_id, purpose, context_key) ne suffit pas, Postgres ne
-- considere jamais deux NULL comme egaux dans une contrainte unique - d'ou les deux index
-- separes ci-dessous (l'un pour le mapping generique sans contexte, l'autre pour les mappings
-- contextuels).
CREATE UNIQUE INDEX idx_aam_unique_generique ON accounting_account_mapping(entreprise_id, purpose)
    WHERE actif = true AND context_key IS NULL;
CREATE UNIQUE INDEX idx_aam_unique_contextuel ON accounting_account_mapping(entreprise_id, purpose, context_key)
    WHERE actif = true AND context_key IS NOT NULL;
