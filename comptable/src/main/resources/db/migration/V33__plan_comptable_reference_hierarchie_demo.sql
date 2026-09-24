-- =========================================================================
-- ATTENTION : referentiel de DEMONSTRATION, pas la nomenclature SYSCOHADA
-- officielle complete et verifiee. Objectif de cette migration : donner au
-- referentiel une vraie arborescence a profondeur variable (classe -> compte
-- parent -> sous-compte -> compte de detail), suffisante pour tester chaque
-- classe deja utilisee par l'application (1-7) plus un minimum de classe 8.
-- Le contenu reglementaire complet devra etre verifie a partir des sources
-- OHADA officielles avant toute utilisation en production - voir
-- comptable/DOCUMENTATION.md.
--
-- Contrairement au precedent V18 (retrofit automatique sur les entreprises
-- existantes), cette migration n'ajoute ces nouveaux comptes parents qu'au
-- referentiel global : rien de l'existant n'en depend, donc ils restent
-- disponibles a l'import (POST .../plan-comptable/importer-reference)
-- plutot que d'etre pousses de force dans chaque PCE deja provisionne.
-- =========================================================================

-- Comptes parents a 2 chiffres, un par regroupement present dans le seed V2/V18.
-- nature : classification dominante du groupe (certains groupes reels melangent
-- plusieurs natures, ex. "44 Etat" contient a la fois des comptes ACTIF et
-- PASSIF - nature retenue ici = la plus frequente du groupe, A VERIFIER avant
-- un usage reglementaire strict).
INSERT INTO plan_comptable_reference (numero, intitule, classe, nature, niveau, sens_normal, actif) VALUES
    ('10', 'Capital',                                  1, 'PASSIF', 1, 'CREDIT', true),
    ('12', 'Resultat net de l''exercice',               1, 'PASSIF', 1, 'CREDIT', true),
    ('16', 'Emprunts et dettes assimilees',             1, 'PASSIF', 1, 'CREDIT', true),
    ('21', 'Immobilisations corporelles - Terrains',    2, 'ACTIF',  1, 'DEBIT',  true),
    ('23', 'Batiments, installations',                  2, 'ACTIF',  1, 'DEBIT',  true),
    ('24', 'Materiel, mobilier',                        2, 'ACTIF',  1, 'DEBIT',  true),
    ('28', 'Amortissements des immobilisations',        2, 'ACTIF',  1, 'CREDIT', true),
    ('31', 'Marchandises',                              3, 'ACTIF',  1, 'DEBIT',  true),
    ('35', 'Stocks de produits',                        3, 'ACTIF',  1, 'DEBIT',  true),
    ('40', 'Fournisseurs et comptes rattaches',         4, 'PASSIF', 1, 'CREDIT', true),
    ('41', 'Clients et comptes rattaches',              4, 'ACTIF',  1, 'DEBIT',  true),
    ('42', 'Personnel et comptes rattaches',            4, 'PASSIF', 1, 'CREDIT', true),
    ('43', 'Organismes sociaux',                        4, 'PASSIF', 1, 'CREDIT', true),
    ('44', 'Etat et collectivites publiques',           4, 'PASSIF', 1, 'CREDIT', true),
    ('52', 'Banques',                                   5, 'ACTIF',  1, 'DEBIT',  true),
    ('57', 'Caisse',                                    5, 'ACTIF',  1, 'DEBIT',  true),
    ('60', 'Achats',                                    6, 'CHARGE', 1, 'DEBIT',  true),
    ('61', 'Transports',                                6, 'CHARGE', 1, 'DEBIT',  true),
    ('62', 'Services exterieurs A',                     6, 'CHARGE', 1, 'DEBIT',  true),
    ('64', 'Charges de personnel',                      6, 'CHARGE', 1, 'DEBIT',  true),
    ('66', 'Charges financieres',                       6, 'CHARGE', 1, 'DEBIT',  true),
    ('68', 'Dotations aux amortissements et provisions', 6, 'CHARGE', 1, 'DEBIT', true),
    ('70', 'Ventes',                                    7, 'PRODUIT', 1, 'CREDIT', true),
    ('75', 'Autres produits',                           7, 'PRODUIT', 1, 'CREDIT', true),
    ('78', 'Reprises de provisions et amortissements',  7, 'PRODUIT', 1, 'CREDIT', true);

-- Rattache les feuilles deja existantes (seed V2 renomme par V4 + ajouts V18) a leur nouveau
-- compte parent, sur le prefixe a 2 chiffres. Generique : couvre toutes les feuilles d'un coup,
-- rien n'est invente ici, seule la relation deductible du numero est renseignee.
UPDATE plan_comptable_reference child
SET parent_id = parent.id, niveau = 2
FROM plan_comptable_reference parent
WHERE parent.niveau = 1
  AND length(parent.numero) = 2
  AND length(child.numero) > 2
  AND left(child.numero, 2) = parent.numero;

-- Exemple de profondeur variable demandee par la mission (classe -> 41 -> 411 -> 4111/4112) :
-- deux comptes de detail sous 411, pour demontrer qu'un compte non-feuille (411 a desormais des
-- enfants) peut malgre tout rester marque compte_detail=true - une entreprise choisit, a
-- l'import, le niveau de granularite qu'elle utilise reellement (411 seul, ou 4111/4112).
INSERT INTO plan_comptable_reference
    (numero, intitule, classe, nature, niveau, sens_normal, parent_id, compte_detail, lettrable)
SELECT '4111', 'Clients locaux', 4, 'ACTIF', 3, 'DEBIT', id, true, true FROM plan_comptable_reference WHERE numero = '411'
UNION ALL
SELECT '4112', 'Clients etrangers', 4, 'ACTIF', 3, 'DEBIT', id, true, true FROM plan_comptable_reference WHERE numero = '411';

-- Minimum classe 8 (HAO - Hors Activites Ordinaires) : aucun precedent dans l'application
-- aujourd'hui, ajout volontairement minimal et explicitement signale comme le point le moins
-- verifie de ce seed de demonstration.
INSERT INTO plan_comptable_reference (numero, intitule, classe, nature, niveau, sens_normal, actif) VALUES
    ('81', 'Valeurs comptables des cessions d''immobilisations (HAO)', 8, 'CHARGE', 1, 'DEBIT', true),
    ('82', 'Produits des cessions d''immobilisations (HAO)',           8, 'PRODUIT', 1, 'CREDIT', true);
