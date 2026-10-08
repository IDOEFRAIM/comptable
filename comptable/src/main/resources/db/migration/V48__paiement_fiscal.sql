-- Paiement direct des declarations fiscales aupres d'un fournisseur externe (e-Syntaxe, Faso
-- Arzeka - Burkina Faso). Une declaration peut avoir plusieurs tentatives (une precedente en
-- ECHEC n'empeche pas d'en relancer une nouvelle) : pas de contrainte d'unicite sur
-- declaration_fiscale_id.
CREATE TABLE paiement_fiscal (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    declaration_fiscale_id   UUID NOT NULL REFERENCES declaration_fiscale(id),
    fournisseur              VARCHAR(30) NOT NULL,
    statut                   VARCHAR(30) NOT NULL DEFAULT 'INITIE',
    montant                  NUMERIC(15,2) NOT NULL,
    numero_telephone_payeur  VARCHAR(20),
    reference_externe        VARCHAR(100),
    message_statut           VARCHAR(500),
    date_initiation          TIMESTAMPTZ NOT NULL,
    date_confirmation        TIMESTAMPTZ
);

CREATE INDEX idx_paiement_fiscal_declaration ON paiement_fiscal(declaration_fiscale_id);

-- Recherche par le webhook du fournisseur (confirmation asynchrone) : une reference externe est
-- unique chez un fournisseur donne, jamais partagee entre e-Syntaxe et Faso Arzeka.
CREATE UNIQUE INDEX idx_paiement_fiscal_fournisseur_reference
    ON paiement_fiscal(fournisseur, reference_externe)
    WHERE reference_externe IS NOT NULL;
