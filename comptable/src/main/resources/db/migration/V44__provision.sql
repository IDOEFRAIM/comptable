-- Provisions (dotation/reprise totale/reprise partielle - mission cloture Etape 4). nature/objet
-- restent du texte libre : aucune liste fermee de natures de provision n'est verifiee dans ce
-- perimetre, en inventer une serait deviner une regle non confirmee. Les 3 comptes (dotation,
-- provision au bilan, reprise) sont toujours saisis et valides par l'utilisateur, jamais resolus
-- via un role systeme non seede pour ce cas.
CREATE TABLE provision (
    id                   UUID PRIMARY KEY,
    entreprise_id        UUID NOT NULL REFERENCES entreprise(id),
    exercice_id          UUID NOT NULL REFERENCES exercice_comptable(id),
    nature               VARCHAR(255) NOT NULL,
    objet                VARCHAR(500),
    montant_precedent    NUMERIC(15,2) NOT NULL DEFAULT 0,
    montant_nouveau      NUMERIC(15,2) NOT NULL,
    justification        VARCHAR(1000),
    type_mouvement       VARCHAR(30) NOT NULL,
    compte_dotation_id   UUID NOT NULL REFERENCES compte_comptable(id),
    compte_provision_id  UUID NOT NULL REFERENCES compte_comptable(id),
    compte_reprise_id    UUID NOT NULL REFERENCES compte_comptable(id),
    statut               VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    proposition_id       UUID REFERENCES accounting_operation_proposal(id),
    created_by           UUID REFERENCES utilisateur(id),
    created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_provision_entreprise ON provision(entreprise_id);
CREATE INDEX idx_provision_exercice ON provision(exercice_id);
CREATE INDEX idx_provision_statut ON provision(statut);
