-- Chaine d'achats (Sprint 11) : Bon de Commande Fournisseur -> Reception. La reception declenche
-- l'entree en stock (recalcul PUMP + ecriture 311/6031) via le meme mecanisme que
-- mouvement_stock, sans dupliquer la logique metier.

CREATE TABLE commande_achat (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id  UUID NOT NULL REFERENCES entreprise(id),
    fournisseur_id UUID NOT NULL REFERENCES tiers(id),
    numero         VARCHAR(30) NOT NULL,
    date_commande  DATE NOT NULL,
    statut         VARCHAR(20) NOT NULL DEFAULT 'EN_COURS',
    date_reception DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entreprise_id, numero)
);

CREATE TABLE ligne_commande_achat (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commande_id       UUID NOT NULL REFERENCES commande_achat(id) ON DELETE CASCADE,
    article_id        UUID NOT NULL REFERENCES article(id),
    quantite          NUMERIC(15,3) NOT NULL,
    prix_unitaire_ht  NUMERIC(15,4) NOT NULL,
    numero_ligne      INT NOT NULL
);

CREATE INDEX idx_ligne_commande_achat_commande ON ligne_commande_achat(commande_id);
