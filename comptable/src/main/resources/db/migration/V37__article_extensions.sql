-- Etend Article (mission "moteur de gestion des stocks", Etape 3) : type de nature, activation,
-- seuil d'alerte, politique de stock negatif configurable, mode de gestion (permanent/intermittent).
-- Valeurs par defaut choisies pour preserver exactement le comportement actuel de tous les
-- articles deja crees : MARCHANDISE (seule nature geree jusqu'ici), actif=true (aucun article
-- n'etait desactivable avant), INTERDIT (StockInsuffisantException etait deja systematique),
-- PERMANENT (seul mode existant). Ce ne sont pas des suppositions metier, seulement des
-- defauts de compatibilite.
ALTER TABLE article
    ADD COLUMN type VARCHAR(30) NOT NULL DEFAULT 'MARCHANDISE',
    ADD COLUMN actif BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN stock_minimum NUMERIC(15,3),
    ADD COLUMN politique_stock_negatif VARCHAR(30) NOT NULL DEFAULT 'INTERDIT',
    ADD COLUMN mode_gestion_stock VARCHAR(20) NOT NULL DEFAULT 'PERMANENT',
    ADD COLUMN updated_at TIMESTAMPTZ;
