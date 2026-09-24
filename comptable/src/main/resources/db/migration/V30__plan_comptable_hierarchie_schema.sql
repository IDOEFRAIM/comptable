-- Renforcement du plan comptable SYSCOHADA : ajoute la hierarchie (parent_id/niveau) et les
-- metadonnees manquantes (sens_normal, compte_collectif, compte_detail, auxiliaire_autorise,
-- actif) sur le referentiel global et sur le PCE de chaque entreprise. Purement additif : aucune
-- colonne supprimee, aucun type modifie, aucune ligne supprimee.

ALTER TABLE plan_comptable_reference
    ADD COLUMN parent_id INT REFERENCES plan_comptable_reference(id),
    ADD COLUMN niveau SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN sens_normal VARCHAR(10),
    ADD COLUMN compte_collectif BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN compte_detail BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN lettrable BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN auxiliaire_autorise BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN actif BOOLEAN NOT NULL DEFAULT true;

-- sens_normal : regle generique a partir de la nature SYSCOHADA. Volontairement generique -
-- toute exception de contre-compte connue est corrigee explicitement juste apres.
UPDATE plan_comptable_reference SET sens_normal = CASE nature
    WHEN 'ACTIF' THEN 'DEBIT'
    WHEN 'CHARGE' THEN 'DEBIT'
    WHEN 'PASSIF' THEN 'CREDIT'
    WHEN 'PRODUIT' THEN 'CREDIT'
END;

-- Exception connue et verifiee dans le seed actuel : 281 "Amortissements des immobilisations
-- corporelles" est de nature ACTIF (classe 2) mais porte normalement un solde CREDITEUR (compte
-- de contre-actif). A VERIFIER : d'autres contre-comptes de ce type pourraient exister une fois
-- le referentiel etendu au-dela de ce seed de demonstration (voir V33).
UPDATE plan_comptable_reference SET sens_normal = 'CREDIT' WHERE numero = '281';

ALTER TABLE plan_comptable_reference ALTER COLUMN sens_normal SET NOT NULL;

-- 401/411 sont les seuls comptes collectifs tiers du seed actuel : lettrables, collectifs, et
-- autorisant une subdivision locale (sous-comptes auxiliaires par tiers, voir TiersService).
UPDATE plan_comptable_reference
SET lettrable = true, compte_collectif = true, auxiliaire_autorise = true
WHERE numero IN ('401', '411');

ALTER TABLE compte_comptable
    ADD COLUMN parent_id UUID REFERENCES compte_comptable(id),
    ADD COLUMN niveau SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN actif BOOLEAN NOT NULL DEFAULT true;

CREATE INDEX idx_pcr_parent ON plan_comptable_reference(parent_id);
CREATE INDEX idx_compte_comptable_parent ON compte_comptable(parent_id);
CREATE INDEX idx_pcr_classe ON plan_comptable_reference(classe);
