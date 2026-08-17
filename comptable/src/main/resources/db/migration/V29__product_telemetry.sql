-- Mission P1.9 (section 5 : "ne pas introduire une architecture d'observabilite lourde") : une
-- seule nouvelle table, append-only, pour la seule donnee qui n'a strictement aucune donnee
-- existante a reutiliser (voir docs/P1_9_AUDIT.md section F/G) - la persistance des questions
-- et du feedback du Copilot. Tous les autres KPI de la mission (matching, findings, demandes de
-- pieces, work queue) sont calcules par de nouvelles requetes de lecture sur les tables
-- existantes, jamais par un nouvel evenement.
CREATE TABLE copilot_query (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant(id),
    entreprise_id       UUID REFERENCES entreprise(id),
    utilisateur_id      UUID REFERENCES utilisateur(id),
    portee              VARCHAR(20) NOT NULL,
    question            VARCHAR(500) NOT NULL,
    intent              VARCHAR(50) NOT NULL,
    confidence          DOUBLE PRECISION NOT NULL,
    evidence_count      INTEGER NOT NULL,
    fallback            BOOLEAN NOT NULL,
    latency_ms          BIGINT,
    created_at          TIMESTAMPTZ NOT NULL,
    feedback            VARCHAR(20),
    feedback_reason     VARCHAR(50),
    feedback_at         TIMESTAMPTZ
);

CREATE INDEX idx_copilot_query_tenant_date ON copilot_query(tenant_id, created_at DESC);
CREATE INDEX idx_copilot_query_entreprise ON copilot_query(entreprise_id);
CREATE INDEX idx_copilot_query_feedback ON copilot_query(feedback) WHERE feedback IS NOT NULL;

-- Motif de rejet (mission section 31) : absent aujourd'hui sur les 3 modeles de proposition -
-- rejeter ne fait que changer le statut. Colonne nullable, pas de nouvelle table (section 25 :
-- reutiliser l'existant avant de creer).
ALTER TABLE finding ADD COLUMN rejection_reason VARCHAR(50);
ALTER TABLE ai_match_proposal ADD COLUMN rejection_reason VARCHAR(50);
ALTER TABLE accounting_proposal ADD COLUMN rejection_reason VARCHAR(50);
