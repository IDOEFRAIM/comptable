-- Persistance des checkpoints LangGraph4j (pipeline agentique de traitement des pieces
-- justificatives). Un thread (une execution de graphe, un document) peut rester en pause
-- (attente_validation) plusieurs jours : le checkpoint doit survivre a un redemarrage de
-- l'application, d'ou une table plutot que le MemorySaver en-memoire fourni par defaut.
CREATE TABLE agent_checkpoint (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id      VARCHAR(255) NOT NULL,
    checkpoint_id  VARCHAR(255) NOT NULL,
    node_id        VARCHAR(255) NOT NULL,
    next_node_id   VARCHAR(255) NOT NULL,
    state          JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (thread_id, checkpoint_id)
);

CREATE INDEX idx_agent_checkpoint_thread ON agent_checkpoint(thread_id, created_at DESC);
