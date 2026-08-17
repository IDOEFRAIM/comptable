-- Date a laquelle une ligne a ete lettree (integrale ou partielle), pour tracabilite/audit et
-- pour une future TVA sur encaissements (date reelle du reglement, distincte de la date de la
-- facture) - la balance agee, elle, continue de se baser sur la date de piece de la ligne, pas
-- sur cette colonne : une ligne partiellement lettree reste "en instance" pour son solde residuel.
ALTER TABLE ligne_ecriture ADD COLUMN date_lettrage DATE;
