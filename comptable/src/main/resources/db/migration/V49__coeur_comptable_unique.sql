-- Phase A : un seul chemin comptable, modele propre avant le premier pilote.

-- 1. Tables mortes (V1) : aucune entite, aucun repository, aucune donnee seedee, aucune cle etrangere
--    entrante. Le modele de droits reel est Role / Utilisateur / utilisateur_entreprise.
DROP TABLE IF EXISTS role_permission;
DROP TABLE IF EXISTS permission;
DROP TABLE IF EXISTS collaborateur_entreprise;

-- 2. accounting_proposal -> imputation_suggestion : suggestion de tiers + compte pour un document.
--    Le journal n'en fait plus partie : il est resolu par le moteur comptable, jamais suggere.
ALTER TABLE accounting_proposal RENAME TO imputation_suggestion;
ALTER TABLE imputation_suggestion DROP COLUMN journal_id;
ALTER INDEX idx_accounting_proposal_entreprise RENAME TO idx_imputation_suggestion_entreprise;
ALTER INDEX idx_accounting_proposal_document RENAME TO idx_imputation_suggestion_document;
ALTER INDEX idx_accounting_proposal_status RENAME TO idx_imputation_suggestion_status;

-- 3. Cycle de vie des immobilisations : ACQUISE -> EN_SERVICE -> SORTIE.
ALTER TABLE immobilisation ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'CORPORELLE';
ALTER TABLE immobilisation ADD COLUMN date_mise_en_service DATE;
ALTER TABLE immobilisation ADD COLUMN acquisition_ecriture_id UUID REFERENCES ecriture_comptable(id);
ALTER TABLE immobilisation ADD COLUMN date_sortie DATE;
ALTER TABLE immobilisation ADD COLUMN motif_sortie VARCHAR(20);
ALTER TABLE immobilisation ADD COLUMN montant_cession NUMERIC(15,2);
ALTER TABLE immobilisation ALTER COLUMN statut SET DEFAULT 'ACQUISE';
-- Donnees de developpement existantes : un bien deja EN_SERVICE l'etait des son acquisition.
UPDATE immobilisation SET date_mise_en_service = date_acquisition WHERE statut = 'EN_SERVICE';
UPDATE immobilisation SET statut = 'SORTIE' WHERE statut = 'CEDEE';

-- 4. La dotation trace l'ecriture du moteur (une par immobilisation et par annee), plus une ligne d'ecriture.
ALTER TABLE dotation_amortissement DROP COLUMN ligne_ecriture_id;
ALTER TABLE dotation_amortissement ADD COLUMN ecriture_id UUID REFERENCES ecriture_comptable(id);
