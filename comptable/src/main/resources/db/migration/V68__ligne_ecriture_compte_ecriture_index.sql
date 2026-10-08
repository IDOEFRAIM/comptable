-- Mouvement d'un compte sur une période (rapprochement TVA) : jointure ligne_ecriture -> ecriture_comptable filtrée par entreprise et dates.
-- Avec un plan générique de requête préparée (JDBC bascule au 6e appel), PostgreSQL combinait idx_ligne_ecriture_compte et idx_ligne_ecriture_ecriture par BitmapAnd pour CHAQUE écriture de la période
-- (reconstruction du bitmap du compte 50 000 fois : ~29 s pour 50 000 écritures). Un index composite rend la sonde (compte, écriture) unitaire et le plan stable, générique ou non.
CREATE INDEX IF NOT EXISTS idx_ligne_ecriture_compte_ecriture ON ligne_ecriture (compte_id, ecriture_id);
