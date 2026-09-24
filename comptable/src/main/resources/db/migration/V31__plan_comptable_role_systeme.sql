-- Registre central des comptes "systeme" (TVA, dotations, resultat, racines tiers) aujourd'hui
-- retrouves par plusieurs services independamment via un numero code en dur
-- (ComptabilisationService, FacturationService, FactureAvoirService, CommandeAchatService,
-- DeclarationFiscaleService, ImmobilisationService, ClotureExerciceService, TiersService).
-- Cette migration ajoute la colonne et l'associe aux 8 comptes concernes ; la migration des
-- sites d'appel existants vers CompteSystemeResolver reste un suivi separe, a faible risque,
-- hors perimetre de cette fondation.

ALTER TABLE plan_comptable_reference ADD COLUMN role_systeme VARCHAR(30);

CREATE UNIQUE INDEX idx_pcr_role_systeme ON plan_comptable_reference(role_systeme)
    WHERE role_systeme IS NOT NULL;

UPDATE plan_comptable_reference SET role_systeme = 'TVA_COLLECTEE' WHERE numero = '443';
UPDATE plan_comptable_reference SET role_systeme = 'TVA_DEDUCTIBLE' WHERE numero = '4456';
UPDATE plan_comptable_reference SET role_systeme = 'DOTATIONS_AMORTISSEMENTS' WHERE numero = '681';
UPDATE plan_comptable_reference SET role_systeme = 'RESULTAT_BENEFICE' WHERE numero = '120';
UPDATE plan_comptable_reference SET role_systeme = 'RESULTAT_PERTE' WHERE numero = '129';
UPDATE plan_comptable_reference SET role_systeme = 'RACINE_FOURNISSEUR' WHERE numero = '401';
UPDATE plan_comptable_reference SET role_systeme = 'RACINE_CLIENT' WHERE numero = '411';
