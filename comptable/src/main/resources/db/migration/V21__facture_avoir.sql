-- Avoirs commerciaux (Sprint 13) : extourne d'une facture de vente validee, totale ou partielle.
--
-- compte_produit_id trace le compte de produit (701/706...) choisi lors de la validation de la
-- facture d'origine (jusqu'ici transmis en parametre de requete mais jamais persiste) : sans lui,
-- impossible de savoir quel compte debiter pour extourner exactement la vente lors de l'avoir.
ALTER TABLE facture ADD COLUMN compte_produit_id UUID REFERENCES compte_comptable(id);

CREATE TABLE facture_avoir (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    facture_origine_id  UUID NOT NULL REFERENCES facture(id),
    tiers_id            UUID NOT NULL REFERENCES tiers(id),
    numero              VARCHAR(30) NOT NULL,
    date_emission       DATE NOT NULL,
    motif               VARCHAR(300),
    retour_stock        BOOLEAN NOT NULL DEFAULT false,
    statut              VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    ecriture_id         UUID REFERENCES ecriture_comptable(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entreprise_id, numero)
);

CREATE INDEX idx_facture_avoir_facture_origine ON facture_avoir(facture_origine_id);

CREATE TABLE ligne_facture_avoir (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    avoir_id                  UUID NOT NULL REFERENCES facture_avoir(id) ON DELETE CASCADE,
    ligne_facture_origine_id  UUID NOT NULL REFERENCES ligne_facture(id),
    designation               VARCHAR(300) NOT NULL,
    quantite                  NUMERIC(12,2) NOT NULL,
    prix_unitaire             NUMERIC(15,2) NOT NULL,
    taux_tva                  NUMERIC(5,2) NOT NULL DEFAULT 0,
    numero_ligne              INT NOT NULL
);

CREATE INDEX idx_ligne_facture_avoir_avoir ON ligne_facture_avoir(avoir_id);
