-- Nature comptable explicite d'une ecriture (phase F1.5) : distingue ce qui represente l'activite economique de la periode de ce qui n'est qu'une ecriture
-- technique de cloture. Une ecriture de cloture RESTE une vraie ecriture du grand livre ; la nature dit seulement dans quelle vue elle entre.
--   BUSINESS    activite courante (achats, ventes, reglements, saisies manuelles, stock, immobilisations, annulations)
--   ADJUSTMENT  ecriture d'inventaire (regularisations, provisions, depreciations, dotations aux amortissements)
--   CLOSING     determination du resultat de cloture d'exercice
--   OPENING     reserve (aucune ecriture d'a-nouveaux n'est postee aujourd'hui : les soldes de bilan se reportent par cumul)
ALTER TABLE ecriture_comptable ADD COLUMN nature VARCHAR(20) NOT NULL DEFAULT 'BUSINESS';

UPDATE ecriture_comptable SET nature = 'CLOSING'
WHERE id IN (SELECT ecriture_id FROM accounting_operation_proposal WHERE type = 'CLOTURE_EXERCICE' AND ecriture_id IS NOT NULL);

UPDATE ecriture_comptable SET nature = 'ADJUSTMENT'
WHERE id IN (SELECT ecriture_id FROM accounting_operation_proposal
             WHERE type IN ('REGULARISATION', 'REGULARISATION_REPRISE', 'REGULARISATION_INVENTAIRE', 'PROVISION', 'DEPRECIATION', 'AMORTISSEMENT_DOTATION')
               AND ecriture_id IS NOT NULL);

-- Les vues de reporting filtrent par (entreprise, statut, nature, date) : index couvrant la date deja existant (entreprise, date), la nature est peu selective.
