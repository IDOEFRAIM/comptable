-- Rattache a leur compte collectif les sous-comptes locaux deja crees avant l'introduction de la
-- hierarchie (fixtures V8 411001/401001, et tout compte auxiliaire deja provisionne par
-- TiersService.provisionnerSousCompte pour un tiers existant). Rien d'invente : seule une
-- relation reellement deductible (meme entreprise, meme racine 401/411, meme prefixe a 3
-- chiffres) est renseignee. Tout le reste garde niveau=1, parent_id NULL - identique a l'etat
-- actuel.

UPDATE compte_comptable child
SET parent_id = parent.id,
    niveau = parent.niveau + 1
FROM compte_comptable parent
WHERE child.reference_id IS NULL
  AND child.parent_id IS NULL
  AND parent.entreprise_id = child.entreprise_id
  AND parent.numero IN ('401', '411')
  AND left(child.numero, 3) = parent.numero
  AND child.numero <> parent.numero;
