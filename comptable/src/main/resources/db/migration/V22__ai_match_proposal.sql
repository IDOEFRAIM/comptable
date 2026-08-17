-- Traçabilité des propositions du Universal Matching Engine (ai-service) : une proposition est
-- distincte du pointage définitif (cf. RapprochementBancaireService/MatchDecisionEngine). Permet
-- l'audit, la validation humaine, et de futures statistiques de qualité du modèle.
CREATE TABLE ai_match_proposal (
    id                 UUID PRIMARY KEY,
    entreprise_id      UUID NOT NULL REFERENCES entreprise(id),
    source_type        VARCHAR(50) NOT NULL,
    source_id          VARCHAR(100) NOT NULL,
    candidate_type     VARCHAR(50),
    candidate_id       VARCHAR(100),
    confidence         DOUBLE PRECISION NOT NULL,
    decision           VARCHAR(30) NOT NULL,
    match_type         VARCHAR(30),
    reasons_json       JSONB,
    warnings_json      JSONB,
    status             VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    created_at         TIMESTAMPTZ NOT NULL,
    validated_at       TIMESTAMPTZ,
    validated_by_id    UUID REFERENCES utilisateur(id)
);

CREATE INDEX idx_ai_match_proposal_entreprise ON ai_match_proposal(entreprise_id);
CREATE INDEX idx_ai_match_proposal_source ON ai_match_proposal(source_type, source_id);
CREATE INDEX idx_ai_match_proposal_status ON ai_match_proposal(status);
