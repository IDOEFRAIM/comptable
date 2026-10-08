-- Ecran Banque pagine cote serveur : tri/filtre par date sur toute l'entreprise (sans compte) et acces par releve pour le classement partiel.
CREATE INDEX IF NOT EXISTS idx_ligne_releve_entreprise_date ON ligne_releve(entreprise_id, date_operation DESC, id);
CREATE INDEX IF NOT EXISTS idx_ligne_releve_releve_montant ON ligne_releve(releve_id, montant) WHERE ligne_ecriture_id IS NULL;
