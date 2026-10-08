-- Phase C : le document n'est plus un fichier stocke mais une piece identifiee, dedupliquee, comprise et rapprochee.

-- Identite forte : SHA-256 du contenu, calcule cote backend. Non unique : un doublon est un CONSTAT a traiter,
-- pas un refus d'ecriture (la piece reste tracable). Deux entreprises peuvent posseder le meme fichier : l'index
-- est donc par entreprise.
ALTER TABLE document ADD COLUMN content_hash VARCHAR(64);
CREATE INDEX idx_document_hash ON document(entreprise_id, content_hash) WHERE content_hash IS NOT NULL;

-- Numero de facture du FOURNISSEUR (pas le numero interne), valeur d'origine + valeur normalisee pour comparer.
ALTER TABLE document ADD COLUMN numero_facture VARCHAR(100);
ALTER TABLE document ADD COLUMN numero_facture_normalise VARCHAR(100);
ALTER TABLE document ADD COLUMN devise VARCHAR(3);

-- Tiers identifie (fournisseur pour un achat, client pour une vente) et qualite de cette identification.
ALTER TABLE document ADD COLUMN tiers_id UUID REFERENCES tiers(id);
ALTER TABLE document ADD COLUMN statut_classification VARCHAR(20);
ALTER TABLE document ADD COLUMN confiance_tiers DOUBLE PRECISION;
CREATE INDEX idx_document_facture_tiers ON document(entreprise_id, tiers_id, numero_facture_normalise)
    WHERE numero_facture_normalise IS NOT NULL;

-- Rapprochement vers la facture / l'operation a laquelle la piece appartient.
ALTER TABLE document ADD COLUMN cible_type VARCHAR(30);
ALTER TABLE document ADD COLUMN cible_id UUID;
ALTER TABLE document ADD COLUMN statut_rapprochement VARCHAR(20) NOT NULL DEFAULT 'NON_RAPPROCHE';
ALTER TABLE document ADD COLUMN confiance_rapprochement DOUBLE PRECISION;
ALTER TABLE document ADD COLUMN rapproche_at TIMESTAMPTZ;
ALTER TABLE document ADD COLUMN rapproche_par VARCHAR(10);
ALTER TABLE document ADD COLUMN cible_rejetee_id UUID;
CREATE INDEX idx_document_cible ON document(entreprise_id, cible_type, cible_id) WHERE cible_id IS NOT NULL;
CREATE INDEX idx_document_hub ON document(entreprise_id, statut, created_at DESC);

-- Doublon CONFIRME par un humain (la piece reste en base, hors du travail a faire) et validation humaine.
ALTER TABLE document ADD COLUMN doublon_de_id UUID REFERENCES document(id);
ALTER TABLE document ADD COLUMN valide_at TIMESTAMPTZ;
ALTER TABLE document ADD COLUMN valide_par_id UUID REFERENCES utilisateur(id);

-- Facture fournisseur : le numero existant est INTERNE ; le numero du fournisseur est distinct et facultatif.
ALTER TABLE facture_fournisseur ADD COLUMN numero_fournisseur VARCHAR(100);
ALTER TABLE facture_fournisseur ADD COLUMN numero_fournisseur_normalise VARCHAR(100);
CREATE INDEX idx_ff_numero_fournisseur ON facture_fournisseur(entreprise_id, fournisseur_id, numero_fournisseur_normalise)
    WHERE numero_fournisseur_normalise IS NOT NULL;

-- Facture client : numero normalise pour retrouver la facture d'une piece de vente.
ALTER TABLE facture ADD COLUMN numero_normalise VARCHAR(100);
UPDATE facture SET numero_normalise = regexp_replace(upper(numero), '[^A-Z0-9]', '', 'g') WHERE numero IS NOT NULL;
CREATE INDEX idx_facture_numero_normalise ON facture(entreprise_id, numero_normalise) WHERE numero_normalise IS NOT NULL;

-- Demande de piece : rattachee a l'operation qui manque de justificatif, avec canal et reception.
ALTER TABLE demande_piece ADD COLUMN ecriture_id UUID REFERENCES ecriture_comptable(id);
ALTER TABLE demande_piece ADD COLUMN motif VARCHAR(30);
ALTER TABLE demande_piece ADD COLUMN canal VARCHAR(20);
CREATE INDEX idx_demande_piece_ecriture ON demande_piece(ecriture_id) WHERE ecriture_id IS NOT NULL;

-- Les documents dont le type d'objet ou l'etat est consulte par le Hub sont filtres par statut/date : index ci-dessus.
