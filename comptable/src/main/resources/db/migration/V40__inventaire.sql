-- Inventaire physique et regularisation d'ecart (mission stock, sections 8-9). Le solde theorique
-- et le cout unitaire sont des instantanes figes au moment de la saisie de chaque ligne (pas
-- recalcules retroactivement), pour ne jamais modifier silencieusement un historique deja saisi.
-- proposition_id relie l'inventaire a la proposition d'ecriture generee par
-- AccountingOperationEngine lors de la validation - null tant que l'inventaire n'a pas ete valide.
CREATE TABLE inventaire (
    id             UUID PRIMARY KEY,
    entreprise_id  UUID NOT NULL REFERENCES entreprise(id),
    date           DATE NOT NULL,
    statut         VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    commentaire    VARCHAR(500),
    proposition_id UUID REFERENCES accounting_operation_proposal(id),
    created_by     UUID REFERENCES utilisateur(id),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE ligne_inventaire (
    id               UUID PRIMARY KEY,
    inventaire_id    UUID NOT NULL REFERENCES inventaire(id) ON DELETE CASCADE,
    article_id       UUID NOT NULL REFERENCES article(id),
    stock_theorique  NUMERIC(15,3) NOT NULL,
    stock_compte     NUMERIC(15,3) NOT NULL,
    ecart            NUMERIC(15,3) NOT NULL,
    cout_unitaire    NUMERIC(15,4) NOT NULL,
    valeur_ecart     NUMERIC(15,2) NOT NULL,
    UNIQUE(inventaire_id, article_id)
);

CREATE INDEX idx_inventaire_entreprise ON inventaire(entreprise_id, statut);
CREATE INDEX idx_ligne_inventaire_inventaire ON ligne_inventaire(inventaire_id);
CREATE INDEX idx_ligne_inventaire_article ON ligne_inventaire(article_id);
