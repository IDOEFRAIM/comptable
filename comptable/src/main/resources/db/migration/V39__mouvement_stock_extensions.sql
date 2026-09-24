-- Etend MouvementStock (mission stock, Etape 3) : tracabilite generique (section 17 -
-- "pourquoi ce mouvement existe ?"), et rattachement optionnel a un depot source/destination pour
-- les transferts (section 15). Elargit la colonne type pour accueillir les nouvelles valeurs
-- d'enum plus longues (ex. "SORTIE_CONSOMMATION").
ALTER TABLE mouvement_stock
    ALTER COLUMN type TYPE VARCHAR(30),
    ADD COLUMN source_type VARCHAR(30),
    ADD COLUMN source_id UUID,
    ADD COLUMN depot_source_id UUID REFERENCES depot(id),
    ADD COLUMN depot_destination_id UUID REFERENCES depot(id);

CREATE INDEX idx_mouvement_stock_source ON mouvement_stock(source_type, source_id);
