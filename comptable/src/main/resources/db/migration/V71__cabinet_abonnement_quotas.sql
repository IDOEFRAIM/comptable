-- Mode administrateur de la plateforme : réguler les cabinets (abonnement, essai, quotas, suspension).
-- Le plan (plan_abonnement) et le statut existent déjà ; on ajoute la fin d'essai, les quotas et la trace d'une suspension.
-- NULL = pas de limite / pas de date : les cabinets existants ne sont donc ni limités ni signalés en fin d'essai.
ALTER TABLE tenant ADD COLUMN fin_essai          DATE;
ALTER TABLE tenant ADD COLUMN quota_utilisateurs INTEGER CHECK (quota_utilisateurs IS NULL OR quota_utilisateurs > 0);
ALTER TABLE tenant ADD COLUMN quota_dossiers     INTEGER CHECK (quota_dossiers IS NULL OR quota_dossiers > 0);
ALTER TABLE tenant ADD COLUMN suspendu_motif     VARCHAR(500);
ALTER TABLE tenant ADD COLUMN suspendu_le        TIMESTAMPTZ;
