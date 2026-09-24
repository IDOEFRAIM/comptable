-- Etend le registre role_systeme (V31) aux comptes de tresorerie, pour que ReglementRule resolve
-- le compte banque/caisse via CompteSystemeResolver au lieu d'un numero "521"/"571" code en dur.
-- Continuation directe du pattern V31, pas une nouvelle architecture.
UPDATE plan_comptable_reference SET role_systeme = 'TRESORERIE_BANQUE' WHERE numero = '521';
UPDATE plan_comptable_reference SET role_systeme = 'TRESORERIE_CAISSE' WHERE numero = '571';
