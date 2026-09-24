-- Tracabilite et idempotence des operations generees par AccountingOperationEngine (mission
-- regularisations/cloture, Etape 4). source_type/source_id identifient le document metier
-- d'origine (ex: 'FACTURE'/factureId, 'EXERCICE'/exerciceId, 'REGULARISATION'/regularisationId) -
-- nullable car les propositions deja existantes (achat/vente/reglement/avoir/inventaire) n'en
-- portaient pas jusqu'ici. Le couple (entreprise_id, source_type, source_id, type) permet au
-- moteur de retrouver une proposition deja generee pour une meme operation metier au lieu d'en
-- creer une seconde (une facture validee deux fois ne doit pas produire deux ecritures).
ALTER TABLE accounting_operation_proposal ADD COLUMN source_type VARCHAR(40);
ALTER TABLE accounting_operation_proposal ADD COLUMN source_id UUID;

CREATE INDEX idx_aop_source ON accounting_operation_proposal(entreprise_id, source_type, source_id, type);
