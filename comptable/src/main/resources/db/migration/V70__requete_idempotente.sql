-- Hors ligne : les saisies faites sans Internet sont envoyées plus tard, parfois deux fois (la réponse du serveur se perd quand le réseau retombe pendant l'envoi).
-- Chaque envoi porte une clé d'idempotence (en-tête Idempotency-Key) : le serveur n'exécute qu'UNE fois la requête et rejoue la même réponse aux renvois, au lieu de créer une seconde écriture ou pièce.
CREATE TABLE requete_idempotente (
    utilisateur_id UUID         NOT NULL REFERENCES utilisateur(id),
    cle            VARCHAR(100) NOT NULL,
    -- « POST /api/entreprises/{id}/ecritures » : une même clé ne peut pas servir à une autre route ni à un autre dossier
    requete        VARCHAR(300) NOT NULL,
    -- NULL tant que la requête s'exécute ; renseigné une fois la réponse 2xx obtenue
    statut_http    INT,
    corps_reponse  TEXT,
    type_contenu   VARCHAR(100),
    cree_le        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (utilisateur_id, cle)
);

CREATE INDEX idx_requete_idempotente_cree ON requete_idempotente (cree_le);
