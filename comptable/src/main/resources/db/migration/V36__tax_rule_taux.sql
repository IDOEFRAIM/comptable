-- Ajoute le taux au referentiel fiscal (TaxRule), prealable au moteur TVA. Volontairement sans
-- seed : la table reste vide en production (voir V27, aucune source reglementaire verifiee
-- disponible) - seules des fixtures de test explicitement marquees comme demo peuplent cette
-- colonne, jamais une migration executee en production.
ALTER TABLE tax_rule ADD COLUMN taux NUMERIC(5,2);
