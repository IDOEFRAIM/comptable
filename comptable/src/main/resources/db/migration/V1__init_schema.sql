-- Extension pour la generation d'UUID
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =========================================================================
-- IDENTITE / TENANCY
-- =========================================================================

CREATE TABLE tenant (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raison_sociale  VARCHAR(200) NOT NULL,
    slug            VARCHAR(80) UNIQUE NOT NULL,
    pays            VARCHAR(2) NOT NULL,
    plan_abonnement VARCHAR(30) NOT NULL DEFAULT 'ESSAI',
    statut          VARCHAR(20) NOT NULL DEFAULT 'ACTIF',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(30) UNIQUE NOT NULL,
    libelle     VARCHAR(100) NOT NULL
);

CREATE TABLE permission (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(60) UNIQUE NOT NULL,
    description VARCHAR(200)
);

CREATE TABLE role_permission (
    role_id       INT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id INT NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE utilisateur (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES tenant(id),
    email           VARCHAR(150) UNIQUE NOT NULL,
    mot_de_passe    VARCHAR(255) NOT NULL,
    nom             VARCHAR(100) NOT NULL,
    prenom          VARCHAR(100) NOT NULL,
    telephone       VARCHAR(20),
    statut          VARCHAR(20) NOT NULL DEFAULT 'ACTIF',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_utilisateur_tenant ON utilisateur(tenant_id);

CREATE TABLE utilisateur_role (
    utilisateur_id UUID NOT NULL REFERENCES utilisateur(id) ON DELETE CASCADE,
    role_id        INT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    PRIMARY KEY (utilisateur_id, role_id)
);

-- =========================================================================
-- ENTREPRISES (clients des cabinets)
-- =========================================================================

CREATE TABLE entreprise (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenant(id),
    raison_sociale      VARCHAR(200) NOT NULL,
    numero_contribuable VARCHAR(50),
    rccm                VARCHAR(50),
    forme_juridique     VARCHAR(50),
    regime_fiscal       VARCHAR(50),
    secteur_activite    VARCHAR(100),
    pays                VARCHAR(2) NOT NULL,
    devise              VARCHAR(3) NOT NULL DEFAULT 'XOF',
    statut              VARCHAR(20) NOT NULL DEFAULT 'ACTIF',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entreprise_tenant ON entreprise(tenant_id);

CREATE TABLE utilisateur_entreprise (
    utilisateur_id UUID NOT NULL REFERENCES utilisateur(id) ON DELETE CASCADE,
    entreprise_id  UUID NOT NULL REFERENCES entreprise(id) ON DELETE CASCADE,
    PRIMARY KEY (utilisateur_id, entreprise_id)
);

CREATE TABLE collaborateur_entreprise (
    collaborateur_id UUID NOT NULL REFERENCES utilisateur(id) ON DELETE CASCADE,
    entreprise_id    UUID NOT NULL REFERENCES entreprise(id) ON DELETE CASCADE,
    PRIMARY KEY (collaborateur_id, entreprise_id)
);

-- =========================================================================
-- COMPTABILITE
-- =========================================================================

CREATE TABLE exercice_comptable (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    date_debut    DATE NOT NULL,
    date_fin      DATE NOT NULL,
    statut        VARCHAR(20) NOT NULL DEFAULT 'OUVERT',
    UNIQUE (entreprise_id, date_debut)
);

CREATE TABLE journal (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    code          VARCHAR(10) NOT NULL,
    libelle       VARCHAR(100) NOT NULL,
    type          VARCHAR(20) NOT NULL,
    UNIQUE (entreprise_id, code)
);

-- Referentiel global du plan comptable SYSCOHADA revise (pas de tenant_id : partage entre tous les cabinets)
CREATE TABLE plan_comptable_reference (
    id       SERIAL PRIMARY KEY,
    numero   VARCHAR(10) NOT NULL UNIQUE,
    intitule VARCHAR(200) NOT NULL,
    classe   SMALLINT NOT NULL,
    nature   VARCHAR(20) NOT NULL
);

CREATE TABLE compte_comptable (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    reference_id  INT REFERENCES plan_comptable_reference(id),
    numero        VARCHAR(10) NOT NULL,
    intitule      VARCHAR(200) NOT NULL,
    lettrable     BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (entreprise_id, numero)
);

CREATE TABLE ecriture_comptable (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    exercice_id   UUID NOT NULL REFERENCES exercice_comptable(id),
    journal_id    UUID NOT NULL REFERENCES journal(id),
    numero_piece  VARCHAR(30) NOT NULL,
    date_ecriture DATE NOT NULL,
    libelle       VARCHAR(300),
    statut        VARCHAR(20) NOT NULL DEFAULT 'BROUILLON',
    created_by    UUID REFERENCES utilisateur(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ecriture_entreprise_date ON ecriture_comptable(entreprise_id, date_ecriture);

CREATE TABLE ligne_ecriture (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ecriture_id UUID NOT NULL REFERENCES ecriture_comptable(id) ON DELETE CASCADE,
    compte_id   UUID NOT NULL REFERENCES compte_comptable(id),
    libelle     VARCHAR(300),
    debit       NUMERIC(15,2) NOT NULL DEFAULT 0,
    credit      NUMERIC(15,2) NOT NULL DEFAULT 0,
    lettrage    VARCHAR(10),
    numero_ligne INT NOT NULL,
    CHECK (debit = 0 OR credit = 0),
    CHECK (debit >= 0 AND credit >= 0)
);

CREATE INDEX idx_ligne_ecriture_compte ON ligne_ecriture(compte_id);

-- =========================================================================
-- DOCUMENTS
-- =========================================================================

CREATE TABLE document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    entreprise_id   UUID NOT NULL REFERENCES entreprise(id),
    depose_par_id   UUID REFERENCES utilisateur(id),
    type_document   VARCHAR(30) NOT NULL,
    canal_source    VARCHAR(20) NOT NULL,
    chemin_fichier  VARCHAR(500) NOT NULL,
    statut          VARCHAR(30) NOT NULL DEFAULT 'EN_ATTENTE',
    donnees_ocr     JSONB,
    montant_ht      NUMERIC(15,2),
    montant_tva     NUMERIC(15,2),
    montant_ttc     NUMERIC(15,2),
    date_piece      DATE,
    ecriture_id     UUID REFERENCES ecriture_comptable(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_entreprise_statut ON document(entreprise_id, statut);

-- =========================================================================
-- FISCALITE
-- =========================================================================

CREATE TABLE declaration_fiscale (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entreprise_id UUID NOT NULL REFERENCES entreprise(id),
    type_impot    VARCHAR(30) NOT NULL,
    periode       VARCHAR(20) NOT NULL,
    date_echeance DATE NOT NULL,
    statut        VARCHAR(20) NOT NULL DEFAULT 'A_FAIRE',
    montant       NUMERIC(15,2),
    date_declaration DATE,
    date_paiement DATE,
    piece_jointe_url VARCHAR(500),
    UNIQUE (entreprise_id, type_impot, periode)
);

CREATE INDEX idx_declaration_echeance ON declaration_fiscale(date_echeance);

-- =========================================================================
-- SUPPORT (notification, audit)
-- =========================================================================

CREATE TABLE notification (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenant(id),
    utilisateur_id UUID REFERENCES utilisateur(id),
    type        VARCHAR(30) NOT NULL,
    canal       VARCHAR(20) NOT NULL,
    contenu     TEXT NOT NULL,
    statut      VARCHAR(20) NOT NULL DEFAULT 'A_ENVOYER',
    date_envoi  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID,
    utilisateur_id UUID,
    action      VARCHAR(50) NOT NULL,
    entite      VARCHAR(50) NOT NULL,
    entite_id   VARCHAR(60),
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant ON audit_log(tenant_id);
