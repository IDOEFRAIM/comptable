-- Mode administrateur (livraison 3) : suivre l'usage de l'IA par cabinet et pouvoir le plafonner.
-- Une ligne par appel au microservice IA. Les jetons et le coût ne sont pas mesurés (ai-service ne les remonte pas) : on compte les appels, les échecs et la latence.
CREATE TABLE ai_call_log (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  UUID,
    operation  VARCHAR(40) NOT NULL,
    statut     VARCHAR(14) NOT NULL CHECK (statut IN ('OK', 'ECHEC', 'REFUSE_PLAFOND')),
    latence_ms INTEGER,
    erreur     VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_call_log_tenant_date ON ai_call_log (tenant_id, created_at DESC);
CREATE INDEX idx_ai_call_log_date ON ai_call_log (created_at DESC);

-- NULL = pas de plafond (les cabinets existants ne sont donc pas limités).
ALTER TABLE tenant ADD COLUMN quota_appels_ia_mois INTEGER CHECK (quota_appels_ia_mois IS NULL OR quota_appels_ia_mois > 0);
