-- Depreciations / pertes de valeur (mission cloture Etape 4). objet_type couvre uniquement les
-- categories explicitement listees par la mission (immobilisation/stock/creance/autre) ; objet_id
-- est une reference polymorphe libre (pas de FK stricte, ces categories ne partagent pas de table
-- commune). Si valeur_recouvrable >= valeur_comptable, aucune perte n'est constatee et aucune
-- ecriture n'est generee (jamais d'ecriture nulle forcee) - la ressource reste tracee pour
-- historique mais son statut ne produit pas de proposition dans ce cas.
CREATE TABLE depreciation (
    id                          UUID PRIMARY KEY,
    entreprise_id               UUID NOT NULL REFERENCES entreprise(id),
    exercice_id                 UUID NOT NULL REFERENCES exercice_comptable(id),
    objet_type                  VARCHAR(30) NOT NULL,
    objet_id                    UUID NOT NULL,
    valeur_comptable            NUMERIC(15,2) NOT NULL,
    valeur_recouvrable          NUMERIC(15,2) NOT NULL,
    justification               VARCHAR(1000),
    compte_dotation_id          UUID NOT NULL REFERENCES compte_comptable(id),
    compte_depreciation_id      UUID NOT NULL REFERENCES compte_comptable(id),
    statut                      VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    proposition_id              UUID REFERENCES accounting_operation_proposal(id),
    created_by                  UUID REFERENCES utilisateur(id),
    created_at                  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_depreciation_entreprise ON depreciation(entreprise_id);
CREATE INDEX idx_depreciation_exercice ON depreciation(exercice_id);
CREATE INDEX idx_depreciation_statut ON depreciation(statut);
