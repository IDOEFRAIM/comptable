-- Proposition d'ecriture pour les operations metier courantes (achat/vente/reglement/avoir),
-- mission "moteur comptable SYSCOHADA des operations courantes". Distincte de accounting_proposal
-- (V24, mission P0) : cette derniere est liee 1:1 a un Document et porte un seul compte scalaire
-- (proposition de comptabilisation issue de l'OCR) - pas de structure multi-lignes, pas de notion
-- d'equilibre. Ici, une operation peut produire plusieurs lignes (achat + TVA + fournisseur, par
-- exemple), d'ou une vraie table enfant plutot qu'une colonne JSONB : coherent avec
-- ligne_ecriture/ligne_facture qui sont deja des tables reelles dans ce projet, jamais du JSONB
-- pour des lignes porteuses de FK.
CREATE TABLE accounting_operation_proposal (
    id                    UUID PRIMARY KEY,
    entreprise_id         UUID NOT NULL REFERENCES entreprise(id),
    type                  VARCHAR(30) NOT NULL,
    tiers_id              UUID NOT NULL REFERENCES tiers(id),
    journal_id            UUID REFERENCES journal(id),
    date_operation        DATE NOT NULL,
    libelle               VARCHAR(255) NOT NULL,
    reference             VARCHAR(255),
    montant_total_debit   NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant_total_credit  NUMERIC(15,2) NOT NULL DEFAULT 0,
    equilibree            BOOLEAN NOT NULL DEFAULT false,
    statut                VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    explications_json     JSONB,
    warnings_json         JSONB,
    ecriture_id           UUID REFERENCES ecriture_comptable(id),
    created_at            TIMESTAMPTZ NOT NULL,
    validated_at          TIMESTAMPTZ,
    validated_by_id       UUID REFERENCES utilisateur(id),
    rejection_reason      VARCHAR(255)
);

-- Table enfant, jamais de compte NULL : soit une ligne est entierement resolue (compte reel du
-- PCE), soit elle n'existe pas du tout et le manque est explique dans explications_json - c'est le
-- mecanisme concret de "le moteur ne doit jamais inventer un compte".
CREATE TABLE accounting_operation_proposal_ligne (
    id           UUID PRIMARY KEY,
    proposal_id  UUID NOT NULL REFERENCES accounting_operation_proposal(id) ON DELETE CASCADE,
    ordinal      INT NOT NULL,
    compte_id    UUID NOT NULL REFERENCES compte_comptable(id),
    tiers_id     UUID REFERENCES tiers(id),
    libelle      VARCHAR(255) NOT NULL,
    debit        NUMERIC(15,2) NOT NULL DEFAULT 0,
    credit       NUMERIC(15,2) NOT NULL DEFAULT 0
);

CREATE INDEX idx_aop_entreprise ON accounting_operation_proposal(entreprise_id);
CREATE INDEX idx_aop_statut ON accounting_operation_proposal(statut);
CREATE INDEX idx_aop_ligne_proposal ON accounting_operation_proposal_ligne(proposal_id);
