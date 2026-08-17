-- Module Stocks & Achats (Sprint 11) : catalogue d'articles valorises au PUMP (Prix Unitaire
-- Moyen Pondere) et journal des mouvements d'entree/sortie/ajustement d'inventaire permanent.

-- Comptes de variation de stock (SYSCOHADA), absents du referentiel de depart : necessaires pour
-- contrepartie des mouvements d'entree/sortie (cf. section 3-4 du cahier des charges).
INSERT INTO plan_comptable_reference (numero, intitule, classe, nature) VALUES
    ('6031', 'Variation de stocks de marchandises', 6, 'CHARGE'),
    ('7031', 'Variation de stocks de produits finis', 7, 'PRODUIT');

-- Retrofit : les entreprises deja provisionnees avant cette migration n'ont pas encore ces
-- comptes (le clonage du referentiel ne se fait qu'a la creation de l'entreprise) ; on les
-- ajoute pour toutes les entreprises existantes, comme le ferait clonerPlanComptable().
INSERT INTO compte_comptable (entreprise_id, reference_id, numero, intitule, lettrable)
SELECT e.id, pcr.id, pcr.numero, pcr.intitule, false
FROM entreprise e
CROSS JOIN plan_comptable_reference pcr
WHERE pcr.numero IN ('6031', '7031')
  AND NOT EXISTS (
      SELECT 1 FROM compte_comptable cc WHERE cc.entreprise_id = e.id AND cc.numero = pcr.numero
  );

CREATE TABLE article (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id        UUID NOT NULL REFERENCES entreprise(id),
    code_article         VARCHAR(50) NOT NULL,
    designation          VARCHAR(300) NOT NULL,
    categorie            VARCHAR(100),
    unite_mesure         VARCHAR(20) NOT NULL,
    prix_vente_ht        NUMERIC(15,2) NOT NULL,
    taux_tva             NUMERIC(5,2) NOT NULL DEFAULT 0,
    quantite_en_stock    NUMERIC(15,3) NOT NULL DEFAULT 0,
    valeur_stock_totale  NUMERIC(15,2) NOT NULL DEFAULT 0,
    pump                 NUMERIC(15,4) NOT NULL DEFAULT 0,
    compte_stock_id      UUID NOT NULL REFERENCES compte_comptable(id),
    compte_achat_id      UUID NOT NULL REFERENCES compte_comptable(id),
    compte_vente_id      UUID NOT NULL REFERENCES compte_comptable(id),
    compte_variation_id  UUID NOT NULL REFERENCES compte_comptable(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entreprise_id, code_article)
);

CREATE TABLE mouvement_stock (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id        UUID NOT NULL REFERENCES entreprise(id),
    article_id           UUID NOT NULL REFERENCES article(id),
    type                 VARCHAR(20) NOT NULL,
    quantite             NUMERIC(15,3) NOT NULL,
    prix_unitaire_ht     NUMERIC(15,4) NOT NULL,
    valeur_mouvement     NUMERIC(15,2) NOT NULL,
    quantite_apres       NUMERIC(15,3) NOT NULL,
    pump_apres           NUMERIC(15,4) NOT NULL,
    date_mouvement       DATE NOT NULL,
    libelle              VARCHAR(300),
    ecriture_id          UUID REFERENCES ecriture_comptable(id),
    created_by           UUID REFERENCES utilisateur(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- quantite est positive pour ENTREE/SORTIE ; pour AJUSTEMENT elle porte le signe de l'ecart
    -- constate (positif = surplus, negatif = perte), jamais nulle.
    CHECK (quantite <> 0)
);

CREATE INDEX idx_mouvement_stock_article ON mouvement_stock(article_id, date_mouvement);
