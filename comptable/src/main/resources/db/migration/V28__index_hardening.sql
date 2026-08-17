-- Mission P1.5 "Product Hardening" (section 60) : index manquants confirmes par audit reel
-- (pas des suppositions) sur les patterns de requete les plus frequents du systeme.

-- finding : la requete la plus frequente du systeme (Cockpit, Dossier, Inbox, Copilot,
-- Compliance Center) filtre sur (entreprise_id, status) et trie par severity - seuls des index
-- mono-colonnes existaient jusqu'ici.
CREATE INDEX idx_finding_entreprise_status_severity ON finding(entreprise_id, status, severity);

-- ligne_ecriture : idx_ligne_ecriture_compte(compte_id) existait deja, mais aucun index sur
-- ecriture_id - necessaire pour charger rapidement toutes les lignes d'une ecriture (detail
-- d'ecriture, export FEC, validation).
CREATE INDEX idx_ligne_ecriture_ecriture ON ligne_ecriture(ecriture_id);

-- audit_log : idx_audit_tenant(tenant_id) existait deja mais sans le tri par date le plus
-- frequent (journal recent d'abord), et aucun index sur (entite, entite_id) pour l'historique
-- d'un objet precis - les deux patterns de lecture reels de AuditEventRepository (mission P1).
CREATE INDEX idx_audit_log_tenant_date ON audit_log(tenant_id, created_at DESC);
CREATE INDEX idx_audit_log_entite ON audit_log(entite, entite_id, created_at DESC);
