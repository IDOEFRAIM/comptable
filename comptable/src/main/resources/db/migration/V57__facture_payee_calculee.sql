-- Phase E (E0.1) : le statut PAYEE saisi a la main n'est plus une verite. Le paiement d'une facture se lit dans ses allocations de reglements
-- (payment_allocation) : montant - somme des allocations actives = reste a payer. La colonne facture.statut ne porte plus que le cycle de vie
-- (BROUILLON, ENVOYE, ACCEPTE, REFUSE, CONVERTI, VALIDEE, ANNULEE).

-- Journal des migrations de donnees : ce qui a ete change, pour qui, et pourquoi (jamais de transformation silencieuse).
CREATE TABLE migration_report (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(60) NOT NULL,
    entreprise_id UUID,
    entity_type   VARCHAR(40),
    entity_id     UUID,
    details       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_migration_report_code ON migration_report(code, entreprise_id);

-- Les factures marquees PAYEE a la main sont ramenees a VALIDEE ; chacune est consignee avec le nombre d'allocations actives qu'elle avait
-- (zero pour toutes les donnees anterieures : l'allocation n'existait pas). Leur reste a payer se recalcule desormais depuis les allocations.
INSERT INTO migration_report (code, entreprise_id, entity_type, entity_id, details)
SELECT 'FACTURE_PAYEE_MANUELLE', f.entreprise_id, 'FACTURE', f.id,
       jsonb_build_object('numero', f.numero, 'allocationsActives', 0,
                          'action', 'statut ramene de PAYEE a VALIDEE : le paiement ne se lit plus que dans les allocations de reglements')
  FROM facture f
 WHERE f.statut = 'PAYEE';

UPDATE facture SET statut = 'VALIDEE' WHERE statut = 'PAYEE';
