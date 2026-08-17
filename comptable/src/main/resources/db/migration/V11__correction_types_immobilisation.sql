-- V10 declarait duree_annees et annee en SMALLINT alors que les entites JPA utilisent
-- Integer (mappe par defaut sur INTEGER) : Hibernate refuse de valider ce mismatch au demarrage.

ALTER TABLE immobilisation ALTER COLUMN duree_annees TYPE INTEGER;
ALTER TABLE dotation_amortissement ALTER COLUMN annee TYPE INTEGER;
