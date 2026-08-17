-- Modele generique d'exception/inbox comptable (mission "P0 - Core Intelligent de Production
-- Comptable"). Fusionne les concepts WorkItem/AccountingFinding/AiFinding decrits dans la
-- mission (sections 7 et 18) en un seul modele : aucune autre table generique n'existait dans le
-- schema, et un doublon aurait ete redondant. Meme pattern polymorphe (source_type/source_id) que
-- ai_match_proposal (V22) et audit_log (V1) - pas de nouveau pattern introduit.
CREATE TABLE finding (
    id                  UUID PRIMARY KEY,
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    type                VARCHAR(50) NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    source_type         VARCHAR(50) NOT NULL,
    source_id           VARCHAR(100) NOT NULL,
    title               VARCHAR(300) NOT NULL,
    description         TEXT,
    confidence          DOUBLE PRECISION,
    evidence_json       JSONB,
    recommended_action  VARCHAR(500),
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ,
    resolved_by_id      UUID REFERENCES utilisateur(id)
);

CREATE INDEX idx_finding_entreprise ON finding(entreprise_id);
CREATE INDEX idx_finding_source ON finding(source_type, source_id);
CREATE INDEX idx_finding_status ON finding(status);
CREATE INDEX idx_finding_severity ON finding(severity);
