-- Seul chainon manquant pour la boucle de resolution WhatsApp (mission P0, section 13) : une
-- DemandePiece creee automatiquement a partir d'un Finding de type manque de preuve doit pouvoir
-- etre retracee jusqu'a ce Finding (aujourd'hui demande_piece -> document est le seul lien, et
-- uniquement une fois la piece recue). Nullable : les demandes creees manuellement (hors P0)
-- n'ont pas de Finding d'origine.
ALTER TABLE demande_piece ADD COLUMN finding_id UUID REFERENCES finding(id);

CREATE INDEX idx_demande_piece_finding ON demande_piece(finding_id);
