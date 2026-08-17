-- =========================================================================
-- DONNEES DE TEST - A utiliser en developpement uniquement
-- =========================================================================
-- ATTENTION: Les mots de passe hashés ci-dessous doivent être générés avec BCryptPasswordEncoder
-- Les passwords utilisent BCrypt avec force=10
-- Pour générer un hash: https://www.browserling.com/tools/bcrypt ou via Java:
-- new BCryptPasswordEncoder().encode("password")

-- =========================================================================
-- CABINETS (TENANTS) DE TEST
-- =========================================================================

INSERT INTO tenant (id, raison_sociale, slug, pays, plan_abonnement, statut) VALUES
  -- Cabinet 1: Cabinet Conseil & Associés
  ('11111111-1111-1111-1111-111111111111'::uuid, 'Cabinet Conseil & Associés', 'cabinet-conseil-associes', 'CI', 'PREMIUM', 'ACTIF'),
  -- Cabinet 2: Expert Comptable Solutions
  ('22222222-2222-2222-2222-222222222222'::uuid, 'Expert Comptable Solutions', 'expert-comptable-solutions', 'CI', 'ESSAI', 'ACTIF');

-- =========================================================================
-- UTILISATEURS DE TEST
-- =========================================================================
-- Note: Les mots de passe sont hashés avec BCrypt (force=10)
--
-- Utilisateur 1: EXPERT_COMPTABLE du Cabinet 1
-- Email: cabinet@example.com
-- Mot de passe: Cabinet123!@#
-- Hash BCrypt: $2a$10$K2fBg3VVZiNXVmU2KVmhSu9Mv3z8z8z8z8z8z8z8z8z8z8z8z8z8z (exemple)

-- Utilisateur 2: COLLABORATEUR du Cabinet 1
-- Email: collaborateur@example.com
-- Mot de passe: Collab123!@#

-- Utilisateur 3: EXPERT_COMPTABLE du Cabinet 2
-- Email: expert@solutions.ci
-- Mot de passe: Expert123!@#

-- =========================================================================
-- ENTREPRISES CLIENTES DE TEST
-- =========================================================================

INSERT INTO entreprise (id, tenant_id, raison_sociale, numero_contribuable, rccm, forme_juridique, regime_fiscal, secteur_activite, pays, devise, statut) VALUES
  -- Entreprise 1: ACME Corporation (client du Cabinet 1)
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
   'ACME Corporation', 'CI-123456789', 'CI/CC/2024/12345', 'SARL', 'Régime Normal', 'Commerce & Services', 'CI', 'XOF', 'ACTIF'),

  -- Entreprise 2: TechStart SARL (client du Cabinet 1)
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
   'TechStart SARL', 'CI-987654321', 'CI/CC/2024/54321', 'SARL', 'Régime Simplifié', 'Informatique & Télécommunications', 'CI', 'XOF', 'ACTIF'),

  -- Entreprise 3: Pharma Plus (client du Cabinet 2)
  ('cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, '22222222-2222-2222-2222-222222222222'::uuid,
   'Pharma Plus', 'CI-555666777', 'CI/CC/2024/66666', 'SA', 'Régime Normal', 'Pharmacie & Santé', 'CI', 'XOF', 'ACTIF');

-- =========================================================================
-- EXERCICES COMPTABLES
-- =========================================================================

