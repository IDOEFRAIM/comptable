-- Phase B / lot B0 : chaque proposition comptable a une SOURCE METIER identifiable (type, id, evenement) et
-- une meme source ne peut produire qu'UNE consequence comptable active, garantie par la base.

ALTER TABLE accounting_operation_proposal ADD COLUMN source_event VARCHAR(40);

-- Reprise des donnees existantes (developpement) sans jamais creer de collision :
-- 1. une dotation portait l'annee dans source_type ("DOTATION_2027") ;
UPDATE accounting_operation_proposal
   SET source_event = source_type, source_type = 'IMMOBILISATION'
 WHERE source_type LIKE 'DOTATION\_%';
-- 2. une reprise de regularisation avait son propre type de source ;
UPDATE accounting_operation_proposal
   SET source_event = 'REPRISE', source_type = 'REGULARISATION'
 WHERE source_type = 'REGULARISATION_REPRISE';
-- 3. les propositions de l'IA (source distincte du flux Documents) restent distinctes des anciennes ;
UPDATE accounting_operation_proposal
   SET source_event = 'COMPTABILISATION_IA', source_type = 'DOCUMENT'
 WHERE source_type = 'AI_ACCOUNTING_PROPOSAL';
-- 4. les autres sources deja renseignees prennent le type d'operation comme evenement (l'ancienne cle
--    d'idempotence incluait deja ce type) ;
UPDATE accounting_operation_proposal SET source_event = type WHERE source_type IS NOT NULL AND source_event IS NULL;
-- 5. une proposition issue d'une saisie d'operation sans objet metier devient sa propre source.
UPDATE accounting_operation_proposal
   SET source_type = 'OPERATION_SAISIE', source_id = gen_random_uuid(), source_event = 'PROPOSITION'
 WHERE source_type IS NULL;

ALTER TABLE accounting_operation_proposal ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE accounting_operation_proposal ALTER COLUMN source_id SET NOT NULL;
ALTER TABLE accounting_operation_proposal ALTER COLUMN source_event SET NOT NULL;

-- Une cause metier -> au plus une consequence comptable active (une proposition rejetee libere la source).
CREATE UNIQUE INDEX uq_aop_source_event
    ON accounting_operation_proposal (entreprise_id, source_type, source_id, source_event)
    WHERE statut <> 'REJECTED';

-- Immobilisation : l'acquisition est rattachee a la PROPOSITION qui la porte (facture ou moteur), plus a une
-- simple ecriture : on sait par quelle operation, quand, et on remonte a la source.
ALTER TABLE immobilisation ADD COLUMN acquisition_proposal_id UUID REFERENCES accounting_operation_proposal(id);
UPDATE immobilisation i
   SET acquisition_proposal_id = p.id
  FROM accounting_operation_proposal p
 WHERE p.ecriture_id = i.acquisition_ecriture_id AND i.acquisition_ecriture_id IS NOT NULL;
ALTER TABLE immobilisation DROP COLUMN acquisition_ecriture_id;
