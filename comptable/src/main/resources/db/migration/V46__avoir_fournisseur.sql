-- Avoir fournisseur documentaire (mission Etape 5, section 16) : reference une FactureFournisseur
-- d'origine et en inverse le total (marchandises + services + frais + TVA), meme proportion que
-- FactureFournisseur elle-meme (montants agreges, pas de lignes propres - un avoir total, pas de
-- selection partielle des lignes de la commande dans cette mission).
CREATE TABLE avoir_fournisseur (
    id                  UUID PRIMARY KEY,
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    facture_id          UUID NOT NULL REFERENCES facture_fournisseur(id),
    fournisseur_id      UUID NOT NULL REFERENCES tiers(id),
    numero              VARCHAR(50) NOT NULL,
    date_emission       DATE NOT NULL,
    montant_ht          NUMERIC(15,2) NOT NULL,
    montant_tva         NUMERIC(15,2) NOT NULL,
    montant_ttc         NUMERIC(15,2) NOT NULL,
    motif               VARCHAR(500),
    retour_stock        BOOLEAN NOT NULL DEFAULT false,
    ecriture_id         UUID,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_avoir_fournisseur_entreprise ON avoir_fournisseur(entreprise_id);
-- Une seule facture fournisseur ne peut avoir qu'un avoir total dans cette mission (pas de
-- selection partielle des lignes) - contrainte d'unicite en defense, en plus du controle
-- applicatif dans AvoirFournisseurService.
CREATE UNIQUE INDEX idx_avoir_fournisseur_facture ON avoir_fournisseur(facture_id);
