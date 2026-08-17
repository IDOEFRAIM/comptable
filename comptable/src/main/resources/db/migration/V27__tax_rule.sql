-- Mission P1 "Compliance Center" (section 31) : architecture versionnee pour des regles fiscales
-- par pays/regime, prete a etre peuplee - AUCUNE regle reelle n'est inseree ici. Le Burkina Faso
-- (marche prioritaire) n'a pas de source reglementaire verifiee disponible dans cet environnement ;
-- peupler cette table sans verification reviendrait a fabriquer une obligation fiscale qui
-- pourrait induire un comptable en erreur - explicitement interdit par la mission (section 31 :
-- "L'IA ne doit jamais etre la source juridique" - et par extension, ce chantier non plus sans
-- source verifiee). Dependance documentee dans docs/P1_ARCHITECTURE.md.
CREATE TABLE tax_rule (
    id              UUID PRIMARY KEY,
    pays            VARCHAR(2) NOT NULL,
    regime          VARCHAR(100) NOT NULL,
    code            VARCHAR(50) NOT NULL,
    libelle         VARCHAR(255) NOT NULL,
    description     TEXT,
    effective_from  DATE NOT NULL,
    effective_to    DATE NULL,
    source          VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tax_rule_pays_regime ON tax_rule(pays, regime);
