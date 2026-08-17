ALTER TABLE document ADD COLUMN nom_fichier_original VARCHAR(255);
ALTER TABLE document ADD COLUMN content_type VARCHAR(100);
ALTER TABLE document ADD COLUMN taille_octets BIGINT;
