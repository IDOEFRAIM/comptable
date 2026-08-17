-- Mission P1 "Cabinet Operating System" : workflow collaboratif (section 11-13).
-- Un Finding non resolu EST la tache a traiter - pas de nouvelle table Task/WorkItem separee,
-- juste de quoi l'assigner a un collaborateur avec une echeance.
ALTER TABLE finding ADD COLUMN assigned_to_id UUID NULL REFERENCES utilisateur(id);
ALTER TABLE finding ADD COLUMN due_date DATE NULL;

CREATE INDEX idx_finding_assigned_to ON finding(assigned_to_id) WHERE assigned_to_id IS NOT NULL;
