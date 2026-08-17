-- Garde d'idempotence pour l'ingestion des messages WhatsApp entrants : Meta livre les
-- evenements webhook au moins une fois (retries possibles en cas de non-reponse rapide),
-- cette table empeche de traiter deux fois le meme message_id.
CREATE TABLE whatsapp_evenement_traite (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id  VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
