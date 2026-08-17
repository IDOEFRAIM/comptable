-- Cablage automatique Facturation <-> Stock/Achats (Sprint 12).
--
-- Une ligne de facture peut desormais referencer un Article de stock (article_id) ; a la
-- validation de la facture, MouvementStockService genere la sortie correspondante et son
-- identifiant est trace sur la ligne (mouvement_stock_id) pour permettre une reintegration
-- exacte (meme quantite, meme prix) si la facture est annulee.
ALTER TABLE ligne_facture ADD COLUMN article_id UUID REFERENCES article(id);
ALTER TABLE ligne_facture ADD COLUMN mouvement_stock_id UUID REFERENCES mouvement_stock(id);

-- Facture fournisseur generee depuis un bon de commande receptionne (chainage complet
-- Commande -> Reception -> Facture). Une seule facture par commande (UNIQUE(commande_id)).
CREATE TABLE facture_fournisseur (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id  UUID NOT NULL REFERENCES entreprise(id),
    commande_id    UUID NOT NULL REFERENCES commande_achat(id),
    fournisseur_id UUID NOT NULL REFERENCES tiers(id),
    numero         VARCHAR(30) NOT NULL,
    date_facture   DATE NOT NULL,
    montant_ht     NUMERIC(15,2) NOT NULL,
    montant_tva    NUMERIC(15,2) NOT NULL,
    montant_ttc    NUMERIC(15,2) NOT NULL,
    ecriture_id    UUID REFERENCES ecriture_comptable(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (commande_id),
    UNIQUE (entreprise_id, numero)
);
