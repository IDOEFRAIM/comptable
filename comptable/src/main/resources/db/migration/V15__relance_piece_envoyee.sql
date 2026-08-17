-- Historique des relances WhatsApp envoyees pour des pieces en retard (Sprint 5). Sert a la fois
-- de trace d'audit (quel client relance, quand, via quel message) et de garde anti-spam : le
-- batch quotidien verifie qu'aucune ligne recente n'existe deja pour une demande donnee avant
-- d'envoyer une nouvelle relance.
CREATE TABLE relance_piece_envoyee (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    demande_piece_id       UUID NOT NULL REFERENCES demande_piece(id),
    entreprise_id          UUID NOT NULL REFERENCES entreprise(id),
    utilisateur_client_id  UUID NOT NULL REFERENCES utilisateur(id),
    telephone              VARCHAR(50) NOT NULL,
    statut                 VARCHAR(20) NOT NULL,
    whatsapp_message_id    VARCHAR(255),
    motif_echec            VARCHAR(500),
    envoye_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_relance_demande_piece ON relance_piece_envoyee(demande_piece_id, envoye_at DESC);
CREATE INDEX idx_relance_entreprise ON relance_piece_envoyee(entreprise_id, envoye_at DESC);
