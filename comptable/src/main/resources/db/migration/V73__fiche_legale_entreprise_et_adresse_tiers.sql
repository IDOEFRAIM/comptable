-- Devis et factures imprimables : l'entreprise émettrice doit pouvoir y figurer avec ses mentions légales.
-- L'IFU (numero_contribuable), le RCCM, la forme juridique et le régime fiscal existent déjà ; on ajoute les coordonnées.
ALTER TABLE entreprise ADD COLUMN adresse                VARCHAR(300);
ALTER TABLE entreprise ADD COLUMN telephone              VARCHAR(40);
ALTER TABLE entreprise ADD COLUMN email                  VARCHAR(150);
ALTER TABLE entreprise ADD COLUMN coordonnees_bancaires  VARCHAR(300);
ALTER TABLE entreprise ADD COLUMN mentions_facture       VARCHAR(500);

-- Adresse du client (ou du fournisseur) : mention usuelle d'une facture.
ALTER TABLE tiers ADD COLUMN adresse VARCHAR(300);
