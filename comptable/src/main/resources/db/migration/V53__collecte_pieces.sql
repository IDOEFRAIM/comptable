-- Phase C+ : collecte ACTIVE des pieces. Une demande de piece produit un vrai message (outbox), est relancee selon une
-- politique configurable, et se resout quand la piece arrive. Remplace l'ancienne relance quotidienne (relance_piece_envoyee).

-- La demande porte l'operation concernee (instantane lisible par le client : jamais d'identifiant interne dans un message).
ALTER TABLE demande_piece ADD COLUMN operation_type        VARCHAR(30);
ALTER TABLE demande_piece ADD COLUMN tiers_id              UUID;
ALTER TABLE demande_piece ADD COLUMN tiers_nom             VARCHAR(200);
ALTER TABLE demande_piece ADD COLUMN montant               NUMERIC(15,2);
ALTER TABLE demande_piece ADD COLUMN date_operation        DATE;
ALTER TABLE demande_piece ADD COLUMN periode               VARCHAR(7);
ALTER TABLE demande_piece ADD COLUMN description_operation VARCHAR(500);
ALTER TABLE demande_piece ADD COLUMN canal_prefere         VARCHAR(20);
-- Cycle de vie : dates d'envoi / relance / reception, prochaine relance, cause d'un blocage (aucun contact, echec d'envoi...).
ALTER TABLE demande_piece ADD COLUMN sent_at               TIMESTAMPTZ;
ALTER TABLE demande_piece ADD COLUMN derniere_relance_at   TIMESTAMPTZ;
ALTER TABLE demande_piece ADD COLUMN nb_relances           INTEGER NOT NULL DEFAULT 0;
ALTER TABLE demande_piece ADD COLUMN prochaine_relance_at  TIMESTAMPTZ;
ALTER TABLE demande_piece ADD COLUMN received_at           TIMESTAMPTZ;
ALTER TABLE demande_piece ADD COLUMN motif_blocage         VARCHAR(30);
ALTER TABLE demande_piece ADD COLUMN resolu_par            VARCHAR(10);
-- Lien de depot : le jeton est SIGNE (HMAC) et se recalcule ; seule son expiration est stockee.
ALTER TABLE demande_piece ADD COLUMN token_expire_at       TIMESTAMPTZ;

-- Statuts avant cette phase : EN_ATTENTE (= demandee, en attente du client), RECU, RAPPROCHEE, ANNULEE : inchanges.
-- Au plus UNE demande ouverte par ecriture : un double clic ne cree pas deux demandes.
CREATE UNIQUE INDEX uq_demande_ouverte_ecriture ON demande_piece(ecriture_id)
    WHERE ecriture_id IS NOT NULL AND statut IN ('A_ENVOYER', 'EN_ATTENTE', 'RECU', 'ECHEC_ENVOI');
CREATE INDEX idx_demande_relance ON demande_piece(prochaine_relance_at) WHERE statut = 'EN_ATTENTE' AND prochaine_relance_at IS NOT NULL;
CREATE INDEX idx_demande_collecte ON demande_piece(entreprise_id, statut);

-- Boite d'envoi (outbox) : le message est ecrit dans la meme transaction que la demande, un worker l'envoie ensuite.
CREATE TABLE outbound_message (
    id                  UUID PRIMARY KEY,
    entreprise_id       UUID NOT NULL REFERENCES entreprise(id),
    channel             VARCHAR(20) NOT NULL,
    recipient           VARCHAR(100) NOT NULL,
    recipient_name      VARCHAR(200),
    template_code       VARCHAR(50) NOT NULL,
    kind                VARCHAR(20) NOT NULL,
    payload_json        JSONB NOT NULL,
    status              VARCHAR(20) NOT NULL,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    max_attempts        INTEGER NOT NULL,
    next_attempt_at     TIMESTAMPTZ NOT NULL,
    locked_at           TIMESTAMPTZ,
    provider_message_id VARCHAR(200),
    last_error          VARCHAR(500),
    error_kind          VARCHAR(20),
    idempotency_key     VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    sent_at             TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    CONSTRAINT uq_outbound_idempotence UNIQUE (entreprise_id, idempotency_key)
);
CREATE INDEX idx_outbound_a_envoyer ON outbound_message(next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbound_entreprise ON outbound_message(entreprise_id, created_at DESC);

-- Un message peut porter plusieurs demandes (regroupement par client) ; chaque demande garde sa trace individuelle.
CREATE TABLE demande_piece_message (
    demande_id UUID NOT NULL REFERENCES demande_piece(id),
    message_id UUID NOT NULL REFERENCES outbound_message(id),
    PRIMARY KEY (demande_id, message_id)
);
CREATE INDEX idx_dpm_message ON demande_piece_message(message_id);

-- Piece recue et demande : lien confirme ou suggere (jamais choisi arbitrairement).
ALTER TABLE document ADD COLUMN demande_id     UUID;
ALTER TABLE document ADD COLUMN statut_demande VARCHAR(20) NOT NULL DEFAULT 'AUCUNE';
CREATE INDEX idx_document_demande ON document(entreprise_id, statut_demande) WHERE statut_demande <> 'AUCUNE';

DROP TABLE relance_piece_envoyee;
