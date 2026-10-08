-- Snapshot d'un etat financier FINAL (phase F, F11) : fige l'entreprise, l'exercice, la version reglementaire (cadre, version, empreinte de la definition), les montants, les
-- mappings (comptes par ligne), les controles et l'empreinte technique des donnees comptables. Une mise a jour reglementaire ulterieure ne modifie jamais l'etat historique.
-- Seuls les etats FINAL sont persistes (un etat DRAFT/READY se recalcule) ; la table est IMMUABLE : aucune mise a jour ni suppression (trigger).
CREATE TABLE financial_statement_snapshot (
    id                   UUID PRIMARY KEY,
    entreprise_id        UUID NOT NULL REFERENCES entreprise(id),
    exercice_id          UUID NOT NULL REFERENCES exercice_comptable(id),
    statement_type       VARCHAR(30) NOT NULL,
    framework            VARCHAR(60) NOT NULL,
    definition_version   VARCHAR(20) NOT NULL,
    definition_hash      VARCHAR(64) NOT NULL,
    status               VARCHAR(10) NOT NULL DEFAULT 'FINAL' CHECK (status = 'FINAL'),
    dataset_fingerprint  VARCHAR(64) NOT NULL,
    payload              TEXT NOT NULL,
    payload_sha256       VARCHAR(64) NOT NULL,
    created_by           UUID REFERENCES utilisateur(id),
    created_at           TIMESTAMPTZ NOT NULL,
    UNIQUE (entreprise_id, exercice_id, statement_type, framework, definition_version)
);

CREATE INDEX idx_fs_snapshot_exercice ON financial_statement_snapshot(entreprise_id, exercice_id);

CREATE FUNCTION financial_statement_snapshot_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Un etat financier FINAL est immuable (aucune mise a jour ni suppression)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_financial_statement_snapshot_immutable
    BEFORE UPDATE OR DELETE ON financial_statement_snapshot
    FOR EACH ROW EXECUTE FUNCTION financial_statement_snapshot_immutable();
