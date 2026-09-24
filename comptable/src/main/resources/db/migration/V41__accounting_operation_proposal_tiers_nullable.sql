-- Une regularisation d'inventaire (mission stock, Etape 3) n'a pas de tiers - ce n'est ni un
-- achat, ni une vente, ni un reglement. AccountingOperationProposal.tiers devient optionnel ;
-- toutes les propositions existantes (achat/vente/reglement/avoir) continuent de porter un tiers
-- obligatoire cote application (AccountingOperationEngine), cette colonne redevient simplement
-- non contrainte au niveau base pour permettre le nouveau cas d'usage.
ALTER TABLE accounting_operation_proposal ALTER COLUMN tiers_id DROP NOT NULL;
