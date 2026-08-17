-- Alignement sur la nomenclature TVA utilisee par le moteur de comptabilisation :
-- 443 = Etat, TVA collectee (sur ventes) ; 4456 = Etat, TVA deductible (sur achats).
-- On renomme les lignes existantes plutot que d'en ajouter de nouvelles : meme id,
-- donc aucune reference existante (compte_comptable.reference_id) n'est cassee.

UPDATE plan_comptable_reference SET numero = '443', intitule = 'Etat, TVA collectee'
WHERE numero = '4431';

UPDATE plan_comptable_reference SET numero = '4456', intitule = 'Etat, TVA deductible'
WHERE numero = '4452';
