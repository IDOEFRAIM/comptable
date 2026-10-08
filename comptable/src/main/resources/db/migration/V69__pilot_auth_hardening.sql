-- P0 pilote — durcissement de l'authentification : changement de mot de passe obligatoire, révocation des jetons, verrouillage après échecs, réinitialisation à usage unique.

ALTER TABLE utilisateur
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN credentials_version  INT     NOT NULL DEFAULT 0,
    ADD COLUMN failed_login_count   INT     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         TIMESTAMPTZ,
    ADD COLUMN last_login_at        TIMESTAMPTZ;

-- Jeton de réinitialisation émis par un expert-comptable du cabinet (aucune infrastructure e-mail n'est supposée) : seul son empreinte SHA-256 est stockée ; usage unique ; expiration.
CREATE TABLE password_reset_token (
    id             UUID PRIMARY KEY,
    utilisateur_id UUID        NOT NULL REFERENCES utilisateur(id),
    token_hash     VARCHAR(64) NOT NULL UNIQUE,
    expires_at     TIMESTAMPTZ NOT NULL,
    used_at        TIMESTAMPTZ,
    created_by     UUID REFERENCES utilisateur(id),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_password_reset_user ON password_reset_token(utilisateur_id);
