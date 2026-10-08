-- Qualification (declaration humaine, a la saisie) de la nature de tresorerie d'une ecriture manuelle : evenement de financement ou d'investissement
-- reellement encaisse/decaisse. Sert au tableau des flux de tresorerie : la nature cash n'est JAMAIS deduite d'un numero de compte. NULL = non qualifiee.
ALTER TABLE ecriture_comptable ADD COLUMN cash_flow_event VARCHAR(60);
