-- Regularisations de fin de periode (CCA/PCA/FNP/CAP/PAR - mission cloture Etape 4). Les comptes
-- d'origine et de contrepartie sont toujours saisis et valides par l'utilisateur (jamais devines
-- via un role systeme non verifie) - meme discipline que Article.compteStock/compteAchat/...
-- montant_exercice/montant_futur sont calcules au prorata des dates par le service, jamais une
-- simple saisie manuelle libre lorsque les dates permettent un calcul deterministe.
-- proposition_id/proposition_reprise_id relient respectivement l'ecriture d'origine et la reprise
-- (contre-passation) a leurs propositions AccountingOperationEngine - la reprise n'est jamais
-- generee automatiquement, uniquement sur action explicite si reprise_automatique est vrai.
CREATE TABLE regularisation (
    id                     UUID PRIMARY KEY,
    entreprise_id          UUID NOT NULL REFERENCES entreprise(id),
    exercice_id            UUID NOT NULL REFERENCES exercice_comptable(id),
    type                   VARCHAR(40) NOT NULL,
    date_origine           DATE NOT NULL,
    date_debut             DATE,
    date_fin               DATE,
    montant                NUMERIC(15,2) NOT NULL,
    montant_exercice       NUMERIC(15,2),
    montant_futur          NUMERIC(15,2),
    libelle                VARCHAR(255) NOT NULL,
    compte_origine_id      UUID NOT NULL REFERENCES compte_comptable(id),
    compte_contrepartie_id UUID NOT NULL REFERENCES compte_comptable(id),
    statut                 VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    proposition_id         UUID REFERENCES accounting_operation_proposal(id),
    reprise_automatique    BOOLEAN NOT NULL DEFAULT false,
    date_reprise           DATE,
    proposition_reprise_id UUID REFERENCES accounting_operation_proposal(id),
    created_by             UUID REFERENCES utilisateur(id),
    created_at              TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_regularisation_entreprise ON regularisation(entreprise_id);
CREATE INDEX idx_regularisation_exercice ON regularisation(exercice_id);
CREATE INDEX idx_regularisation_statut ON regularisation(statut);
