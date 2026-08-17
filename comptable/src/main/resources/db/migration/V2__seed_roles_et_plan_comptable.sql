-- =========================================================================
-- ROLES
-- =========================================================================

INSERT INTO role (code, libelle) VALUES
    ('SUPER_ADMIN',      'Super administrateur SaaS'),
    ('EXPERT_COMPTABLE', 'Expert-comptable / titulaire du cabinet'),
    ('COLLABORATEUR',    'Collaborateur du cabinet'),
    ('CLIENT',           'Client / entreprise cliente');

-- =========================================================================
-- PLAN COMPTABLE SYSCOHADA REVISE - extrait de demarrage
-- Liste non exhaustive couvrant les comptes les plus courants d'une PME.
-- A completer avec le plan complet par classe lors de l'industrialisation.
-- =========================================================================

INSERT INTO plan_comptable_reference (numero, intitule, classe, nature) VALUES
    -- Classe 1 : comptes de ressources durables
    ('101', 'Capital social',                              1, 'PASSIF'),
    ('106', 'Reserves',                                    1, 'PASSIF'),
    ('120', 'Resultat net de l''exercice (benefice)',      1, 'PASSIF'),
    ('129', 'Resultat net de l''exercice (perte)',         1, 'ACTIF'),
    ('161', 'Emprunts aupres des etablissements de credit', 1, 'PASSIF'),

    -- Classe 2 : actif immobilise
    ('211', 'Terrains',                                    2, 'ACTIF'),
    ('231', 'Batiments',                                   2, 'ACTIF'),
    ('244', 'Materiel et mobilier',                         2, 'ACTIF'),
    ('245', 'Materiel de transport',                        2, 'ACTIF'),
    ('281', 'Amortissements des immobilisations corporelles', 2, 'ACTIF'),

    -- Classe 3 : stocks
    ('311', 'Marchandises',                                 3, 'ACTIF'),
    ('355', 'Produits finis',                                3, 'ACTIF'),

    -- Classe 4 : tiers
    ('401', 'Fournisseurs',                                  4, 'PASSIF'),
    ('411', 'Clients',                                        4, 'ACTIF'),
    ('421', 'Personnel, avances et acomptes',                  4, 'ACTIF'),
    ('422', 'Personnel, remunerations dues',                    4, 'PASSIF'),
    ('431', 'Securite sociale',                                  4, 'PASSIF'),
    ('441', 'Etat, impots sur les benefices',                     4, 'PASSIF'),
    ('4431', 'Etat, TVA facturee',                                 4, 'PASSIF'),
    ('4452', 'Etat, TVA recuperable sur achats',                    4, 'ACTIF'),

    -- Classe 5 : tresorerie
    ('521', 'Banques',                                               5, 'ACTIF'),
    ('571', 'Caisse',                                                 5, 'ACTIF'),

    -- Classe 6 : charges
    ('601', 'Achats de marchandises',                                  6, 'CHARGE'),
    ('604', 'Achats stockes de matieres et fournitures',                6, 'CHARGE'),
    ('611', 'Transports sur achats et ventes',                           6, 'CHARGE'),
    ('621', 'Sous-traitance generale',                                    6, 'CHARGE'),
    ('641', 'Charges de personnel',                                        6, 'CHARGE'),
    ('661', 'Charges d''interets',                                          6, 'CHARGE'),
    ('681', 'Dotations aux amortissements',                                  6, 'CHARGE'),

    -- Classe 7 : produits
    ('701', 'Ventes de marchandises',                                        7, 'PRODUIT'),
    ('706', 'Services vendus',                                                 7, 'PRODUIT'),
    ('758', 'Produits divers',                                                  7, 'PRODUIT'),
    ('781', 'Reprises d''amortissements',                                        7, 'PRODUIT');