INSERT INTO exercice_comptable (id, entreprise_id, date_debut, date_fin, statut) VALUES
  -- Exercice 2024 pour ACME Corporation
  ('dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, '2024-01-01', '2024-12-31', 'OUVERT'),

  -- Exercice 2023 pour ACME Corporation
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, '2023-01-01', '2023-12-31', 'FERMÉ'),

  -- Exercice 2024 pour TechStart SARL
  ('ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, '2024-01-01', '2024-12-31', 'OUVERT'),

  -- Exercice 2024 pour Pharma Plus
  ('00000000-0000-0000-0000-000000000000'::uuid, 'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, '2024-01-01', '2024-12-31', 'OUVERT');

-- =========================================================================
-- JOURNAUX COMPTABLES
-- =========================================================================

-- Journaux pour ACME Corporation
INSERT INTO journal (id, entreprise_id, code, libelle, type) VALUES
  ('11111111-1111-1111-1111-111111111111'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'VT', 'Journal de Ventes', 'VENTES'),
  ('12121212-1212-1212-1212-121212121212'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'AC', 'Journal d''Achats', 'ACHATS'),
  ('13131313-1313-1313-1313-131313131313'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'BQ', 'Journal de Banque', 'BANQUE'),
  ('14141414-1414-1414-1414-141414141414'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'CS', 'Journal de Caisse', 'CAISSE'),
  ('15151515-1515-1515-1515-151515151515'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'OD', 'Journal d''Opérations Diverses', 'DIVERS');

-- Journaux pour TechStart SARL
INSERT INTO journal (id, entreprise_id, code, libelle, type) VALUES
  ('21212121-2121-2121-2121-212121212121'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'VT', 'Journal de Ventes', 'VENTES'),
  ('22222222-2222-2222-2222-222222222222'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'AC', 'Journal d''Achats', 'ACHATS'),
  ('23232323-2323-2323-2323-232323232323'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'BQ', 'Journal de Banque', 'BANQUE'),
  ('24242424-2424-2424-2424-242424242424'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'CS', 'Journal de Caisse', 'CAISSE'),
  ('25252525-2525-2525-2525-252525252525'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'OD', 'Journal d''Opérations Diverses', 'DIVERS');

-- Journaux pour Pharma Plus
INSERT INTO journal (id, entreprise_id, code, libelle, type) VALUES
  ('31313131-3131-3131-3131-313131313131'::uuid, 'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, 'VT', 'Journal de Ventes', 'VENTES'),
  ('32323232-3232-3232-3232-323232323232'::uuid, 'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, 'AC', 'Journal d''Achats', 'ACHATS'),
  ('33333333-3333-3333-3333-333333333333'::uuid, 'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, 'BQ', 'Journal de Banque', 'BANQUE'),
  ('34343434-3434-3434-3434-343434343434'::uuid, 'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, 'CS', 'Journal de Caisse', 'CAISSE'),
  ('35353535-3535-3535-3535-353535353535'::uuid, 'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid, 'OD', 'Journal d''Opérations Diverses', 'DIVERS');

-- =========================================================================
-- COMPTES COMPTABLES : clone complet du plan de reference pour chaque
-- entreprise (identique a ce que EntrepriseService.creer() ferait via l'API,
-- indispensable pour que la comptabilisation et la TVA fonctionnent).
-- =========================================================================

INSERT INTO compte_comptable (id, entreprise_id, reference_id, numero, intitule, lettrable)
SELECT gen_random_uuid(), e.id, r.id, r.numero, r.intitule, r.numero LIKE '401%' OR r.numero LIKE '411%'
FROM entreprise e
CROSS JOIN plan_comptable_reference r
WHERE e.id IN (
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid,
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
    'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid
);

-- Sous-comptes auxiliaires dedies pour chaque tiers (meme principe que
-- TiersService.provisionnerSousCompte : un compte 411xxx/401xxx par tiers).
INSERT INTO compte_comptable (id, entreprise_id, reference_id, numero, intitule, lettrable) VALUES
  ('61616161-6161-6161-6161-616161616161'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, NULL, '411001', 'Client - Client Fidèle SARL', true),
  ('62626262-6262-6262-6262-626262626262'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, NULL, '401001', 'Fournisseur - Fournisseur Premium Ltd', true),
  ('63636363-6363-6363-6363-636363636363'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, NULL, '411001', 'Client - Entreprise Tech Pro', true),
  ('64646464-6464-6464-6464-646464646464'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, NULL, '401001', 'Fournisseur - Supplier International', true);

-- =========================================================================
-- TIERS (Clients, Fournisseurs, etc.)
-- =========================================================================

INSERT INTO tiers (id, entreprise_id, type, nom, numero_contribuable, compte_comptable_id) VALUES
  -- Tiers pour ACME Corporation
  ('41414141-4141-4141-4141-414141414141'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'CLIENT', 'Client Fidèle SARL', 'SARL-001', '61616161-6161-6161-6161-616161616161'::uuid),
  ('42424242-4242-4242-4242-424242424242'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'FOURNISSEUR', 'Fournisseur Premium Ltd', 'FRN-001', '62626262-6262-6262-6262-626262626262'::uuid),

  -- Tiers pour TechStart SARL
  ('51515151-5151-5151-5151-515151515151'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'CLIENT', 'Entreprise Tech Pro', 'TECH-001', '63636363-6363-6363-6363-636363636363'::uuid),
  ('52525252-5252-5252-5252-525252525252'::uuid, 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, 'FOURNISSEUR', 'Supplier International', 'INT-001', '64646464-6464-6464-6464-646464646464'::uuid);

-- =========================================================================
-- FIN DES DONNEES DE TEST
-- =========================================================================
--
-- NOTES IMPORTANTES:
--
-- 1. Les utilisateurs ne sont PAS créés ici car les mots de passe doivent être
--    hashés avec BCryptPasswordEncoder (Spring Security).
--
-- 2. Pour créer les utilisateurs de test, consultez le README avec les instructions
--    sur l'enregistrement via l'API.
--
-- 3. Cette migration crée uniquement:
--    - Les cabinets (tenants)
--    - Les entreprises clientes
--    - Les exercices comptables
--    - Les journaux
--    - Les tiers de base
--    - Les comptes comptables
--
-- 4. Les utilisateurs doivent être créés via l'endpoint:
--    POST /api/auth/register-cabinet
--    (voir DOCUMENTATION.md pour les détails)
