# 📊 Comptable - Plateforme SaaS de Gestion Comptable

## 🎯 Vision du Projet

**Comptable** est une plateforme SaaS (Software as a Service) complète et moderne destinée aux cabinets d'experts-comptables. Elle permet la gestion intégrée de la comptabilité, de la fiscalité et des documents pour leurs clients entreprises.

### Objectifs Principaux
- 📈 **Automatisation** : Réduire le temps de saisie comptable via OCR et extraction de données
- 🏢 **Multi-tenant** : Support de plusieurs cabinets avec isolation totale des données
- 📱 **Accessibilité** : Interface web moderne et intuitive
- 🔒 **Sécurité** : Authentification JWT, gestion des rôles et permissions
- 🌍 **International** : Support multi-pays (XOF - Franc CFA West African)

---

## 🏗️ Architecture Technique

### Stack Technologique

**Backend:**
- 🔧 **Framework** : Spring Boot 4.1.0
- ☕ **Langage** : Java 26
- 🗄️ **Base de données** : PostgreSQL
- 🔐 **Authentification** : JWT (JSON Web Tokens)
- 🔄 **Migrations** : Flyway
- 📦 **ORM** : Spring Data JPA

**Frontend:**
- 🎨 **Framework** : Angular 18.2.0
- 📝 **Langage** : TypeScript
- 🎭 **Styling** : SCSS
- 🔀 **Routage** : Angular Router

**Infrastructure:**
- 🐳 **Conteneurisation** : Docker
- 📦 **Orchestration** : Docker Compose

### Structure du Projet

```
comptable/
├── src/main/java/com/example/comptable/
│   ├── identite/              # Authentification et gestion des utilisateurs
│   ├── entreprise/            # Gestion des entreprises clientes
│   ├── comptabilite/          # Modules comptables (écritures, journaux, plans)
│   ├── document/              # Upload, stockage et OCR
│   ├── fiscalite/             # Déclarations fiscales et TVA
│   ├── dashboard/             # Tableau de bord
│   ├── demande/               # Demandes de documents
│   ├── notification/          # Système de notifications
│   ├── security/              # Sécurité (JWT, permissions)
│   └── common/                # Code partagé
├── frontend/src/app/
│   ├── features/              # Modules métiers
│   ├── core/                  # Services centralisés
│   └── layout/                # Composants de mise en page
├── src/main/resources/db/migration/  # Migrations SQL (Flyway)
└── compose.yaml               # Configuration Docker
```

---

## 🔐 Authentification et Rôles

### Système de Rôles

| Rôle | Code | Description | Permissions |
|------|------|-------------|-------------|
| **Super Administrateur** | `SUPER_ADMIN` | Administrateur SaaS global | Gestion complète du système |
| **Expert-Comptable** | `EXPERT_COMPTABLE` | Chef du cabinet comptable | Gestion du cabinet et de ses clients |
| **Collaborateur** | `COLLABORATEUR` | Employé du cabinet | Assistance à la comptabilité |
| **Client/Entreprise** | `CLIENT` | Entreprise cliente du cabinet | Accès limité à ses données |

### Architecture d'Authentification

```
Client (Frontend)
    ↓ [Email + Mot de passe]
    ↓
Login API (/api/auth/login)
    ↓ [Validation]
    ↓
JWT Token + Infos utilisateur
    ↓ [Token stocké localement]
    ↓
Toutes les requêtes API incluent le JWT
    ↓ [JwtAuthFilter valide le token]
    ↓
Accès accordé/refusé en fonction des permissions
```

### Structure du JWT

Le JWT contient :
- `id` : UUID de l'utilisateur
- `email` : Email de l'utilisateur
- `tenant_id` : ID du cabinet (tenant)
- `roles` : Liste des rôles

---

## 📚 Modules Principaux

### 1️⃣ Module Identité & Authentification (`identite/`)

**Responsabilités:**
- Enregistrement des nouveaux cabinets
- Gestion des utilisateurs
- Attribution des rôles et permissions
- Authentification JWT

**Endpoints Clés:**
```
POST   /api/auth/login              → Connexion utilisateur
POST   /api/auth/register-cabinet   → Enregistrement d'un nouveau cabinet
```

**Modèles de Données:**
- `Tenant` : Cabinet comptable
- `Utilisateur` : Utilisateur avec email, mot de passe, nom/prénom
- `Role` : Rôle (SUPER_ADMIN, EXPERT_COMPTABLE, etc.)
- `Permission` : Permission granulaire

---

### 2️⃣ Module Entreprise (`entreprise/`)

**Responsabilités:**
- Gestion des entreprises clientes du cabinet
- Gestion des associations utilisateurs-entreprises
- Historique et métadonnées des entreprises

**Endpoints Clés:**
```
GET    /api/entreprises               → Lister les entreprises
POST   /api/entreprises               → Créer une entreprise
GET    /api/entreprises/{id}          → Détails d'une entreprise
PUT    /api/entreprises/{id}          → Modifier une entreprise
```

**Modèles de Données:**
- `Entreprise` : Raison sociale, SIRET, RCCM, régime fiscal, devise (XOF)
- `UtilisateurEntreprise` : Lien utilisateur-entreprise
- `CollaborateurEntreprise` : Collaborateurs associés

#### Invitation (`entreprise/service/InvitationService`)

Crée un compte pour un **CLIENT** (rattaché à une seule entreprise via `utilisateur_entreprise`, pour le portail) ou un **COLLABORATEUR** (rattaché au tenant, accès à toutes les entreprises du cabinet). Aucune infrastructure email n'existe encore : un mot de passe temporaire est généré et renvoyé une seule fois dans la réponse, à communiquer manuellement au nouvel utilisateur.

```
POST   /api/entreprises/{id}/inviter   → Inviter un collaborateur ou un client (retourne le mot de passe temporaire)
```

---

### 3️⃣ Module Comptabilité (`comptabilite/`)

**Responsabilités:**
- Gestion des exercices comptables
- Gestion des journaux (Ventes, Achats, Banque, Caisse)
- Création et validation des écritures comptables
- Génération de rapports (Balance, Grand Livre)

**Sous-modules:**

#### A. Plan Comptable & Comptes (`comptabilite/referentiel/`)
```
- Plan Comptable de Référence (SYSCOHADA Révisé - standard international)
- Comptes Comptables par entreprise (avec numérotation personnalisée)
```

#### B. Écritures Comptables (`comptabilite/ecriture/`)
```
POST   /api/entreprises/{id}/ecritures           → Créer une écriture
GET    /api/entreprises/{id}/ecritures           → Lister les écritures
GET    /api/entreprises/{id}/ecritures/{id}      → Détails d'une écriture
DELETE /api/entreprises/{id}/ecritures/{id}      → Supprimer une écriture
```

**Structure d'une Écriture:**
```
EcritureComptable
├── numéro_pièce      (exemple: "FAC-001", "CHQ-050")
├── date_écriture     
├── libellé           (description)
├── statut            (BROUILLON, VALIDÉE, ARCHIVÉE)
├── exercice_id       (exerise auquel elle appartient)
├── journal_id        (journal de saisie)
└── LignesEcriture[]
    ├── compte_id     (compte débité/crédité)
    ├── libellé       
    ├── débit         (montant débité)
    ├── crédit        (montant crédité)
    └── tiers_id      (référence optionnelle à un tiers)
```

#### C. Journaux (`comptabilite/journal/`)
- **Journal de Ventes (VT)** : Factures de vente
- **Journal d'Achats (AC)** : Factures d'achats
- **Journal de Banque (BQ)** : Opérations bancaires
- **Journal de Caisse (CS)** : Opérations de caisse
- **Journal d'Opérations Diverses (OD)** : Écritures d'ajustement

#### D. Rapports (`comptabilite/rapport/`)
```
GET    /api/entreprises/{id}/balance                    → Balance générale
GET    /api/entreprises/{id}/grand-livre                → Grand livre par compte
GET    /api/entreprises/{id}/journal-operations          → Détail des écritures
GET    /api/entreprises/{id}/rapports/fec/{exerciceId}   → Export FEC (fichier .txt)
```

**Types de Rapports:**
- **Balance** : Solde de tous les comptes (colonnes: Débit/Crédit/Solde)
- **Grand Livre** : Détail des mouvements par compte
- **Journal des Opérations** : Chronologie de toutes les écritures
- **Export FEC** (`ExportFecService`) : fichier texte tabulé, 18 colonnes officielles
  (`JournalCode`...`Idevise`), uniquement les écritures `VALIDEE` (les brouillons sont exclus —
  ce filtrage reprend la règle française d'exclusion des brouillons du FEC, **ce n'est pas une
  affirmation de conformité réglementaire générale**, voir périmètre à clarifier ci-dessous).
  `EcritureNum` est recalculé à l'export (séquence par
  journal) faute de numéro de pièce séquentiel persisté par journal dans le modèle actuel —
  documenté comme un compromis technique, pas une règle inventée. Pas de multi-devise
  (`Montantdevise`/`Idevise` toujours vides). **Périmètre réglementaire à clarifier** : le
  format est le FEC français standard (DGI), mais les données de test utilisent un identifiant
  contribuable ivoirien — à valider avec un expert-comptable local avant tout usage réel
  (fonctionnalité préexistante, découverte et documentée ici pour la première fois lors du
  chantier P1 — jamais mentionnée dans ce document jusqu'ici).

#### E. États Financiers SYSCOHADA (`comptabilite/service/EtatsFinanciersService`)

Génère le **Bilan** et le **Compte de Résultat** (système normal, présentation simplifiée) à partir des soldes réels des écritures validées, sans table dédiée : tout est recalculé à la volée par classe de compte (1-5 pour le bilan, 6-7 pour le compte de résultat). Sur un exercice encore **OUVERT**, le bilan injecte un poste provisoire "Résultat net de l'exercice (non affecté)" recalculé en direct.

```
GET    /api/entreprises/{id}/etats-financiers/bilan?exerciceId=...             → Bilan (Actif / Passif)
GET    /api/entreprises/{id}/etats-financiers/compte-resultat?exerciceId=...   → Charges / Produits, résultat net
```

#### F. Clôture d'Exercice (`comptabilite/service/ClotureExerciceService`)

Clôture un exercice comptable : consulte d'abord la **checklist de clôture** (`ClotureReadinessService`, voir module 1️⃣8️⃣) et refuse (422) si un item est `BLOCKING` (jamais de clôture qui contourne un blocage), solde les comptes de charges/produits classes 6-7 (chaque compte soldé selon le **signe réel de son mouvement net** — débit s'il est net créditeur, crédit s'il est net débiteur — et non une direction supposée par sa classe, ce qui reste correct même pour un compte de variation de stock atypique), transfère le résultat vers le compte système `RESULTAT_BENEFICE`/`RESULTAT_PERTE` (résolu via `CompteSystemeResolver`, plus de numéro `120`/`129` codé en dur), le tout construit par `ClotureRule` et posé via `AccountingOperationEngine.proposerEtValiderImmediatement` (mission cloture Étape 4 — voir module 3️⃣.M) plutôt qu'une `EcritureComptable` assemblée à la main. Marque l'exercice **CLOTURE**, puis crée automatiquement l'exercice suivant (année civile) s'il n'existe pas déjà. Les comptes de bilan (classes 1-5) n'ont besoin d'aucune écriture "à nouveaux" : leurs soldes se reportent nativement d'un exercice à l'autre par cumul sur tout l'historique.

```
POST   /api/entreprises/{id}/exercices/{exerciceId}/cloturer   → Clôture l'exercice et ouvre le suivant
```

#### G. Lettrage (`comptabilite/service/LettrageService`)

Rapproche un groupe de lignes d'un même compte tiers lettrable (ex: une facture et son règlement) en leur affectant un code commun (A, B, ... Z, AA, AB, ...), à condition que le groupe soit équilibré (total débit = total crédit) et que toutes les lignes appartiennent à des écritures **VALIDÉES** non déjà lettrées.

```
POST   /api/entreprises/{id}/lettrage                                    → Lettrer un groupe de lignes
DELETE /api/entreprises/{id}/lettrage?compteId=...&code=...              → Délettrer un code
```

#### H. Rapprochement Bancaire (`comptabilite/service/RapprochementBancaireService`)

Importe un relevé bancaire au format CSV (`date;libelle;montant`, montant signé) et tente un appariement automatique de chaque ligne contre les écritures validées du compte de trésorerie concerné (un seul candidat au montant exact sur la période ⇒ pointage auto ; sinon pointage manuel).

```
POST   /api/entreprises/{id}/rapprochements                                          → Importer un relevé CSV
GET    /api/entreprises/{id}/rapprochements                                          → Lister les relevés
GET    /api/entreprises/{id}/rapprochements/{releveId}                               → Détail d'un relevé + écart
POST   /api/entreprises/{id}/rapprochements/{releveId}/lignes/{ligneId}/pointer      → Pointer manuellement
POST   /api/entreprises/{id}/rapprochements/{releveId}/lignes/{ligneId}/depointer    → Dépointer
```

#### I. Immobilisations & Amortissements (`comptabilite/service/ImmobilisationService`)

Registre des immobilisations et génération des dotations aux amortissements (linéaire ou dégressif). Chaque année où une dotation est due, le cabinet déclenche explicitement sa génération : une seule écriture (journal OD) regroupe la dotation de toutes les immobilisations **EN_SERVICE**, avec un débit 681 / crédit compte d'amortissement par actif pour garder une traçabilité individuelle.

```
POST   /api/entreprises/{id}/immobilisations                            → Enregistrer une immobilisation
GET    /api/entreprises/{id}/immobilisations                            → Lister avec plan d'amortissement
POST   /api/entreprises/{id}/immobilisations/generer-dotations?annee=.. → Générer les dotations de l'année
```

#### J. Facturation (`comptabilite/service/FacturationService`)

Devis et factures clients. Un devis suit `BROUILLON → ENVOYE → ACCEPTE|REFUSE`, un devis **ACCEPTE** peut être converti en facture (copie des lignes, `devisOrigineId` renseigné). Une facture suit `BROUILLON → VALIDEE` (construit ses lignes via `VenteRule.construireLignesVenteDepuisMontantsPrecalcules` — montants HT/TVA/TTC déjà agrégés par la facture, aucune re-dérivation TVA — puis pose l'écriture via `AccountingOperationEngine.proposerEtValiderImmediatement`, mission cloture Étape 4 ; même forme d'écriture qu'avant, même contrat externe atomique, gain de traçabilité `sourceType="FACTURE"`/`sourceId` et d'idempotence — une double validation ne produit jamais deux écritures) `→ PAYEE`. `FactureAvoirService.validerAvoir` suit le même principe en miroir (`construireLignesAvoirVenteDepuisMontantsPrecalcules`, `sourceType="FACTURE_AVOIR"`).

```
POST   /api/entreprises/{id}/factures?type=DEVIS|FACTURE                → Créer un devis ou une facture
GET    /api/entreprises/{id}/factures?type=DEVIS|FACTURE                → Lister
POST   /api/entreprises/{id}/factures/{id}/envoyer                      → Passer un brouillon en ENVOYE
POST   /api/entreprises/{id}/factures/{id}/accepter                     → Accepter un devis
POST   /api/entreprises/{id}/factures/{id}/refuser                      → Refuser un devis
POST   /api/entreprises/{id}/factures/{id}/convertir                    → Convertir un devis accepté en facture
POST   /api/entreprises/{id}/factures/{id}/valider?compteProduitId=..   → Valider et générer l'écriture de vente
POST   /api/entreprises/{id}/factures/{id}/marquer-payee                → Marquer une facture validée comme payée
```

#### K. Moteur d'Opérations Courantes (`comptabilite/operation/`)

Sépare explicitement l'**opération métier** (achat, vente, règlement, avoir) de l'**écriture comptable** : une opération ne produit jamais directement une écriture validée. Elle traverse toujours le flux suivant, sans exception :

```
Opération métier → AccountingOperationEngine → règle métier (Achat/Vente/Règlement/Avoir)
   → résolution des comptes (AccountingAccountResolver → mapping du cabinet → CompteSystemeResolver
     → PCE, jamais de numéro codé en dur — voir module 3️⃣.O, mission Étape 6)
   → AccountingOperationProposal (PROPOSED ou NEEDS_REVIEW)
   → contrôles + validation humaine explicite
   → EcritureComptable (VALIDEE)
```

**Règle absolue** : le moteur ne calcule ou ne construit une ligne que si le compte correspondant a été réellement résolu (compte explicitement fourni par l'appelant, mapping configuré par le cabinet, ou compte système configuré via `role_systeme` — dans cet ordre, via `AccountingAccountResolver`, jamais un choix arbitraire ni un numéro deviné), toujours validé par `CompteComptableValidationService`. Si une résolution échoue (compte TVA non configuré, journal absent, règle fiscale introuvable...), la proposition passe en **NEEDS_REVIEW** avec une explication précise — le moteur ne devine jamais un compte ni un taux.

**Convention de signe** : tous les montants sont toujours positifs ; c'est le **type d'opération** qui porte le sens (`ACHAT`, `VENTE`, `REGLEMENT_CLIENT`, `REGLEMENT_FOURNISSEUR`, `AVOIR_VENTE`, `AVOIR_ACHAT`). Un avoir n'est jamais un montant négatif : `AvoirRule` réutilise la construction de lignes de `VenteRule`/`AchatRule` puis inverse débit et crédit.

**Statuts d'une proposition** : `PROPOSED` (complète, en attente de validation humaine) → `APPROVED` (l'écriture réelle est créée, `VALIDEE`) ou `REJECTED` ; `NEEDS_REVIEW` (incomplète, au moins une explication bloquante) ne peut être que rejetée, jamais validée directement.

**Traçabilité et idempotence** (mission cloture Étape 4) : chaque proposition porte désormais `sourceType`/`sourceId` (nullable — ex. `"FACTURE"`/factureId, `"EXERCICE"`/exerciceId, `"REGULARISATION"`/regularisationId), identifiant le document métier d'origine. Avant de construire une proposition, le moteur recherche si une proposition non rejetée existe déjà pour `(entreprise, sourceType, sourceId, type)` et la renvoie telle quelle plutôt que d'en créer une seconde — une facture, une clôture ou une régularisation traitée deux fois ne produit jamais deux écritures. `proposerEtValiderImmediatement(...)` enchaîne proposition + validation dans la même transaction, pour les appelants dont l'action métier EST déjà la confirmation humaine (validation d'une facture/d'un avoir, clôture d'un exercice) — évite une double confirmation redondante sans dupliquer la logique de `valider()`.

**Types d'opération** (`AccountingOperationType`) : `ACHAT`, `VENTE`, `REGLEMENT_CLIENT`, `REGLEMENT_FOURNISSEUR`, `AVOIR_VENTE`, `AVOIR_ACHAT`, `REGULARISATION_INVENTAIRE` (Étape 3), et depuis la mission cloture Étape 4 : `REGULARISATION`, `REGULARISATION_REPRISE`, `PROVISION`, `DEPRECIATION`, `CLOTURE_EXERCICE` (voir module 3️⃣.M).

```
POST   /api/entreprises/{id}/operations/achat/proposition                    → Proposer un achat
POST   /api/entreprises/{id}/operations/vente/proposition                    → Proposer une vente
POST   /api/entreprises/{id}/operations/reglement/proposition                → Proposer un règlement (client/fournisseur)
POST   /api/entreprises/{id}/operations/avoir/proposition                    → Proposer un avoir (vente/achat)
GET    /api/entreprises/{id}/operations/propositions/{id}                    → Détail d'une proposition
POST   /api/entreprises/{id}/operations/propositions/{id}/valider            → Valider (crée l'écriture réelle)
POST   /api/entreprises/{id}/operations/propositions/{id}/rejeter            → Rejeter (motif obligatoire)
```

**✅ IMPLÉMENTÉ** :
- Achats (marchandise/matière première/service/charge/immobilisation/autre) avec contrôle de cohérence nature/classe de compte (une immobilisation ne peut jamais produire le schéma d'achat classique 601/445/401).
- Ventes (marchandise/produit/service/autre) sur le même principe.
- Règlements client/fournisseur, compte de trésorerie résolu selon le moyen de paiement (espèces → caisse, tout le reste → banque) via `role_systeme`, jamais `521`/`571` codés en dur.
- Avoirs vente/achat par inversion des lignes de la règle d'origine (aucune logique dupliquée).
- Frais accessoires (inclus ou non dans la base TVA) et réductions commerciales (remise/rabais/ristourne nettées avant TVA).
- Résolution de la TVA via un référentiel paramétrable (`TaxRule` + `TvaRateResolver`) — **jamais un taux codé en dur**.
- Contrôles : entreprise/tenant, exercice ouvert, comptes actifs/utilisables, tiers de la bonne entreprise et du bon type, tolérance HT+TVA≈TTC, équilibre débit=crédit (revérifié une seconde fois au moment de la validation, défense en profondeur).

**⚠️ À FAIRE / LIMITES ASSUMÉES (non deviné, explicitement documenté)** :
- **`TaxRule` reste vide en production** : aucun taux de TVA n'est présenté comme réglementairement vérifié pour un pays donné. Toute donnée de test doit porter `source = "FIXTURE_DEMO_NON_VERIFIE"` (ou équivalent préfixé `FIXTURE_DEMO`) ; le moteur ajoute alors un avertissement explicite (`sourceDemo`) plutôt que de laisser croire à une couverture fiscale certifiée. **Peupler ce référentiel avec des taux officiels par pays SYSCOHADA reste une mission dédiée, non couverte ici.**
- **Escompte** : structure présente (`TypeReduction.ESCOMPTE`) mais volontairement **non calculé** — la proposition signale "non pris en charge, saisir manuellement" plutôt que de risquer une écriture incorrecte.
- Répartition trésorerie simplifiée : carte et mobile money n'ont pas (encore) de compte système dédié distinct de la banque.
- Hypothèse "un seul journal par type et par entreprise" (posée par `EntrepriseService.creerJournauxParDefaut`), non contrainte en base — point d'extension unique si elle change : `JournalResolver`.
- Numérotation centralisée (`NumeroPieceGenerator`) pour ce moteur, `EcritureComptableService`, `FacturationService`, `FactureAvoirService` et, depuis l'Étape 5, `CommandeAchatService`/`AvoirFournisseurService` (via le moteur) ; `ClotureExerciceService`/`ImmobilisationService` gardent encore leur propre copie du même algorithme (dette résiduelle documentée, pas un oubli). Ceci ne concerne que le numéro de *pièce comptable* : la numérotation du *document métier* (`BC-…`, `FF-…`, `AF-…`) reste un concept séparé, propre à chaque service.
- Pas d'écran de liste des propositions en attente ni d'édition d'une proposition — "Modifier" renvoie vers la saisie manuelle d'écriture existante.

**Frontend** : `frontend/src/app/features/comptabilite/operations/` — un formulaire unique par catégorie (Achat/Vente/Règlement/Avoir) avec bascule de sens pour Règlement/Avoir, et un composant d'aperçu (`proposition-preview`) affichant systématiquement le tableau Compte/Débit/Crédit, l'indicateur d'équilibre, les explications/avertissements, et les actions Valider/Modifier/Rejeter — le comptable voit toujours les comptes proposés avant toute comptabilisation réelle.

#### L. Moteur de gestion des stocks (`stock/`)

Module **déjà existant avant cette mission** (`Article`, `MouvementStock`, `PumpCalculator`, `CommandeAchat` — chaîne bon de commande → réception → facture fournisseur, destockage automatique à la vente), étendu ici plutôt que dupliqué. **La comptabilisation de la facture fournisseur elle-même a depuis été unifiée sur `AccountingOperationEngine` — voir module N (mission Étape 5) ; le reste (mouvements de stock, CUMP, inventaire) est inchangé depuis cette mission-ci :**

```
Article (catalogue + 4 comptes explicites)
    ↓
Mouvement de stock (ENTREE_ACHAT / SORTIE_VENTE / ENTREE_INVENTAIRE / SORTIE_INVENTAIRE / AJUSTEMENT / TRANSFERT / ...)
    ↓
Valorisation (StockValuationService → CUMP)
    ↓
Inventaire physique (comptage → écart)
    ↓
Régularisation → AccountingOperationEngine → proposition → validation humaine → EcritureComptable
```

**✅ IMPLÉMENTÉ** :
- **Valorisation** : `StockValuationService` (interface) / `CumpValuationService` (unique implémentation, délègue à `PumpCalculator` — même calcul déjà testé, aucun changement de comportement). **CUMP est la seule méthode implémentée** ; FIFO/LIFO/coût standard ne sont **pas implémentées** (non suffisamment cadrées pour cette mission, jamais devinées).
- **Convention de signe portée par le type** (comme les opérations de l'Étape 2) : `ENTREE_ACHAT`/`SORTIE_VENTE`/`ENTREE_INVENTAIRE`/`SORTIE_INVENTAIRE` portent toujours une quantité positive, le type indique le sens. Les valeurs historiques `ENTREE`/`SORTIE` restent utilisées telles quelles pour les deux réintégrations préexistantes (annulation de facture, retour sur avoir) qui ne correspondent proprement à aucune catégorie plus fine — choix pragmatique documenté, pas une invention. `ENTREE_PRODUCTION`/`SORTIE_PRODUCTION`/`SORTIE_CONSOMMATION` : enum préparé pour une future mission production, **aucune logique câblée**.
- **Politique de stock négatif configurable par article** (`politiqueStockNegatif` : `INTERDIT` par défaut = comportement historique exact / `AUTORISE_AVEC_ALERTE` / `AUTORISE`) — jamais une politique universelle imposée.
- **Traçabilité** (`sourceType`/`sourceId` sur `MouvementStock`, nullable) : achat → `COMMANDE_ACHAT`, vente → `FACTURE`, avoir → `FACTURE_AVOIR`, régularisation → `INVENTAIRE` ; `null` pour un mouvement manuel (traçable via `createdBy`+`libelle`).
- **Inventaire physique** (`stock/inventaire/`) : `Inventaire` (statuts `BROUILLON → EN_COURS → VALIDE → CLOTURE`) + `LigneInventaire` (stock théorique/compté/écart/valeur, instantanés figés à la saisie — jamais recalculés rétroactivement). Mode **PERMANENT** : théorique = solde/PUMP courants de l'article. Mode **INTERMITTENT** (`Article.modeGestionStock`) : aucun mouvement de stock lors des achats/ventes (la charge/le produit restent comptabilisés normalement) ; le théorique est le dernier comptage validé (ou une saisie manuelle de coût si aucun historique n'existe — jamais un coût deviné à zéro). La formule "variation = final − initial" appliquée est l'identité comptable universelle, pas un détail SYSCOHADA spécifique inventé.
- **Régularisation via `AccountingOperationEngine`, jamais un second système** : `valider()` enregistre les mouvements `ENTREE_INVENTAIRE`/`SORTIE_INVENTAIRE` (met à jour la quantité/valeur immédiatement — le comptage physique est un fait) **sans générer d'écriture**, puis construit une `AccountingOperationProposal` (nouveau type `REGULARISATION_INVENTAIRE`, seule proposition sans tiers) via `InventaireRegularisationRule` — une paire de lignes débit/crédit par article en écart (jamais de regroupement agrégé par compte), comptes re-validés via `CompteComptableValidationService`. L'écriture réelle n'existe qu'après validation humaine de cette proposition via l'écran **Opérations** existant (`/operations/propositions/{id}/valider`, endpoint inchangé de l'Étape 2). `cloturer()` exige que la proposition soit `APPROVED`, sinon 422 ; la clôture relie alors chaque mouvement à l'écriture réelle.
- **`Depot`/`TRANSFERT`** : abstraction minimale d'emplacement, préparée pour des transferts inter-dépôts tracables. Un transfert **n'a aucun impact** sur la quantité/valeur globale de l'article et **ne génère aucune écriture** (déplacement interne, pas un événement comptable ; aucun compte de liaison inter-établissement construit).
- Nouveaux champs `Article` : `type` (nature), `actif`, `stockMinimum`, en plus de la politique/mode ci-dessus.

```
POST/GET  /api/entreprises/{id}/articles, /{articleId}          → CRUD article (PUT pour modifier/désactiver)
POST/GET  /api/entreprises/{id}/mouvements-stock                → mouvements manuels (ENTREE/SORTIE/AJUSTEMENT/TRANSFERT)
POST/GET  /api/entreprises/{id}/depots                          → dépôts
POST/GET  /api/entreprises/{id}/inventaires, /{id}              → inventaires
POST      /api/entreprises/{id}/inventaires/{id}/lignes         → saisie théorique/compté (calcule l'écart)
POST      /api/entreprises/{id}/inventaires/{id}/valider        → régularisation + proposition
POST      /api/entreprises/{id}/inventaires/{id}/cloturer       → exige la proposition APPROVED
```

**⚠️ LIMITES ASSUMÉES (non devinées)** :
- À l'époque de cette mission (Étape 3), le reste du module stock (achats/ventes) continuait de générer ses propres écritures directement — seule la régularisation d'inventaire passait par `AccountingOperationEngine`. **Ceci a été résolu pour les achats à l'Étape 5 (voir module N)** ; les ventes l'étaient déjà depuis l'Étape 4 (module M).
- Si la proposition de régularisation est rejetée, l'inventaire reste bloqué (pas de flux de re-proposition dans cette fondation).
- Carte/mobile money, production/consommation, comptes de liaison inter-dépôts : non implémentés (hors périmètre explicite de cette mission).

**Frontend** : `frontend/src/app/features/comptabilite/stock/stock.component.ts` (existant, étendu) — onglet **Inventaires** ajouté à côté d'Articles/Mouvements ; nouveau `inventaire/inventaire-detail.component.ts` (saisie théorique/compté/écart, actions Valider/Clôturer, bandeau "Écriture comptable générée" une fois `ecritureId` renseigné). Nouveau modèle dédié `models/inventaire.model.ts` (même convention que `plan-comptable.model.ts`/`operations.model.ts`).

#### M. Régularisations, Provisions, Dépréciations et Clôture SYSCOHADA (mission cloture Étape 4)

Complète le cycle de fin de période et poursuit l'unification du moteur comptable amorcée à l'Étape 2, sans dupliquer `AccountingOperationEngine` :

```
Exercice → Contrôles pré-clôture (ClotureChecklist) → Régularisations / Provisions / Dépréciations
   → AccountingOperationEngine → AccountingOperationProposal → validation humaine
   → EcritureComptable → Détermination du résultat → CLOTURE → Exercice suivant
```

**✅ IMPLÉMENTÉ** :
- **Régularisations de fin de période** (`comptabilite/regularisation/`, table `regularisation`) — 6 types minimum (`RegularisationType`) : `CHARGE_CONSTATEE_AVANCE` (CCA), `PRODUIT_CONSTATE_AVANCE` (PCA), `FACTURE_NON_PARVENUE` (FNP), `CHARGE_A_PAYER` (CAP), `PRODUIT_A_RECEVOIR`, `PRODUIT_A_REPORTER` (partage exactement la mécanique de PCA — **aucune différence de traitement vérifiée entre les deux dans ce périmètre, donc aucune inventée**, seule la classification diffère). Répartition temporelle **déterministe** au prorata du nombre de jours (`RegularisationService`, jamais une simple saisie manuelle libre lorsque `dateDebut`/`dateFin` le permettent) : la part au-delà de la fin de l'exercice devient `montantFutur`, le reste `montantExercice`. `RegularisationRule` (`comptabilite/operation/regularisation/`) construit les lignes : CCA/PCA/PAR soldent `montantFutur` à la date de fin d'exercice ; FNP/CAP/produit-à-recevoir soldent le `montant` entier (accrual entièrement rattaché à l'exercice courant). **Aucune nouvelle valeur `RoleCompteSysteme` n'a été ajoutée** : `compteOrigine`/`compteContrepartie` sont toujours saisis explicitement par l'utilisateur et validés via `CompteComptableValidationService` (même discipline qu'`Article`) — jamais un numéro de compte deviné (476/477/408...).
- **Reprise (contre-passation)** : `repriseAutomatique`/`dateReprise`/proposition de reprise dédiée — **jamais générée automatiquement**, uniquement sur action explicite (`proposerReprise`) une fois la régularisation d'origine `VALIDEE`.
- **Provisions** (`comptabilite/provision/`, table `provision`) : `DOTATION` (débite le compte de dotation / crédite la provision pour l'augmentation `montantNouveau − montantPrecedent`), `REPRISE_TOTALE` (reprend `montantPrecedent` entier — le risque n'existe plus), `REPRISE_PARTIELLE` (reprend uniquement la diminution). `nature`/`objet` restent du texte libre — aucune liste fermée de natures n'est vérifiée, en inventer une serait deviner.
- **Dépréciations** (`comptabilite/depreciation/`, table `depreciation`) : `perte = max(0, valeurComptable − valeurRecouvrable)` sur `objetType` ∈ `{IMMOBILISATION, STOCK, CREANCE, AUTRE}` (liste reprise telle quelle du besoin exprimé, rien ajouté). Si la valeur recouvrable couvre la valeur comptable, **aucune ligne n'est générée** (jamais d'écriture nulle forcée) — la ressource reste tracée pour historique via son statut.
- Régularisations/provisions/dépréciations partagent le même cycle propose→valide/rejette que l'inventaire (Étape 3) : `POST .../proposition` (jamais auto-validée, exige toujours une confirmation humaine séparée), puis validation/rejet via le moteur commun.
- **Checklist de clôture structurée** et **`ClotureExerciceService` sécurisé par cette checklist** — voir modules 3️⃣.F et 1️⃣8️⃣.
- **Unification vente/avoir** (`FacturationService.validerFacture`, `FactureAvoirService.validerAvoir`) — voir module 3️⃣.J.
- **Règlements déjà unifiés** (aucune écriture n'est créée hors `ReglementRule`/`AccountingOperationEngine` depuis l'Étape 2) — vérifié, aucun changement nécessaire.

```
GET/POST  /api/entreprises/{id}/regularisations, /{id}                    → CRUD + détail
POST      /api/entreprises/{id}/regularisations/{id}/proposition          → Proposer l'écriture
POST      /api/entreprises/{id}/regularisations/{id}/valider              → Valider (délègue au moteur)
POST      /api/entreprises/{id}/regularisations/{id}/rejeter              → Rejeter
POST      /api/entreprises/{id}/regularisations/{id}/reprise              → Proposer la contre-passation
POST      /api/entreprises/{id}/regularisations/{id}/reprise/valider      → Valider la reprise
GET/POST  /api/entreprises/{id}/provisions, /{id}                         → CRUD + détail
POST      /api/entreprises/{id}/provisions/{id}/proposition|valider|rejeter
GET/POST  /api/entreprises/{id}/depreciations, /{id}                      → CRUD + détail
POST      /api/entreprises/{id}/depreciations/{id}/proposition|valider|rejeter
```

**⚠️ LIMITES ASSUMÉES (non devinées)** :
- **`CommandeAchatService` (achats) a depuis été unifié à l'Étape 5** — voir module N. Au moment de cette mission (Étape 4), il restait non unifié pour la raison décrite ci-après, qui a motivé l'ajout d'`AchatRule.construireMultiLigne` plutôt qu'une réutilisation forcée de `construireLignesCore` : son écriture agrège potentiellement plusieurs comptes de charge par ligne, une forme structurellement incompatible avec `AchatRule.construireLignesCore` (compte de charge unique).
- **Aucune nouvelle valeur `RoleCompteSysteme`** pour CCA/PCA/FNP/CAP/PAR/provisions/dépréciations — tous ces comptes restent à la charge de l'utilisateur, validés mais jamais devinés (pas de numéro SYSCOHADA non vérifié seedé).
- **Checklist de clôture partiellement `NOT_APPLICABLE`** : banque, caisse, rapprochement, lettrage, fournisseurs, clients, stocks, immobilisations, amortissements, fiscalité — aucune logique de contrôle approfondie construite pour ces points dans cette mission, honnêtement signalé plutôt que simulé.
- **`PRODUIT_A_REPORTER` ≡ `PRODUIT_CONSTATE_AVANCE`** mécaniquement — distinction de classification uniquement.
- **`DossierComptableService`** (3ᵉ calcul indépendant de "prêt à clôturer", voir module 1️⃣8️⃣) reste **séparé**, non unifié avec la nouvelle `ClotureChecklist` — deux implémentations de la même question coexistent, documenté comme dette pré-existante non résolue ici.
- Amortissements : module existant réutilisé tel quel (voir 3️⃣.I) — non reconstruit, aucune tentative de le faire passer par le moteur commun dans cette mission.

**Frontend** : `frontend/src/app/features/comptabilite/regularisations/regularisations.component.ts` (nouveau) — liste + création + proposition ; la validation/le rejet réutilisent l'écran **Opérations** existant (même convention que l'inventaire, pas de second écran de validation dupliqué). Le tableau de bord (`tableau-de-bord.component.*`) affiche désormais la checklist de clôture structurée (une ligne ✓/⚠/🔴 par contrôle avec message) au lieu de deux listes plates.

> Vérifié de bout en bout par `comptabilite/ClotureRegularisationIntegrationTest.java` (Postgres réel) : entreprise → PCE → exercice → fournisseur/client → achat → stock → vente → règlement → CCA/PCA créées → proposées → validées → écritures équilibrées → readiness sans blocage → clôture → exercice suivant créé sans doublon → idempotence (double validation de facture refusée) → isolation multi-tenant.

#### N. Unification du cycle achats/fournisseurs (mission Étape 5)

Dernier flux opérationnel restant sur l'ancien mécanisme d'écriture directe — migré vers `AccountingOperationEngine`, sans dupliquer le moteur :

```
Commande fournisseur → Réception (MouvementStockService, inchangé)
   → Facture fournisseur → AchatRule.construireMultiLigne → AccountingOperationEngine
   → AccountingOperationProposal → validation (immédiate, voir ci-dessous) → EcritureComptable
   → Avoir fournisseur (AvoirRule.inverserLignes) → même moteur
```

**✅ IMPLÉMENTÉ** :
- **`AchatRule.construireMultiLigne`** (nouveau, `AchatRule.construire`/`construireLignesCore` restent inchangés) : accepte une liste de lignes (`LigneAchatOperationRequest` : désignation, `NatureAchat`, montant HT, taux de TVA explicite, compte) portant chacune sa **propre nature et son propre compte** — une facture peut ainsi débiter 601 (marchandises) et 604 (matières) sur la même pièce, jamais agrégés dans un seul compte. `NatureAchat` étendu additivement avec `CONSOMMABLE` et `FRAIS_ACCESSOIRE` (les valeurs existantes `MARCHANDISE/MATIERE_PREMIERE/SERVICE/CHARGE/IMMOBILISATION/AUTRE` sont inchangées) ; un frais de transport est modélisé comme une ligne à part entière de nature `FRAIS_ACCESSOIRE`, pas comme un sous-attribut d'une autre ligne.
- **Résolution de compte par ligne, priorité stricte, jamais devinée** : chaque ligne doit porter un `compteId` explicitement résolu par l'appelant (ex. `CommandeAchatService` via `Article.compteAchat`) ; si absent, la ligne est écartée avec une explication (`NEEDS_REVIEW`) — **aucune table "nature → compte par défaut" n'a été créée** (elle n'existait nulle part avant cette mission ; l'inventer aurait été une configuration non demandée avec certitude). *(Cette table existe depuis la mission Étape 6 — voir module O — mais reste une configuration explicite par cabinet, jamais un défaut implicite.)*
- **TVA ligne par ligne via un taux explicite**, pas `TvaRateResolver`/`tax_rule` — même convention déjà en place côté vente (`LigneFacture.tauxTva`, consommée directement par `FacturationService`). Ce n'est pas un second moteur TVA : `AchatRule.construire()` (chemin manuel de l'écran Opérations) continue d'utiliser `TvaRateResolver` sans changement ; seul le nouveau chemin document (`construireMultiLigne`) utilise le taux explicite porté par la ligne. Le compte TVA déductible était résolu une seule fois via `CompteSystemeResolver.resoudre(..., TVA_DEDUCTIBLE)` (remplace le littéral `"4456"`) ; absent → explication `NEEDS_REVIEW`, jamais une exception 500. **Depuis la mission Étape 6, ce même appel passe par `AccountingAccountResolver` (purpose `TVA_RECUPERABLE`)**, qui essaie d'abord un mapping du cabinet puis retombe sur ce même `CompteSystemeResolver` — comportement par défaut inchangé, surchargeable désormais.
- **`CommandeAchatService.genererFactureFournisseur` migré** : ne construit plus `EcritureComptable`/`LigneEcriture` à la main. Reconstruit les lignes via `LigneAchatMapper` (nouvelle classe utilitaire partagée, package-privée), puis délègue à `AccountingOperationEngine.proposerAchatEtValiderImmediatement` (nouvelle méthode, même discipline que `FacturationService`/`FactureAvoirService` : la génération de la facture EST la confirmation humaine, propose et valide dans la même transaction). Journal résolu via `JournalResolver` (plus de littéral `"ACH"`). Idempotence : `sourceType="FACTURE_FOURNISSEUR"`, `sourceId=facture.getId()` — rejouer la génération sur une commande déjà `FACTUREE` échoue avant même d'atteindre le moteur (409, garde de premier niveau inchangée).
- **Avoir fournisseur documentaire** (nouveau, table `avoir_fournisseur`, un avoir total par facture — pas de sélection partielle des lignes dans cette mission) : `AvoirFournisseurService` reconstruit les mêmes lignes que la facture d'origine (`LigneAchatMapper` + `AchatRule.construireMultiLigne`), les inverse via `AvoirRule.inverserLignes` (nouvelle méthode publique, extraite du helper privé déjà utilisé par l'avoir générique `AVOIR_ACHAT`), puis propose+valide via le même moteur. Réintègre le stock (sortie générique, comptes de stock/variation via `MouvementStockService`, même mécanisme que tout retour) si `retourStock=true`, en ignorant les articles en mode `INTERMITTENT`.
- **Traçabilité** : `sourceType`/`sourceId` déjà existants depuis l'Étape 4 (`AccountingOperationProposal`) sont désormais renseignés pour la facture (`FACTURE_FOURNISSEUR`) et l'avoir (`AVOIR_FACTURE_FOURNISSEUR`) fournisseur — aucun nouveau mécanisme construit, seulement utilisé.

```
POST /api/entreprises/{id}/avoirs-fournisseur/factures/{factureId}   → créer + valider l'avoir (atomique)
GET  /api/entreprises/{id}/avoirs-fournisseur, /{avoirId}            → liste / détail
```

**⚠️ LIMITES ASSUMÉES (non devinées)** :
- Pas de configuration "nature → compte par défaut" par entreprise — chaque ligne doit toujours résoudre un compte explicite (via un article, ou une saisie manuelle future), sinon `NEEDS_REVIEW`.
- Avoir fournisseur : total uniquement dans cette mission, pas de retour partiel d'une sélection de lignes de la commande.
- Emballages, distinction réduction commerciale/financière au-delà de `TypeReduction` existant, capitalisation des frais accessoires dans le coût du stock : architecture compatible (nature `FRAIS_ACCESSOIRE` dédiée, `TypeReduction` déjà là), **aucune règle implémentée** — non suffisamment vérifiée pour cette mission.
- `CommandeAchatService.validerReception`/`MouvementStockService` inchangés (déjà délégués depuis l'Étape 3) ; `ComptabilisationService` (chemin document→écriture legacy distinct, hors périmètre de toutes les missions à ce jour) reste non touché.
- Frontend : pas de nouvel écran de prévisualisation avant validation (comme `FacturationService`/`FactureAvoirService`, la génération de la facture est elle-même la confirmation) — l'écran achats existant affiche les comptes réellement posés *après* génération plutôt qu'un texte figé.

**Frontend** : `frontend/src/app/features/comptabilite/stock/achats.component.ts` (existant, étendu) — après génération d'une facture fournisseur, affiche le tableau réel Compte/Intitulé/Débit/Crédit (`FactureFournisseurDto.lignesEcriture`, alimenté directement par la proposition retournée par le moteur) et un bouton "Avoir fournisseur" déclenchant le retour physique en stock.

> Vérifié de bout en bout par `stock/service/CommandeAchatIntegrationTest.java` (Postgres réel) : entreprise → PCE → comptes d'achat (601/604/4456/401) → fournisseur → 2 articles de comptes distincts → commande multi-lignes → réception → vérification stock → facture fournisseur → vérification comptes/TVA/total équilibré → rejeu (409, pas de doublon) → avoir fournisseur → inversion vérifiée → stock réintégré → second avoir refusé → isolation multi-tenant.

#### O. Paramétrage comptable par entreprise et moteur de correspondance des comptes (mission Étape 6)

Jusqu'ici, chaque opération exigeait un compte **explicitement saisi** ou passait par les 9 rôles fixes de `CompteSystemeResolver` (TVA collectée/déductible, dotations, résultat, racines tiers) — aucun mécanisme ne permettait à un cabinet de configurer "quel compte PCE utiliser pour telle nature d'opération" au-delà de ces 9 rôles. Cette mission généralise `CompteSystemeResolver` (jamais dupliqué ni supprimé — il reste le repli de dernier niveau) sans jamais deviner un compte à partir d'un numéro qui "semble logique" :

```
Opération → Nature/contexte → AccountingAccountResolver → Paramétrage comptable de l'entreprise
   → Compte PCE → AccountingOperationEngine → Proposition → Validation humaine
```

**✅ IMPLÉMENTÉ** :
- **`AccountingAccountResolver`** (nouveau, `comptabilite/parametrage/`) : point d'entrée unique, chaîne de priorité stricte —
  1. compte explicite fourni par l'opération (`EXPLICITE`, aucune requête mapping) ;
  2. mapping actif de l'entreprise spécifique au contexte (`MAPPING_CONTEXTUEL`, ex. par moyen de paiement) ;
  3. mapping actif générique de l'entreprise (`MAPPING_GENERIQUE`) ;
  4. rôle système historique correspondant, via `CompteSystemeResolver` (`ROLE_SYSTEME`) — seuls certains purposes en ont un (TVA, trésorerie, résultat, dotation, racines tiers ; achats/ventes/stock/immobilisation-actif n'en ont aucun) ;
  5. `NOT_CONFIGURED` — jamais un compte deviné.

  Le compte trouvé aux étapes 2-4 est **toujours revalidé** via `CompteComptableValidationService` avant d'être renvoyé : un compte désactivé après coup rend le mapping `INVALID` dynamiquement, jamais silencieusement ignoré. Plusieurs mappings actifs de même rang (structurellement impossible en base, voir plus bas, mais revérifié en lecture par défense en profondeur) produisent `AMBIGUOUS`, jamais un choix arbitraire.
- **`AccountPurpose`** (23 valeurs, liste fermée du besoin exprimé — achats/ventes par nature, TVA collectée/récupérable, stock/variation de stock, client/fournisseur racine, banque/caisse, immobilisation corporelle/incorporelle/amortissement/dotation, résultat bénéfice/perte) : chaque valeur porte une classe SYSCOHADA attendue, utilisée **uniquement à la validation d'un mapping** (jamais à la résolution — la résolution ne raisonne jamais sur un numéro de compte).
- **`AccountingAccountMapping`** (nouvelle table, `entreprise_id` obligatoire — un mapping n'existe jamais sans entreprise et n'affecte jamais une autre entreprise) : deux index uniques **partiels** (`context_key IS NULL` / `IS NOT NULL`) plutôt qu'une contrainte `UNIQUE` naïve, car PostgreSQL ne bloque pas les doublons `NULL` sous une contrainte unique classique — un doublon actif est ainsi structurellement impossible en base, pas seulement évité côté application.
- **Validation à la création/modification** (`AccountingAccountMappingService`) : le compte doit appartenir à l'entreprise, être actif et utilisable, et sa classe doit être compatible avec le purpose (422 sinon) ; aucun doublon actif pour le même (purpose, contextKey) n'est accepté (409 sinon).
- **Désactivation d'un compte du PCE** (`PlanComptableService.modifier`) : **jamais bloquée**, mais le nombre de mappings actifs concernés est renvoyé (`CompteComptableDto.mappingsActifsCount`) pour que le cabinet sache qu'il doit reconfigurer avant que les opérations concernées redeviennent utilisables — le mapping n'est jamais supprimé silencieusement, `AccountingAccountResolver` le détecte comme `INVALID`.
- **Audit** : chaque `CREATE_MAPPING`/`UPDATE_MAPPING`/`DELETE_MAPPING`/`ACTIVATE_MAPPING`/`DEACTIVATE_MAPPING` est tracé via `AuditTrailService` existant (aucun second système d'audit créé).
- **Intégration par module, portée mesurée** — chaque module tente le résolveur avant de renoncer, aucun ne construit de règle de correspondance en dur :
  - `AchatRule.construireMultiLigne` : ligne sans `compteId` → tente le résolveur depuis `NatureAchat` (`MARCHANDISE/MATIERE_PREMIERE/CONSOMMABLE/SERVICE/FRAIS_ACCESSOIRE/IMMOBILISATION` → purpose correspondant ; `CHARGE`/`AUTRE` n'ont pas d'équivalent dans la liste minimale → `NEEDS_REVIEW` direct, jamais un purpose inventé) ; TVA déductible via purpose `TVA_RECUPERABLE`. `AchatRule.construire()`/`construireLignesCore()` (chemin manuel de l'écran Opérations) restent inchangés.
  - `FacturationService.validerFacture` : TVA collectée via purpose `TVA_COLLECTEE` (remplace l'appel direct à `CompteSystemeResolver`) ; `compteProduitId` reste explicite (une facture porte un seul compte pour toutes ses lignes).
  - `ReglementRule` : trésorerie via `BANQUE`/`CAISSE`, `contextKey` = moyen de paiement (permet une correspondance spécifique par moyen de paiement, ex. une banque différente pour les virements).
  - `ImmobilisationService.genererDotations` : remplace le hardcode `findByEntrepriseIdAndNumero(entrepriseId, "681")` par le purpose `DOTATION_AMORTISSEMENT` — dernier vrai hardcode numérique identifié par l'audit préalable de cette mission. **Le calcul des amortissements lui-même n'a pas été modifié.**
  - `ClotureRule` : résultat bénéfice/perte via `RESULTAT_BENEFICE`/`RESULTAT_PERTE` (remplace l'appel direct à `CompteSystemeResolver`).
  - `Article`/`Immobilisation`/régularisations/provisions/dépréciations : gardent leurs comptes toujours explicites (`NOT NULL`) — déjà conformes à "jamais deviné", non modifiés ; le résolveur reste disponible pour l'écran de test de résolution.

```
GET/POST  /api/entreprises/{id}/parametres-comptables/mappings              → lister (filtrable ?purpose=) / créer
PUT/DELETE /api/entreprises/{id}/parametres-comptables/mappings/{mappingId} → modifier / supprimer
POST      /api/entreprises/{id}/parametres-comptables/test-resolution      → prévisualiser une résolution (purpose + contexte)
```

**⚠️ LIMITES ASSUMÉES (non devinées)** :
- `compteProduitId` de facture reste explicite — pas de nature par ligne à ce niveau de document.
- `Article`/`Immobilisation`/régularisations/provisions/dépréciations gardent leurs comptes toujours explicites — le résolveur ne change rien à leur écriture, seulement disponible pour une future pré-suggestion frontend, non implémentée ici.
- `priorite` sur le mapping est un champ réservé, non fonctionnellement utilisé — les doublons de même rang sont rendus impossibles par contrainte plutôt que départagés (aucun mécanisme de départage arbitraire inventé, le brief l'interdit explicitement).
- `ComptabilisationService`/`DeclarationFiscaleService`/`FactureAvoirService`/`CommandeAchatService`/`ClotureExerciceService`/`TiersService` (racines 401/411) restent hors périmètre, comme dans toutes les missions précédentes — voir le javadoc mis à jour de `CompteSystemeResolver` pour l'état exact des sites d'appel restants.
- Aucune correspondance réglementaire n'a été auto-inventée : un purpose sans mapping ni rôle système reste `NOT_CONFIGURED`, jamais un numéro SYSCOHADA "probable" seedé automatiquement.

**Frontend** : `frontend/src/app/features/comptabilite/parametres-comptables/parametres-comptables.component.ts` (nouvel écran, accessible depuis le workspace entreprise juste après "Plan comptable") — un tableau par groupe (Achats/Ventes/TVA/Trésorerie/Tiers/Stock/Immobilisations/Résultat), un sélecteur de compte PCE réel par purpose (jamais un numéro saisi librement), un badge de statut par purpose calculé via un vrai appel à `test-resolution` (`✓ Résolu` / `⚠ Non configuré` / `🔴 Ambigu` / `⛔ Invalide`), et un panneau « Tester la résolution » libre. `PlanComptablePageComponent` affiche l'avertissement `mappingsActifsCount` lors d'une désactivation.

> Vérifié de bout en bout par `comptabilite/parametrage/AccountingAccountMappingIntegrationTest.java` (Postgres réel) : deux entreprises → PCE → mappings différents pour le même purpose → résolutions testées isolées → achat multi-ligne résolu via mapping → facture (TVA) résolue via mapping → règlement (banque) résolu via mapping → désactivation du compte mappé → résolution `INVALID` → opération refusée (jamais un compte deviné à la place) → réactivation → rejeu réussi → mapping cross-tenant refusé (404) → classe incompatible refusée (422) → doublon actif refusé (409) → suppression explicite du mapping → retour à `NOT_CONFIGURED` → audit trail vérifié (`CREATE_MAPPING`/`DELETE_MAPPING`).

---

### 4️⃣ Module Documents (`document/`)

**Responsabilités:**
- Upload et stockage sécurisé des documents
- Extraction OCR des données de documents
- Gestion du cycle de vie des documents

**Endpoints Clés:**
```
POST   /api/entreprises/{id}/documents          → Upload un document
GET    /api/entreprises/{id}/documents          → Lister les documents
GET    /api/entreprises/{id}/documents/{id}     → Détails du document
```

**Types de Documents:**
- `FACTURE` : Facture client ou fournisseur
- `BULLETIN_PAIE` : Bulletin de salaire
- `RELEVÉ_BANCAIRE` : Relevé de compte
- `DEVIS` : Devis/Commande
- `AUTRE` : Autres documents

**Canaux de Source:**
- `EMAIL` : Document reçu par email
- `UPLOAD` : Téléchargement manuel
- `API` : Fourni via API
- `NUMÉRISATION` : Scanné

**Statuts:**
- `REÇU` : Juste uploadé
- `EN_TRAITEMENT` : OCR en cours
- `TRAITÉ` : OCR terminé, prêt pour comptabilisation
- `COMPTABILISÉ` : Inclus dans une écriture
- `ARCHIVÉ` : Document ancien

**Système de Stockage:**
- Stockage local : `./data/documents/` (configurable via `DOCUMENTS_STORAGE_DIR`)
- Limitation : 10 MB par fichier, 10 MB par requête

**Extraction OCR:**
- Service d'extraction heuristique (implémentation locale)
- Extraction de : date, montant total, TVA, description
- Préparation pour comptabilisation semi-automatique

---

### 5️⃣ Module Fiscalité (`fiscalite/`)

**Responsabilités:**
- Gestion des déclarations fiscales
- Gestion des calendriers de TVA
- Suivi des obligations fiscales

**Types d'Impôts:**
- `TVA` : Taxe sur la Valeur Ajoutée
- `IR` : Impôt sur les Revenus (personnes physiques)
- `IS` : Impôt sur les Sociétés
- `TAXE_PROFESSIONNELLE` : Taxes professionnelles

**Statuts de Déclaration:**
- `BROUILLON` : En préparation
- `COMPLÉTÉE` : Prête pour dépôt
- `DÉPOSÉE` : Envoyée aux autorités
- `PAYÉE` : Paiement effectué

**Endpoints Clés:**
```
POST   /api/entreprises/{id}/declarations-fiscales           → Créer une déclaration
GET    /api/entreprises/{id}/declarations-fiscales           → Lister les déclarations
PUT    /api/entreprises/{id}/declarations-fiscales/{id}      → Modifier une déclaration
POST   /api/entreprises/{id}/declarations-fiscales/{id}/marquer-deposee  → Marquer comme déposée
POST   /api/entreprises/{id}/declarations-fiscales/{id}/marquer-payee    → Marquer comme payée
POST   /api/entreprises/{id}/declarations-fiscales/{id}/generer-echeancier → Générer écheancier de TVA
```

**Génération d'Écheancier:**
- Calcul automatique des échéances de TVA
- Génération des dates de paiement selon le régime fiscal

---

### 6️⃣ Module Dashboard (`dashboard/`)

**Responsabilités:**
- Affichage du tableau de bord personnalisé
- Synthèse financière par entreprise

**Endpoints Clés:**
```
GET    /api/entreprises/{id}/tableau-de-bord    → Tableau de bord complet
```

**Données du Tableau:**
- Résultat net (bénéfice/perte)
- Total actif/passif
- Soldes des comptes clés (Banque, Caisse)
- Dernières écritures
- Documents en attente de traitement

---

### 7️⃣ Module Demandes (`demande/`)

**Responsabilités:**
- Gestion des demandes de documents/informations
- Cycle de vie des demandes

**Statuts de Demande:**
- `CRÉÉE` : Demande initiale
- `EN_COURS` : En traitement
- `COMPLÉTÉE` : Prête
- `FERMÉE` : Terminée

---

### 8️⃣ Module Notifications (`notification/`)

**Responsabilités:**
- Notifications système
- Alertes et reminders

---

## 🤖 Couche Agentique IA (`agent/`)

> ⚠️ **Migration effectuée** : toute la logique IA (extraction VLM, analyse visuelle de fraude,
> rapprochement sémantique, Universal Matching Engine, agent tool-use, envoi de templates
> WhatsApp, réception du webhook Meta) vit dans un microservice FastAPI dédié — voir
> [`../ai-service/README.md`](../ai-service/README.md), en particulier sa section « Côté Spring
> Boot » pour la table de correspondance ancien code → nouveau code. Ce module Spring Boot
> n'appelle plus Anthropic/Groq/Ollama directement (dépendances Spring AI retirées du `pom.xml`) : il
> consomme `ai-service` via `agent/client/AiServiceClient.java` (API REST authentifiée par clé
> partagée). Les modules 9️⃣ à 1️⃣7️⃣ ci-dessous décrivent le *comportement* fonctionnel tel que
> perçu par l'utilisateur ; l'implémentation technique réelle des appels au modèle (prompts,
> parsing, garde-fous anti-hallucination) vit dans `ai-service/app/services/`.
> **Vérifié par compilation et exécution réelles** (JDK 26 + Maven, `mvn test`) le 2026-08-12 :
> `mvn compile`/`mvn test-compile` passent sans erreur, 209/210 tests passent — le seul échec
> (`ComptableApplicationTests.contextLoads`) est un aléa de démarrage Docker Compose (le port
> Postgres découvert dynamiquement n'est pas toujours disponible selon l'ordre d'exécution des
> classes de test), pas un défaut de code ; `ComptabilisationIntegrationTest`, qui exerce le
> même contexte Spring complet avec une vraie base Postgres, passe systématiquement.

Au-dessus du cœur comptable (modules 1 à 8), une couche d'agents IA (microservice FastAPI + Groq pour l'extraction/la fraude visuelle, Claude/Anthropic pour le rapprochement et le Copilot, LangGraph4j en Java pour l'orchestration du pipeline) automatise l'ingestion, la validation et la surveillance des pièces justificatives. Chaque brique est protégée par un **feature flag** désactivé par défaut : sans clé `GROQ_API_KEY`/`ANTHROPIC_API_KEY` (côté ai-service) et sans activation explicite, le comportement historique (upload web + OCR heuristique) reste inchangé à l'identique.

### 9️⃣ Extraction par Vision (VLM) — `agent/extraction/`

Remplace l'OCR heuristique par un modèle de vision (Groq, appelé depuis `ai-service` — plus de `ChatClient` Spring AI côté Java ; appelait Anthropic avant un changement de provider ultérieur, voir `ai-service/README.md` section « Provider vision de l'extraction et de la fraude ») capable de lire une photo de facture ou de reçu Mobile Money (Wave, Orange Money, MTN Money, Moov Money) prise au téléphone : montants HT/TVA/TTC, date, avec un score de confiance (0.0–1.0) et un motif explicatif. Une confiance nulle ou une image illisible dégrade proprement vers la saisie manuelle plutôt que d'inventer des valeurs.

```properties
agent.vlm.enabled=false   # true pour basculer HeuristiqueOcrExtractionService → AiServiceOcrExtractionService (agent/extraction/)
```

### 🔟 Ingestion WhatsApp — `agent/whatsapp/`

Un client peut envoyer une photo de pièce directement par WhatsApp. **Le webhook Meta (`GET/POST /api/v1/whatsapp/webhook`, vérification `X-Hub-Signature-256`) est désormais reçu par `ai-service` (FastAPI), pas par Spring Boot** — `WhatsAppWebhookController` a été supprimé lors du découplage (voir le bandeau d'avertissement en tête de section). `ai-service` télécharge le média (API Graph), extrait/analyse la pièce, puis pousse le résultat vers `POST /api/v1/internal/whatsapp/documents` côté Spring Boot (`WhatsAppInternalIngestionController`, authentifié par `X-Internal-Api-Key`), qui délègue à `WhatsAppIngestionService` — identification du client par numéro de téléphone, idempotence par `messageId`, puis ingestion via `DocumentService.ingererAvecExtractionExterne` (même modèle `Document` que l'upload web).

```
POST /api/v1/internal/whatsapp/documents   → (interne, ai-service → Spring Boot) Pièce WhatsApp déjà extraite/analysée
```

```properties
ai-service.api-key=          # secret partagé attendu sur X-Internal-Api-Key (appels entrants ai-service → Spring Boot)
ai-service.outbound-api-key= # secret envoyé par Spring Boot vers ai-service (appels sortants AiServiceClient)
ai-service.base-url=http://localhost:8000
```

### 1️⃣1️⃣ Pipeline Agentique (LangGraph4j) — `agent/pipeline/`

Une fois une pièce ingérée (WhatsApp), elle traverse un graphe d'états persistant (`extraction → conformité → analyse_fraude → [auto | attente_validation] → écriture`) :
- **Conformité** : règles déterministes (HT + TVA ≈ TTC à l'arrondi près, montants positifs, date présente).
- **Analyse de fraude** : relit le verdict déjà calculé à l'ingestion (cf. module 1️⃣4️⃣, pas de second appel IA).
- **Routage auto/manuel** : passage automatique si confiance VLM ≥ 95 % **et** conformité OK **et** score de fraude non CRITIQUE ; sinon la pièce se met en pause (checkpoint persisté en base, `JdbcCheckpointSaver`) au statut `EN_ATTENTE_VALIDATION_HUMAINE`, potentiellement plusieurs jours, jusqu'à validation par un comptable.
- **Écriture** : tente une comptabilisation automatique (échoue aujourd'hui systématiquement faute de tiers/compte déterminables sans intervention humaine — la pièce reste alors disponible pour une comptabilisation manuelle classique).

### 1️⃣2️⃣ Inbox de Validation — `agent/pipeline/web/`

Écran dédié pour le comptable : liste des pièces en pause (`EN_ATTENTE_VALIDATION_HUMAINE`), avec visualiseur du fichier original, champs extraits éditables (montants, date), score de confiance, et **badge de risque de fraude** (voir module 1️⃣4️⃣). Une validation (individuelle ou par lot) reprend le pipeline là où il s'était arrêté.

```
GET  /api/entreprises/{id}/documents-agent                          → Pièces en attente de validation
POST /api/entreprises/{id}/documents-agent/{docId}/valider-et-reprendre → Corriger et reprendre le pipeline
```

### 1️⃣3️⃣ CRM de Relance WhatsApp — `agent/relance/`

Détecte chaque jour (`@Scheduled`, cron configurable) les demandes de pièces (`demande/`) en retard — date limite dépassée, toujours en attente — et envoie automatiquement une relance WhatsApp au client via un template Meta pré-approuvé (obligatoire au-delà de la fenêtre de 24h de messagerie client). Anti-spam (une relance par demande par jour maximum), traçabilité complète (succès/échec, horodatage) et notification in-app du collaborateur à l'origine de la demande.

```
GET /api/entreprises/{id}/relances   → Historique des relances envoyées
```

```properties
relance.enabled=false           # false par defaut : sans template Meta approuve, chaque envoi echouerait
relance.cron=0 0 8 * * *        # tous les jours a 8h
whatsapp.phone-number-id=       # numero expediteur (ID Meta) des relances
```

### 1️⃣4️⃣ Rapprochement Sémantique Mobile Money — `agent/rapprochement/`

Filet de secours du rapprochement bancaire classique (module 3.H) : sur les lignes de relevé qu'un appariement par montant exact n'a **pas** su résoudre (fréquent avec le Mobile Money, où l'opérateur prélève des frais et où les libellés sont tronqués/codés), un modèle de langage compare en un seul appel structuré toutes les lignes non résolues aux écritures candidates restantes, muni d'un prompt expert du vocabulaire Mobile Money ouest-africain (Wave, Orange Money, MTN Money). Garde-fou strict : un appariement n'est appliqué automatiquement que si sa confiance dépasse 90 % **et** que les identifiants renvoyés sont bien ceux fournis en entrée (jamais de confiance aveugle dans la sortie du modèle) ; en dessous, la ligne reste à pointer manuellement comme avant.

```properties
agent.rapprochement-semantique.enabled=false
agent.rapprochement-semantique.seuil-confiance=0.90
```

### 1️⃣5️⃣ Agent Contrôleur de Fraude & Anomalies Fiscales — `agent/fraude/`

Analyse **chaque** pièce juste après son extraction (upload web *et* WhatsApp), avant toute validation :
- **Contrôles déterministes, toujours actifs, gratuits** : écart grossier entre la TVA déclarée et le taux standard UEMOA (18 %, avec détection simple des régimes exonérés) ; montant TTC parfaitement rond sur un achat/reçu (indice classique de facture de complaisance) ; doublon strict (même entreprise, même montant, date proche déjà enregistrée).
- **Analyse visuelle (optionnelle, VLM)** : recherche d'incohérences typographiques/graphiques typiques d'un reçu Mobile Money falsifié (police non uniforme, logo déformé, montant chiffres/lettres discordant, référence de transaction absente ou au format invalide).
- **Verdict structuré** : score `FAIBLE` / `MOYEN` / `CRITIQUE` + liste de motifs précis, persisté sur la pièce et exposé aux DTO (`scoreRisqueFraude`, `alertesFraude`) pour un badge rouge côté Inbox.
- **Garde-fou fonctionnel** : un score `CRITIQUE` bascule automatiquement la pièce en `EN_ATTENTE_VALIDATION_HUMAINE` — interdiction de comptabilisation/validation automatique, quel que soit le canal d'origine.

```properties
agent.fraude.analyse-visuelle.enabled=false   # seule l'analyse VLM est derriere un flag
agent.fraude.tva.taux-standard=0.18
agent.fraude.tva.tolerance-xof=200            # ecart tolere avant de lever une alerte TVA
agent.fraude.doublon.fenetre-jours=3
agent.fraude.montant-rond.multiple-xof=5000
```

### 1️⃣6️⃣ Agent Comptable Autonome — `agent/tools/` (côté `ai-service`)

Endpoint autonome distinct du reste de la couche agentique : au lieu d'un unique appel structuré, `ai-service` (`app/services/agent_orchestrator.py`) exécute une **vraie boucle multi-tours** (`POST /api/v1/agent/analyser-piece`, bornée à 6 itérations) où le modèle décide lui-même, tour après tour, d'appeler un outil avant de statuer. Trois outils appellent Spring Boot en retour, via `agent/tools/AgentToolsService.java` et `AgentToolsInternalController` (authentifié `X-Internal-Api-Key`, même principe que l'ingestion WhatsApp) :

```
GET  /api/v1/internal/agent-tools/doublons               → verifier_doublon_montant (réutilise AnalyseFraudeService.verifierDoublon)
GET  /api/v1/internal/agent-tools/historique-fournisseur  → consulter_historique_fournisseur (requête JSONB native sur Document.donneesOcr)
POST /api/v1/internal/agent-tools/demande-clarification   → demander_clarification_client (réutilise DemandePieceService.creerDepuisAgent)
```

**Non câblé sur les flux d'ingestion existants** : `DocumentService.uploader`/`ingererAvecExtractionExterne` continuent d'utiliser le duo classique extraction + fraude (modules 9️⃣/1️⃣5️⃣) sans passer par cet agent — décision produit distincte, pas encore prise. Détail complet côté `ai-service/README.md` (section « Agent Comptable Autonome »).

### 1️⃣7️⃣ Universal Matching Engine — `agent/matching/`

Moteur de rapprochement multi-source générique (mission dédiée, distincte du rapprochement sémantique Mobile Money du module 1️⃣4️⃣ — les deux coexistent) : contrairement à ce dernier qui ne compare que relevé ↔ écritures déjà comptabilisées, ce moteur compare une ligne de relevé non résolue à **des candidats de plusieurs types** (écritures, factures clients, pièces fournisseur/documents), en s'appuyant sur `ai-service` (`POST /api/v1/matching/analyze`, voir `ai-service/README.md`).

- **`MatchingContextBuilder`** construit la requête : recherche bornée (fenêtre de dates progressive 7j → 30j, jamais « toutes les factures »), branchée par sens du montant (positif → factures clients `VALIDEE` ; négatif → pièces fournisseur `Document` `FACTURE_ACHAT`, cf. absence d'entité facture-fournisseur dédiée).
- **`MatchDecisionEngine`** (« Java Control ») ne fait jamais confiance à la sortie brute d'ai-service : revalide indépendamment que le candidat proposé existe bien parmi ceux envoyés, appartient à l'entreprise, est encore disponible, et que le montant est cohérent (±20 %), avant de statuer `MATCH_AUTO` / `MATCH_REVIEW` / `NO_MATCH` / `AI_UNAVAILABLE`. **`MATCH_AUTO` (pointage automatique réel) n'est appliqué que pour un candidat de type écriture comptable** — jamais pour une facture ou un document, qui restent toujours une simple proposition à valider.
- **`AiMatchProposal`** (table `ai_match_proposal`, migration V22) trace chaque proposition (`PROPOSED`/`ACCEPTED`/`REJECTED`/`EXPIRED`) pour audit, y compris les `NO_MATCH`/`AI_UNAVAILABLE`.
- **Écran de rapprochement (Angular)** : badge « 🤖 Suggestion IA X % » avec boutons Accepter/Rejeter/Voir détails sur les lignes non pointées ayant une proposition `MATCH_REVIEW`.

```
GET  /api/entreprises/{id}/rapprochements/{relId}/lignes/{ligneId}/proposition-ia   → Proposition IA pour une ligne
POST /api/entreprises/{id}/rapprochements/propositions-ia/{propId}/accepter         → Valider (pointage réel si écriture comptable uniquement)
POST /api/entreprises/{id}/rapprochements/propositions-ia/{propId}/rejeter          → Rejeter
```

```properties
agent.matching-ia.enabled=false                    # false par defaut : filet IA desactive, comportement identique a avant cette mission
agent.matching.auto-match-threshold=0.95
agent.matching.review-threshold=0.70
```

> Côté `ai-service`, `AI_PROVIDER=mock` (valeur par défaut) fait tourner tout ce pipeline sans clé Anthropic — voir `ai-service/README.md`, section « Provider LLM du Universal Matching Engine ».

---

### 1️⃣8️⃣ Core Intelligent — `dossier/`, `agent/finding/`, `agent/comptabilisation/`

Couche de synthèse qui répond à « qu'est-ce qui est correct / qu'est-ce qui manque / qu'est-ce qui est anormal / que dois-je faire maintenant » sans reconstruire les moteurs existants (Universal Matching Engine, extraction, analyse de fraude) — elle les **interprète**. Loi d'architecture : l'IA propose (déjà fait en amont par les modules 1️⃣5️⃣/1️⃣7️⃣), Java vérifie et décide, PostgreSQL persiste.

- **`Finding`** (table `finding`, migration V23) : modèle central unique d'exception/anomalie — polymorphe (`sourceType`+`sourceId`), sévérité `CRITICAL`/`IMPORTANT`/`REVIEW`, preuve JSON, action recommandée, statut `OPEN`/`RESOLVED`/`DISMISSED`. Remplace les deux concepts « WorkItem » et « Finding » envisagés séparément — un seul modèle peuplé par deux moteurs :
  - **`MissingEvidenceAnalyzer`** (événementiel, branché juste après `MatchDecisionEngine.statuer` dans `RapprochementBancaireService`) : classe chaque décision de rapprochement non automatique en `MISSING_EVIDENCE` (aucun candidat) ou `MATCH_AMBIGUOUS` (candidats à trancher).
  - **`ControlEngine`** (relu à l'ouverture du dossier, pas de scheduler) : déclarations TVA manquantes/en retard (`TAX_REVIEW`), documents à risque de fraude non résolus (`DOCUMENT_REVIEW`), écritures BROUILLON oubliées >30j (`ECRITURE_A_CONTROLER`). Ignore les périodes TVA antérieures à l'entrée du dossier dans le cabinet (`entreprise.createdAt`) pour ne pas signaler une négligence qui n'en est pas une.
- **`DossierComptableService`** (`GET /api/entreprises/{id}/dossier`) : calcule à la lecture (jamais persisté — le score doit rester déterministe et toujours à jour) un score global + 7 scores par domaine (Banque/Documents/Clients/Fournisseurs/Justificatifs/Fiscalité/Clôture, pénalités 25/10/3 pts par Finding CRITICAL/IMPORTANT/REVIEW), un statut `PRET`/`A_SURVEILLER`/`BLOQUE`, et la liste des actions prioritaires triées par sévérité.
- **`AccountingProposal`** (table `accounting_proposal`, migration V24) + **`AccountingProposalBuilder`** : débloque `ComptabilisationService.comptabiliserAutomatiquement` (stub qui échouait systématiquement avant cette mission) via une résolution 100 % Java, sans appel IA supplémentaire — tiers par normalisation/sous-chaîne (même technique que `MatchingContextBuilder`), compte par fréquence historique (`LigneEcritureRepository`, agrégée en Java). Niveaux 0–3 : seul le niveau 3 (tiers + compte + journal résolus, confiance ≥ 0.9, ≥ 3 occurrences historiques cohérentes) déclenche une comptabilisation réelle depuis `agent/pipeline/EcritureNode` ; en dessous, la proposition reste une suggestion à valider (`POST .../propositions/{id}/accepter` ne fait que pré-sélectionner, jamais comptabiliser seul).
- **Boucle de résolution WhatsApp** (`WhatsAppResolutionService`) : un `Finding` de type `MISSING_EVIDENCE` sans aucun candidat déclenche automatiquement une `DemandePiece` ciblée (libellé précis : montant/date/libellé, pas un template générique), liée via `demande_piece.finding_id` (migration V25). Les cas ambigus (candidats existants mais non tranchés) restent en revue interne — pas de sollicitation client hors contrôle.
- **`ClotureReadinessService`** (`GET /api/entreprises/{id}/cloture-readiness`) : avis `READY`/`READY_WITH_REVIEW`/`NOT_READY`, restructuré (mission cloture Étape 4) en une **checklist** explicite (`ControlItem[]` : `code`/`libelle`/`statut` OK|WARNING|BLOCKING|NOT_APPLICABLE/`severite`/`message`/`evidence`) couvrant exercice ouvert, écritures BROUILLON, régularisations non finalisées, Findings ouverts, détermination du résultat — plusieurs items (banque/caisse/rapprochement/lettrage/fournisseurs/clients/stocks/immobilisations/amortissements/fiscalité) restent honnêtement `NOT_APPLICABLE` (aucune logique de contrôle approfondie construite pour ces points). Purement consultatif en tant que tel, mais **`ClotureExerciceService.cloturer` consulte désormais cette même checklist et refuse (422) si un item est `BLOCKING`** — voir module 3️⃣.K/M.
- **UI Angular** : le tableau de bord existant (`tableau-de-bord.component.*`) est étendu (pas de nouvel écran redondant) avec le score du dossier, les 7 scores par domaine, un bandeau clôture, et l'inbox des actions prioritaires (Résoudre/Rejeter/Voir détails avec preuve JSON dépliable).

```
GET  /api/entreprises/{id}/dossier                                          → Score global + 7 domaines + actions prioritaires
GET  /api/entreprises/{id}/findings                                         → Findings ouverts, triés par sévérité
POST /api/entreprises/{id}/findings/{findingId}/resoudre                    → Résolution manuelle
POST /api/entreprises/{id}/findings/{findingId}/rejeter                     → Rejet manuel
GET  /api/entreprises/{id}/comptabilisation/documents/{docId}/proposition   → Proposition de comptabilisation pour un document
POST /api/entreprises/{id}/comptabilisation/propositions/{id}/accepter      → Présélectionner (jamais comptabiliser seul)
POST /api/entreprises/{id}/comptabilisation/propositions/{id}/rejeter       → Rejeter
GET  /api/entreprises/{id}/cloture-readiness                                → Avis de préparation à la clôture
```

```properties
agent.accounting-proposal.enabled=false   # false par defaut : AccountingProposalBuilder inactif, comportement identique a avant cette mission
```

> Vérifié de bout en bout par `dossier/P0CoreIntelligentE2ETest.java` (Postgres réel, seul `AiServiceClient` mocké) : import relevé → ligne non expliquée → `Finding MISSING_EVIDENCE` + `DemandePiece` ciblée réels en base → dossier dégradé (score Banque < 100) → preuve fournie (écriture réelle créée) → rematching réel → `MATCH_AUTO` → Finding résolu → dossier réparé (score Banque = 100).

---

### 1️⃣9️⃣ Cabinet Operating System + Financial Intelligence — `cabinet/`, `copilot/`, `finance/`, `recouvrement/`, `fiscalite/conformite/`

Couche de pilotage au-dessus du P0 (mission P1) : le P0 répond « comment rendre cette
comptabilité propre » pour **une** entreprise, le P1 répond « comment piloter tout mon cabinet
et comprendre la situation financière de mes clients ». Reutilise integralement les moteurs P0
(`Finding`, `DossierComptableService`, `BalanceAgeeService`, `soldeTresorerie`...) — une seule
vraie extension de schéma (`Finding.assignedTo`/`dueDate`) et une table `tax_rule` volontairement
vide.

- **Cabinet Cockpit** (`CabinetCockpitService`) : vue portefeuille cross-entreprises pour
  EXPERT_COMPTABLE/COLLABORATEUR — clients PRET/A_SURVEILLER/BLOQUE, top risques (findings les
  plus graves du cabinet), une seule requête `Finding` batchée pour tout le portefeuille (pas
  une requête par client affiché).
- **Work Queue unifiée** (`InboxService`) : `Finding` + `DemandePiece` dans une seule liste
  triée par sévérité, sans nouvelle table — assignation à un collaborateur
  (`Finding.assignedTo`/`dueDate`), charge de travail par collaborateur.
- **AI Copilot Evidence-First** (`CopilotService`/`CabinetCopilotService`) : jeu fermé de
  questions structurées (8 niveau comptable, 5 niveau cabinet, `IntentClassifier`/
  `CabinetIntentClassifier`, 100% déterministe), jamais de chat libre. Pipeline obligatoire :
  classification Java → collecte de faits Java (réutilise les services existants) → un seul
  appel LLM pour la mise en mots (`copilot_service.py`, jamais pour calculer) → réponse
  structurée `{answer, confidence, evidence[], recommendedActions[]}`. RBAC vérifié **avant**
  la classification, jamais après.
- **Financial Intelligence** (`finance/`) : `FinancialSnapshotService` (cash/receivables/
  payables/revenue/expenses/profit/taxes + comparaisons période), `CashPositionService`
  (trésorerie par compte/canal, canal dérivé par heuristique faute d'intégration Mobile Money
  réelle), `CashForecastService` (paliers KNOWN/EXPECTED sur factures réelles, jamais de
  palier PREDICTED fabriqué).
- **Recouvrement** (`recouvrement/CollectionRiskService`) : LOW/MEDIUM/HIGH par client à partir
  de `BalanceAgeeService`, action recommandée en texte, **jamais d'envoi automatique**.
- **Compliance Center** (`fiscalite/conformite/`) : obligations fiscales + anomalies
  `TAX_REVIEW` agrégées, Tax Health = projection du score de domaine Fiscalité du Dossier P0.
  `TaxRule` (schéma versionné pays/régime, table `tax_rule`) volontairement vide — aucune règle
  réelle sans source vérifiée.
- **Cabinet Analytics** (`CabinetAnalyticsService`) : dossiers à jour/en retard, temps moyen de
  résolution, taux de demandes résolues — KPIs réellement calculés, pas des compteurs
  décoratifs.
- **UI Angular** : nouvel écran `/cabinet` (guardé, EXPERT_COMPTABLE/COLLABORATEUR uniquement),
  widget Copilot réutilisable (`app-copilot-widget`) embarqué au niveau comptable **et**
  cabinet.

```
GET  /api/cabinet/cockpit
GET  /api/cabinet/inbox?assigneAMoi=
GET  /api/cabinet/charge-collaborateurs
GET  /api/cabinet/analytics
POST /api/cabinet/copilot/question
POST /api/entreprises/{id}/findings/{findingId}/assigner
GET  /api/entreprises/{id}/tresorerie/position
GET  /api/entreprises/{id}/tresorerie/prevision
GET  /api/entreprises/{id}/financial-snapshot
GET  /api/entreprises/{id}/financial-snapshot/comparaison
GET  /api/entreprises/{id}/recouvrement/risques
GET  /api/entreprises/{id}/conformite
GET  /api/entreprises/{id}/conformite/tax-health
GET  /api/fiscalite/tax-rules?pays=
POST /api/entreprises/{id}/copilot/question
GET  /api/cabinet/collaborateurs
POST /api/entreprises/{id}/copilot-dirigeant/question
```

> Vérifié de bout en bout par `cabinet/P1CabinetOperatingSystemE2ETest.java` (Postgres réel,
> seul `AiServiceClient` mocké, uniquement pour la mise en mots du Copilot) : 2 entreprises
> réelles dans un cabinet → Cockpit agrège correctement → Copilot cabinet et comptable
> répondent avec preuves réelles → trésorerie/prévision/recouvrement/conformité calculés sur
> écritures et factures réelles. Détails complets : `docs/P1_RAPPORT_FINAL.md`.

**Mission P1.5** (« Unified Accounting Workspace ») : les endpoints Trésorerie/Recouvrement/
Conformité ci-dessus étaient déjà complets côté Java depuis P1 mais **jamais appelés depuis
Angular** — P1.5 a construit les écrans manquants (`frontend/src/app/features/finance`,
`recouvrement`, `conformite`) sans toucher au backend. Ajouts réels : `GET
/api/cabinet/collaborateurs` (sélecteur d'assignation d'un Finding) et `POST
/api/entreprises/{id}/copilot-dirigeant/question` (Copilot simplifié pour le rôle CLIENT, même
moteur que le Copilot comptable avec un sous-ensemble d'intentions non-techniques). Premier test
HTTP réel du projet (MockMvc + vrais jetons JWT + vraie chaîne de sécurité) :
`security/P1_5SecuriteHttpE2ETest.java`. Détails complets : `docs/P1_5_RAPPORT_FINAL.md`.

### 2️⃣0️⃣ Product Telemetry — `telemetry/`, `copilot/CopilotQuery` (mission P1.9)

Répond à 4 questions avec des données réelles plutôt qu'un décompte de fonctionnalités : ce que
le produit automatise vraiment, combien de travail humain il retire, quelles capacités IA sont
fiables en pratique, quoi construire en P2. **Aucune nouvelle table d'événements génériques** —
réutilise entièrement les entités P0/P1/P1.5 existantes (voir `docs/P1_9_AUDIT.md` section G) ;
une seule table légère append-only (`copilot_query`) et 3 colonnes nullables
(`rejection_reason` sur `finding`/`ai_match_proposal`/`accounting_proposal`).

- **`ProductKpiService`** (`telemetry/`) : Human Touch Rate (part des propositions IA
  auto-appliquées vs revues/corrigées par un humain, jamais fusionné entre rapprochement
  bancaire et comptabilisation — deux domaines distincts), Matching Quality (acceptation/rejet
  par domaine), Evidence Resolution (taux et durée de résolution des `DemandePiece`), Finding
  Resolution Time (moyenne/médiane/P90), Copilot Usage/Quality (feedback 👍/👎, taux de réponse
  avec preuve, fenêtre glissante 30 jours), Work Queue (backlog/retard). **Ne calcule jamais un
  pourcentage sur un échantillon vide** — retourne `null` plutôt qu'un 0% trompeur
  (`ProductKpiServiceTest.portefeuille_vide_ne_produit_aucun_pourcentage_invente`).
- **`CopilotQuery`** (migration V29) : trace chaque question Copilot (portée, intention,
  confiance, nombre de preuves, latence, fallback, feedback + motif optionnel) — écrite via
  `CopilotTelemetryRecorder` en transaction séparée (`REQUIRES_NEW`), ne fait jamais échouer une
  réponse Copilot si l'écriture télémétrique échoue.
- **Feedback utilisateur** : 👍/👎 sur chaque réponse Copilot (`POST
  /api/copilot/queries/{queryId}/feedback`) ; motif de rejet optionnel sur `Finding`/
  `AiMatchProposal`/`AccountingProposal` (`rejection_reason`, ex. `wrong_candidate`/
  `wrong_amount`/`wrong_supplier`/`too_vague`) — jamais exploité pour ré-entraîner un modèle
  (aucun fine-tuning dans ce projet), seulement pour la qualité produit.
- **Tableau de bord Qualité** (Angular, `/cabinet/indicateurs`) : lecture seule, réservé
  cabinet — chaque taux affiche son échantillon `N=` à côté, jamais un pourcentage nu (même
  discipline que la trésorerie P1.5). État vide explicite par carte plutôt qu'un 0%.
- **Télémétrie IA côté `ai-service`** : latence + succès/échec par opération, journalisée en
  `logging` structuré (`ai_call provider=... operation=... success=... latency_ms=...`), jamais
  le prompt ni le document. Couvre `groq_client.call_structured` (extraction, fraude visuelle —
  `provider=groq`), `anthropic_client.call_structured` (`AnthropicLLMProvider` — matching/copilot
  en mode réel, `provider=anthropic`) et `MockLLMProvider.call_structured` (matching/copilot en
  mode `AI_PROVIDER=mock`). Pas de nouvelle dépendance (pas de Prometheus/OpenTelemetry) — voir
  `ai-service/README.md`.

```
GET  /api/cabinet/kpi
POST /api/copilot/queries/{queryId}/feedback
```

**Gaps assumés, documentés plutôt que masqués** (voir `docs/P1_9_AUDIT.md` section F et
`docs/P1_9_PILOT_PROTOCOL.md`) : Time to Closing (aucun horodatage de clôture persisté), Cash
Forecast Quality (aucun historique réel disponible), False Auto-Match Rate (non mesurable avec
le modèle de données actuel).

---

## 🗄️ Modèle de Données (Base de Données)

### 1. Schéma d'Identité
```sql
TABLE tenant
  ├── id (UUID)
  ├── raison_sociale (VARCHAR)
  ├── slug (VARCHAR UNIQUE)
  ├── pays (VARCHAR)
  ├── plan_abonnement (VARCHAR)
  └── created_at (TIMESTAMPTZ)

TABLE utilisateur
  ├── id (UUID)
  ├── tenant_id (FK → tenant)
  ├── email (VARCHAR UNIQUE)
  ├── mot_de_passe (VARCHAR hashed)
  ├── nom, prenom (VARCHAR)
  ├── telephone (VARCHAR)
  └── created_at (TIMESTAMPTZ)

TABLE role
  ├── id (SERIAL)
  ├── code (VARCHAR UNIQUE)
  └── libelle (VARCHAR)

TABLE utilisateur_role (M:M)
  ├── utilisateur_id (FK)
  └── role_id (FK)
```

### 2. Schéma Entreprise
```sql
TABLE entreprise
  ├── id (UUID)
  ├── tenant_id (FK → tenant)
  ├── raison_sociale (VARCHAR)
  ├── numero_contribuable (VARCHAR)
  ├── rccm (VARCHAR)
  ├── forme_juridique (VARCHAR)
  ├── regime_fiscal (VARCHAR)
  ├── secteur_activite (VARCHAR)
  ├── pays (VARCHAR)
  ├── devise (VARCHAR) -- XOF par défaut
  └── created_at (TIMESTAMPTZ)

TABLE utilisateur_entreprise (M:M)
  ├── utilisateur_id (FK → utilisateur)
  └── entreprise_id (FK → entreprise)
```

### 3. Schéma Comptabilité
```sql
TABLE exercice_comptable
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── date_debut (DATE)
  ├── date_fin (DATE)
  └── statut (VARCHAR) -- OUVERT, CLOTURE

TABLE journal
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── code (VARCHAR) -- VT, AC, BQ, CS, OD
  ├── libelle (VARCHAR)
  └── type (VARCHAR)

TABLE plan_comptable_reference (GLOBAL - non tenant)
  ├── id (SERIAL)
  ├── numero (VARCHAR UNIQUE) -- 101, 211, 401, etc.
  ├── intitule (VARCHAR)
  ├── classe (SMALLINT) -- 1-8
  └── nature (VARCHAR) -- ACTIF, PASSIF, CHARGE, PRODUIT

TABLE compte_comptable
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── reference_id (FK → plan_comptable_reference)
  ├── numero (VARCHAR)
  ├── intitule (VARCHAR)
  └── lettrable (BOOLEAN)

TABLE ecriture_comptable
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── exercice_id (FK)
  ├── journal_id (FK)
  ├── numero_piece (VARCHAR) -- FAC-001, CHQ-050
  ├── date_ecriture (DATE)
  ├── libelle (VARCHAR)
  ├── statut (VARCHAR) -- BROUILLON, VALIDÉE
  ├── created_by (FK → utilisateur)
  └── created_at (TIMESTAMPTZ)

TABLE ligne_ecriture
  ├── id (UUID)
  ├── ecriture_id (FK → ecriture_comptable)
  ├── compte_id (FK → compte_comptable)
  ├── tiers_id (FK → tiers, optional)
  ├── libelle (VARCHAR)
  ├── debit (NUMERIC)
  ├── credit (NUMERIC)
  └── lettrage (VARCHAR, optional) -- code A, B, ... Z, AA, ... affecte par le lettrage

TABLE tiers
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── type (VARCHAR) -- CLIENT, FOURNISSEUR, EMPLOYEE, etc.
  ├── raison_sociale (VARCHAR)
  ├── numero_compte (VARCHAR)
  ├── adresse (VARCHAR)
  └── ville (VARCHAR)
```

### 3bis. Schéma Rapprochement Bancaire, Immobilisations & Facturation
```sql
TABLE releve_bancaire
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── compte_id (FK → compte_comptable)
  ├── libelle (VARCHAR)
  ├── date_debut, date_fin (DATE)
  └── solde_debut, solde_fin (NUMERIC)

TABLE ligne_releve
  ├── id (UUID)
  ├── releve_id (FK → releve_bancaire)
  ├── date_operation (DATE)
  ├── libelle (VARCHAR)
  ├── montant (NUMERIC) -- signe : positif = entree, negatif = sortie
  └── ligne_ecriture_id (UUID, optional) -- renseigne quand la ligne est pointee

TABLE immobilisation
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── compte_id, compte_amortissement_id (FK → compte_comptable)
  ├── libelle (VARCHAR)
  ├── date_acquisition (DATE)
  ├── valeur_origine (NUMERIC)
  ├── duree_annees (INTEGER)
  ├── mode (VARCHAR) -- LINEAIRE, DEGRESSIF
  └── statut (VARCHAR) -- EN_SERVICE, CEDEE

TABLE dotation_amortissement
  ├── id (UUID)
  ├── immobilisation_id (FK → immobilisation)
  ├── annee (INTEGER)
  ├── montant (NUMERIC)
  └── ligne_ecriture_id (UUID) -- ligne credit de l'ecriture de dotation generee

TABLE facture
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── tiers_id (FK → tiers)
  ├── type (VARCHAR) -- DEVIS, FACTURE
  ├── numero (VARCHAR) -- DEV-2026-0001, FAC-2026-0001
  ├── date_emission, date_echeance (DATE)
  ├── objet (VARCHAR)
  ├── statut (VARCHAR) -- BROUILLON, ENVOYE, ACCEPTE, REFUSE, CONVERTI, VALIDEE, PAYEE
  ├── devis_origine_id (UUID, optional) -- renseigne sur la facture issue d'une conversion
  └── ecriture_id (UUID, optional) -- renseigne une fois la facture validee

TABLE ligne_facture
  ├── id (UUID)
  ├── facture_id (FK → facture)
  ├── designation (VARCHAR)
  ├── quantite, prix_unitaire (NUMERIC)
  └── taux_tva (NUMERIC)
```

### 4. Schéma Documents
```sql
TABLE document
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── type_document (VARCHAR) -- FACTURE, BULLETIN_PAIE, etc.
  ├── canal_source (VARCHAR) -- EMAIL, UPLOAD, API, NUMÉRISATION
  ├── statut (VARCHAR) -- REÇU, EN_TRAITEMENT, TRAITÉ, COMPTABILISÉ, ARCHIVÉ
  ├── nom_fichier (VARCHAR)
  ├── chemin_stockage (VARCHAR)
  ├── taille_octets (BIGINT)
  ├── ocr_data (JSON) -- Données extraites
  ├── created_at (TIMESTAMPTZ)
  └── created_by (FK → utilisateur)
```

### 5. Schéma Fiscalité
```sql
TABLE declaration_fiscale
  ├── id (UUID)
  ├── entreprise_id (FK)
  ├── type_impot (VARCHAR) -- TVA, IR, IS, TAXE_PROFESSIONNELLE
  ├── periode_debut (DATE)
  ├── periode_fin (DATE)
  ├── montant_base (NUMERIC)
  ├── montant_impot (NUMERIC)
  ├── statut (VARCHAR) -- BROUILLON, COMPLÉTÉE, DÉPOSÉE, PAYÉE
  ├── date_depot (DATE, optional)
  ├── date_paiement (DATE, optional)
  └── created_at (TIMESTAMPTZ)
```

---

## 🔐 Credentials de Test

### ⚠️ Important: Les Utilisateurs ne sont PAS Pré-créés

**Le projet utilise un système d'enregistrement dynamique.** Les utilisateurs doivent d'abord s'enregistrer via l'API. Les données de base (cabinets, entreprises, journaux) sont pré-chargées par la migration `V8__seed_test_data.sql`.

### 📝 Étapes pour Créer des Comptes de Test

#### Étape 1️⃣ : Enregistrement du Cabinet

**Endpoint:** `POST /api/auth/register-cabinet`

**Request:**
```json
{
  "email": "cabinet@example.com",
  "motDePasse": "Cabinet123!@#",
  "nom": "Dupont",
  "prenom": "Jean",
  "telephone": "+225 01 23 45 67 89",
  "raisonSocialeCabinet": "Cabinet Conseil & Associés",
  "pays": "CI"
}
```

**Response (exemple):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "uuid-utilisateur",
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "email": "cabinet@example.com",
  "roles": ["EXPERT_COMPTABLE"]
}
```

#### Étape 2️⃣ : Connexion

**Endpoint:** `POST /api/auth/login`

**Request:**
```json
{
  "email": "cabinet@example.com",
  "motDePasse": "Cabinet123!@#"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "uuid-utilisateur",
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "email": "cabinet@example.com",
  "roles": ["EXPERT_COMPTABLE"]
}
```

---

### 🎯 Comptes de Test Recommandés à Créer

#### Compte 1: Expert-Comptable (Chef de Cabinet)
```
Email:             cabinet@example.com
Mot de passe:      Cabinet123!@#
Nom/Prénom:        Dupont / Jean
Téléphone:         +225 01 23 45 67 89
Cabinet:           Cabinet Conseil & Associés
Rôle:              EXPERT_COMPTABLE (créé automatiquement)
Permissions:       Gestion complète du cabinet et de ses clients
```

**Utilisation:** Pour tester toutes les fonctionnalités (comptabilité, fiscalité, gestion d'entreprises)

#### Compte 2: Collaborateur
```
Email:             collaborateur@example.com
Mot de passe:      Collab123!@#
Nom/Prénom:        Martin / Sophie
Téléphone:         +225 01 98 76 54 32
Cabinet:           Cabinet Conseil & Associés
Rôle:              COLLABORATEUR (à assigner manuellement)
Permissions:       Assistance à la comptabilité
```

**Utilisation:** Pour tester les permissions restreintes

#### Compte 3: Expert-Comptable (Cabinet 2)
```
Email:             expert@solutions.ci
Mot de passe:      Expert123!@#
Nom/Prénom:        Bernard / Yves
Téléphone:         +225 05 06 07 08 09
Cabinet:           Expert Comptable Solutions
Rôle:              EXPERT_COMPTABLE
```

**Utilisation:** Pour tester l'isolation multi-tenant

---

### 🏢 Entreprises Pré-chargées (via Migration V8)

Les entreprises suivantes sont déjà créées et prêtes à l'usage:

#### Entreprise 1: ACME Corporation
```
Raison Sociale:        ACME Corporation
Numéro Contribuable:   CI-123456789
RCCM:                  CI/CC/2024/12345
Forme Juridique:       SARL
Régime Fiscal:         Régime Normal
Secteur d'Activité:    Commerce & Services
Pays:                  CI (Côte d'Ivoire)
Devise:                XOF (Franc CFA)
Cabinet Associé:       Cabinet Conseil & Associés
```

#### Entreprise 2: TechStart SARL
```
Raison Sociale:        TechStart SARL
Numéro Contribuable:   CI-987654321
RCCM:                  CI/CC/2024/54321
Forme Juridique:       SARL
Régime Fiscal:         Régime Simplifié
Secteur d'Activité:    Informatique & Télécommunications
Pays:                  CI (Côte d'Ivoire)
Devise:                XOF (Franc CFA)
Cabinet Associé:       Cabinet Conseil & Associés
```

#### Entreprise 3: Pharma Plus
```
Raison Sociale:        Pharma Plus
Numéro Contribuable:   CI-555666777
RCCM:                  CI/CC/2024/66666
Forme Juridique:       SA
Régime Fiscal:         Régime Normal
Secteur d'Activité:    Pharmacie & Santé
Pays:                  CI (Côte d'Ivoire)
Devise:                XOF (Franc CFA)
Cabinet Associé:       Expert Comptable Solutions
```

---

### 📤 Types de Documents Supportés

Vous pouvez uploader les types de documents suivants :

```
Type              │ Extension   │ Taille Max │ Description
FACTURE           │ PDF, JPG    │ 10 MB      │ Factures de vente/achat
BULLETIN_PAIE     │ PDF         │ 10 MB      │ Bulletins de salaire
RELEVÉ_BANCAIRE   │ PDF, Excel  │ 10 MB      │ Relevés bancaires
DEVIS             │ PDF         │ 10 MB      │ Devis et commandes
AUTRE             │ Tous        │ 10 MB      │ Autres documents
```

### 🔢 Plan Comptable Standard (Extrait)

Les comptes suivants sont pré-chargés (Plan Comptable SYSCOHADA Révisé) :

```
CLASSE 1 - COMPTES DE RESSOURCES DURABLES
  101   │ Capital social
  106   │ Réserves
  120   │ Résultat net de l'exercice (bénéfice)
  161   │ Emprunts auprès des établissements de crédit

CLASSE 2 - ACTIF IMMOBILISÉ
  211   │ Terrains
  231   │ Bâtiments
  244   │ Matériel et mobilier
  281   │ Amortissements

CLASSE 3 - STOCKS
  311   │ Marchandises
  355   │ Produits finis

CLASSE 4 - TIERS
  401   │ Fournisseurs
  411   │ Clients
  421   │ Personnel, avances
  4431  │ État, TVA facturée
  4452  │ État, TVA récupérable

CLASSE 5 - TRÉSORERIE
  521   │ Banques
  571   │ Caisse

CLASSE 6 - CHARGES
  601   │ Achats de marchandises
  641   │ Charges de personnel
  681   │ Dotations aux amortissements

CLASSE 7 - PRODUITS
  701   │ Ventes de marchandises
  706   │ Services vendus
```

---

## 🚀 Démarrage du Projet

### Prérequis

- **Java 26+** : `java -version`
- **Maven 3.8+** : `mvn -version`
- **Node.js 18+** : `node -version`
- **npm 9+** : `npm -version`
- **Docker & Docker Compose** : `docker --version`
- **PostgreSQL 15+** (via Docker)

### Installation et Démarrage

#### 1️⃣ Cloner le Repository
```bash
git clone https://github.com/yourusername/comptable.git
cd comptable
```

#### 2️⃣ Démarrer la Base de Données PostgreSQL
```bash
# Via Docker Compose
docker-compose up -d postgres

# Vérifier la connexion
# Host: localhost:5432
# Database: mydatabase
# User: myuser
# Password: secret
```

#### 3️⃣ Construire et Démarrer le Backend (Spring Boot)
```bash
# Depuis la racine du projet
mvn clean install
mvn spring-boot:run

# Le serveur démarre sur http://localhost:8080
# Accès aux logs sur http://localhost:8080/actuator
```

#### 4️⃣ Démarrer le Frontend (Angular)
```bash
cd frontend
npm install
npm start

# L'application démarre sur http://localhost:4200
```

#### 5️⃣ Premiers Pas
```
1. Ouvrir http://localhost:4200 dans le navigateur
2. Naviguer vers la page d'enregistrement (/register)
3. Créer un nouveau cabinet avec les informations :
   - Email: votreemail@example.com
   - Mot de passe: VotreMotDePasse123!@#
   - Nom/Prénom: Votre Nom
   - Raison sociale du cabinet: Votre Cabinet
   - Pays: CI (Côte d'Ivoire)
4. Confirmer l'enregistrement
5. Se connecter avec les identifiants créés
6. Créer une première entreprise cliente
7. Commencer à gérer la comptabilité
```

---

## 🌐 Endpoints API Principaux

### Authentication
```
POST   /api/auth/login              Login utilisateur
POST   /api/auth/register-cabinet   Enregistrement cabinet
```

### Entreprises
```
GET    /api/entreprises                Get liste des entreprises
POST   /api/entreprises                Créer entreprise
GET    /api/entreprises/{id}           Get détails entreprise
PUT    /api/entreprises/{id}           Update entreprise
POST   /api/entreprises/{id}/inviter   Inviter un collaborateur ou un client
```

### Comptabilité
```
GET    /api/entreprises/{id}/ecritures                    Lister écritures
POST   /api/entreprises/{id}/ecritures                    Créer écriture
GET    /api/entreprises/{id}/ecritures/{ecritureId}       Get écriture
DELETE /api/entreprises/{id}/ecritures/{ecritureId}       Supprimer écriture

POST   /api/entreprises/{id}/documents/{docId}/comptabiliser   Comptabiliser doc

GET    /api/entreprises/{id}/balance                      Balance générale
GET    /api/entreprises/{id}/grand-livre                  Grand livre
GET    /api/entreprises/{id}/journal-operations           Journal des ops

POST   /api/entreprises/{id}/exercices/{exId}/cloturer                   Clôturer un exercice
GET    /api/entreprises/{id}/etats-financiers/bilan?exerciceId=..        Bilan SYSCOHADA
GET    /api/entreprises/{id}/etats-financiers/compte-resultat?exerciceId=.. Compte de résultat

POST   /api/entreprises/{id}/lettrage                                    Lettrer un groupe de lignes
DELETE /api/entreprises/{id}/lettrage?compteId=..&code=..                Délettrer un code

POST   /api/entreprises/{id}/rapprochements                              Importer un relevé bancaire (CSV)
GET    /api/entreprises/{id}/rapprochements                              Lister les relevés
POST   /api/entreprises/{id}/rapprochements/{relId}/lignes/{ligneId}/pointer    Pointer une ligne
POST   /api/entreprises/{id}/rapprochements/{relId}/lignes/{ligneId}/depointer  Dépointer
GET    /api/entreprises/{id}/rapprochements/{relId}/lignes/{ligneId}/proposition-ia   Proposition IA (module 1️⃣7️⃣, si active)
POST   /api/entreprises/{id}/rapprochements/propositions-ia/{propId}/accepter         Accepter une proposition IA
POST   /api/entreprises/{id}/rapprochements/propositions-ia/{propId}/rejeter          Rejeter une proposition IA

POST   /api/entreprises/{id}/immobilisations                             Enregistrer une immobilisation
GET    /api/entreprises/{id}/immobilisations                             Lister le registre
POST   /api/entreprises/{id}/immobilisations/generer-dotations?annee=..  Générer les dotations

POST   /api/entreprises/{id}/factures?type=DEVIS|FACTURE                 Créer un devis/une facture
GET    /api/entreprises/{id}/factures?type=DEVIS|FACTURE                 Lister
POST   /api/entreprises/{id}/factures/{id}/envoyer                       Envoyer
POST   /api/entreprises/{id}/factures/{id}/accepter                      Accepter un devis
POST   /api/entreprises/{id}/factures/{id}/refuser                       Refuser un devis
POST   /api/entreprises/{id}/factures/{id}/convertir                     Convertir en facture
POST   /api/entreprises/{id}/factures/{id}/valider?compteProduitId=..    Valider (génère l'écriture)
POST   /api/entreprises/{id}/factures/{id}/marquer-payee                 Marquer payée
```

### Documents
```
POST   /api/entreprises/{id}/documents         Upload document
GET    /api/entreprises/{id}/documents         Lister documents
GET    /api/entreprises/{id}/documents/{id}    Get détails doc
```

### Fiscalité
```
GET    /api/entreprises/{id}/declarations-fiscales              Lister déclarations
POST   /api/entreprises/{id}/declarations-fiscales              Créer déclaration
PUT    /api/entreprises/{id}/declarations-fiscales/{id}         Update déclaration
POST   /api/entreprises/{id}/declarations-fiscales/{id}/marquer-deposee
POST   /api/entreprises/{id}/declarations-fiscales/{id}/marquer-payee
POST   /api/entreprises/{id}/declarations-fiscales/{id}/generer-echeancier
```

### Dashboard
```
GET    /api/entreprises/{id}/tableau-de-bord   Tableau de bord
```

### Core Intelligent (voir module 1️⃣8️⃣ pour le détail)
```
GET    /api/entreprises/{id}/dossier                                          Dossier Comptable Intelligent
GET    /api/entreprises/{id}/findings                                         Findings ouverts
POST   /api/entreprises/{id}/findings/{findingId}/resoudre                    Résoudre un finding
POST   /api/entreprises/{id}/findings/{findingId}/rejeter                     Rejeter un finding
GET    /api/entreprises/{id}/comptabilisation/documents/{docId}/proposition   Proposition de comptabilisation
POST   /api/entreprises/{id}/comptabilisation/propositions/{id}/accepter      Présélectionner une proposition
POST   /api/entreprises/{id}/comptabilisation/propositions/{id}/rejeter       Rejeter une proposition
GET    /api/entreprises/{id}/cloture-readiness                                Avis de préparation à la clôture
```

---

## 🔒 Configuration Sécurité

### JWT (JSON Web Tokens)

**Configuration (application.properties):**
```properties
security.jwt.secret=changeit-dev-secret-changeit-dev-secret-32B
security.jwt.expiration-minutes=480  # 8 heures
```

**En Production:**
```bash
# Définir via variables d'environnement
export JWT_SECRET="votre-secret-très-long-de-32-caractères-minimum"
export JWT_EXPIRATION_MINUTES="480"
```

### Stockage des Documents

**Configuration (application.properties):**
```properties
storage.documents.root-dir=./data/documents
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

**En Production:**
```bash
export DOCUMENTS_STORAGE_DIR="/var/comptable/documents"
```

---

## 📋 Fonctionnalités Principales

### ✅ Implémentées

- ✅ **Authentification JWT** : Inscription/Connexion des utilisateurs
- ✅ **Gestion Multi-tenant** : Support de plusieurs cabinets indépendants
- ✅ **Gestion d'Entreprises** : Onboarding et gestion des clients
- ✅ **Comptabilité Complète** :
  - Création et validation des écritures comptables
  - Gestion des journaux (Ventes, Achats, Banque, Caisse, OD)
  - Plan comptable SYSCOHADA pré-chargé
  - Gestion des comptes (débit/crédit, équilibre)
- ✅ **Rapports** :
  - Balance générale
  - Grand livre par compte
  - Journal des opérations
- ✅ **Gestion de Documents** :
  - Upload avec validation
  - Extraction OCR heuristique
  - Liaison documents-écritures
  - Stockage sécurisé local
- ✅ **Fiscalité** :
  - Gestion des déclarations fiscales
  - Suivi des obligations TVA
  - Génération d'écheanciers
- ✅ **Dashboard** :
  - Vue d'ensemble financière
  - Soldes clés
  - Derniers documents/écritures
- ✅ **Gestion des Rôles et Permissions**
- ✅ **États Financiers SYSCOHADA** : Bilan et Compte de Résultat générés en direct depuis les écritures validées
- ✅ **Clôture d'Exercice** : détermination du résultat (via le moteur commun `AccountingOperationEngine`, mission cloture Étape 4), solde des comptes de charges/produits selon le signe réel du mouvement, checklist de clôture bloquante, ouverture automatique de l'exercice suivant
- ✅ **Régularisations, Provisions, Dépréciations** (mission cloture Étape 4, module 3️⃣.M) : CCA/PCA/FNP/CAP/produit-à-recevoir/produit-à-reporter avec répartition temporelle déterministe, provisions (dotation/reprise totale/reprise partielle), dépréciations (perte constatée si valeur recouvrable < valeur comptable) — toujours via proposition + validation humaine, jamais de compte deviné
- ✅ **Lettrage** : rapprochement des lignes d'un compte tiers par code alphabétique (A, B, ... AA, ...)
- ✅ **Rapprochement Bancaire** : import de relevé CSV avec appariement automatique par montant
- ✅ **Immobilisations & Amortissements** : registre des actifs, plan d'amortissement linéaire/dégressif, génération des dotations
- ✅ **Facturation** : devis → facture (BROUILLON → ENVOYE → ACCEPTE → converti → VALIDEE → PAYEE), écriture de vente automatique avec TVA
- ✅ **Invitation Collaborateur/Client** : création de compte avec mot de passe temporaire, rattachement automatique à l'entreprise pour les clients
- ✅ **Extraction par Vision (VLM)** : lecture de photos de factures/reçus Mobile Money par Groq, avec score de confiance (derrière `agent.vlm.enabled`)
- ✅ **Ingestion WhatsApp** : dépôt de pièces par simple photo envoyée à un numéro WhatsApp, sans passer par le portail web
- ✅ **Pipeline Agentique (LangGraph4j)** : conformité déterministe + contrôle de fraude + routage auto/validation humaine, checkpoints persistants
- ✅ **Inbox de Validation** : écran dédié pour corriger et valider les pièces à faible confiance ou à risque
- ✅ **CRM de Relance WhatsApp** : détection des pièces en retard et relance automatique quotidienne, avec historique
- ✅ **Rapprochement Sémantique Mobile Money** : filet de secours IA sur les lignes de relevé non résolues par le rapprochement exact (relevé ↔ écritures uniquement)
- ✅ **Agent Contrôleur de Fraude & Anomalies Fiscales** : écart de TVA, montant rond suspect, doublon strict, analyse visuelle optionnelle, blocage automatique en validation humaine sur score critique
- ✅ **Universal Matching Engine** : rapprochement des lignes de relevé non résolues contre écritures **et** factures clients/pièces fournisseur (pas seulement les écritures déjà comptabilisées, cf. module 1️⃣7️⃣), avec pointage automatique réel réservé aux seules écritures comptables — testable sans clé Anthropic via `AI_PROVIDER=mock` côté `ai-service`
- ✅ **Agent Comptable Autonome** : boucle tool-use multi-tours (module 1️⃣6️⃣), endpoint autonome `POST /api/v1/agent/analyser-piece`, pas encore câblé sur les flux d'ingestion existants
- ✅ **Dossier Comptable Intelligent** : score global + 7 scores par domaine (Banque/Documents/Clients/Fournisseurs/Justificatifs/Fiscalité/Clôture), statut `PRET`/`A_SURVEILLER`/`BLOQUE`, actions prioritaires triées par sévérité (module 1️⃣8️⃣)
- ✅ **Finding (modèle central d'exception)** : `MissingEvidenceAnalyzer` (événementiel, après chaque décision de rapprochement) + `ControlEngine` (déclarations TVA manquantes/en retard, documents à risque, écritures brouillon oubliées), Résolution/Rejet manuels
- ✅ **Comptabilisation automatique niveau 3** : `AccountingProposalBuilder` résout tiers (normalisation) + compte (historique) sans appel IA supplémentaire ; comptabilisation réelle uniquement quand la confiance est haute et l'historique cohérent, sinon simple suggestion à valider — débloque `comptabiliserAutomatiquement` (derrière `agent.accounting-proposal.enabled`)
- ✅ **Boucle de résolution WhatsApp automatique** : un justificatif manquant sans aucun candidat déclenche une `DemandePiece` ciblée (montant/date/libellé réels, pas un template), liée au `Finding` d'origine
- ✅ **Closing Readiness** : avis `READY`/`READY_WITH_REVIEW`/`NOT_READY` consultatif avant clôture d'exercice, n'automatise jamais la clôture elle-même
- ✅ **Cabinet Cockpit** (mission P1, module 1️⃣9️⃣) : vue portefeuille cross-entreprises — Client Health, Portfolio Risk, "à traiter aujourd'hui", charge par collaborateur, analytics. Comble le manque identifié en P0 ci-dessous.
- ✅ **AI Copilot Evidence-First** (mission P1, module 1️⃣9️⃣) : niveau comptable **et** niveau cabinet, jeu fermé de questions structurées, jamais de chat libre, preuves toujours citées. Comble le manque identifié en P0 ci-dessous.
- ✅ **Financial Intelligence** (mission P1, module 1️⃣9️⃣) : Financial Snapshot, Cash Position multicanal, Cash Forecasting (KNOWN/EXPECTED), Collection Risk, Compliance Center, Tax Health.
- ✅ **Export FEC** (`ExportFecService`) : déjà présent avant ce chantier, **découvert et documenté pour la première fois en P1** (voir section D ci-dessus) — 18 colonnes officielles, écritures validées uniquement.

### 🔄 À Implémenter/Améliorer

- ⏳ ~~**AI Copilot structuré**~~ **Livré en P1** (voir ci-dessus).
- ⏳ ~~**Cabinet Cockpit multi-entreprises**~~ **Livré en P1** (voir ci-dessus).
- ⏳ **Unification des deux représentations "facture fournisseur"** (`Document` type `FACTURE_ACHAT` vs `facture_fournisseur`) : incohérence documentée, refonte trop lourde pour ce chantier
- ⏳ **Pipeline agentique pour l'upload web** : le graphe LangGraph4j (conformité, routage) ne se déclenche aujourd'hui que pour les pièces WhatsApp ; le contrôle de fraude s'applique bien aux deux canaux, mais pas encore le reste du pipeline
- ⏳ **Universal Matching Engine avec un vrai appel Anthropic** : testé et fonctionnel en mode `AI_PROVIDER=mock` uniquement (aucun budget Anthropic disponible à ce jour) — aucun changement de code prévu pour basculer en production, juste `AI_PROVIDER=anthropic` + `ANTHROPIC_API_KEY`
- ⏳ **Décision produit sur l'Agent Comptable Autonome** (module 1️⃣6️⃣) : remplacer ou compléter le duo extraction+fraude classique dans les flux d'ingestion (upload web, WhatsApp) ?
- ⏳ **Unification des achats sur le moteur commun** (`CommandeAchatService`) : structurellement incompatible avec `AchatRule` en l'état (agrégation multi-comptes par ligne) — dette documentée, mission cloture Étape 4, module 3️⃣.M.
- ⏳ **Trois calculs indépendants de "prêt à clôturer"** (`ClotureExerciceService` inline, `ClotureReadinessService`, `DossierComptableService`) : la checklist de clôture (Étape 4) et `ClotureExerciceService` sont désormais unifiés autour de `ClotureReadinessService`, mais `DossierComptableService` reste un 3ᵉ calcul séparé — non résolu, dette documentée.
- ⏳ **Signatures Électroniques** : Validation des documents par DSN
- ⏳ **Intégrations Bancaires** : Connexion avec les banques (API XOF)
- ⏳ **Export/Import** : Format Excel, CSV, XML pour intégration
- ⏳ **Audit Trail Complet** : `AuditTrailService` livré en P1 (module 1️⃣9️⃣), mais câblé
  uniquement sur un sous-ensemble d'actions (résolution/rejet/assignation de Finding, création/
  réponse à une DemandePiece) — pas encore sur toutes les mutations du système (écriture
  validée, match accepté, déclaration modifiée...). Extension directe avec le même primitif.
- ⏳ **Notifications Email** : Reminders pour obligations fiscales
- ⏳ **Mobile App** : Application iOS/Android
- ⏳ **Brouillon Collaboratif** : Édition en temps réel avec plusieurs utilisateurs
- ⏳ **Analytics Avancés** : Dashboards financiers interactifs
- ⏳ **Conformité RGPD** : Exportation/suppression de données
- ⏳ ~~**Copilot Dirigeant**~~ **Livré en P1.5** (`copilot-dirigeant-widget.component.ts`,
  endpoint `POST /api/entreprises/{id}/copilot-dirigeant/question`, voir module 1️⃣9️⃣).
- ⏳ **Cash Forecasting palier PREDICTED** : seuls KNOWN et EXPECTED sont livrés (mission P1
  section 24) — aucune date de paiement réelle n'est persistée sur `Facture` aujourd'hui, donc
  aucune prédiction statistique fiable n'était calculable honnêtement.
- ⏳ ~~**Tests HTTP de permissions par rôle**~~ **Livré en P1.5** (`P1_5SecuriteHttpE2ETest`,
  premier test `MockMvc` réel du projet, voir module 1️⃣9️⃣).
- ⏳ **`TaxRule`** (mission P1 section 31) : schéma versionné prêt (`tax_rule`, migration V27),
  volontairement vide — aucune règle fiscale réelle n'est peuplée sans source réglementaire
  vérifiée.
- ⏳ **Time to Closing non instrumenté** (mission P1.9) : `ExerciceComptable` n'a aucun
  horodatage de clôture persisté (`ClotureReadinessService` recalcule à la volée) — gap
  documenté, pas de colonne ajoutée sans cas d'usage pilote réel pour la justifier (voir
  `docs/P1_9_AUDIT.md` section F.3).
- ⏳ **Cash Forecast Quality non instrumenté** (mission P1.9) : nécessite un historique réel de
  prévisions vs réalisations sur plusieurs mois, absent de l'environnement de développement
  (voir `docs/P1_9_AUDIT.md` section F.4).
- ⏳ **False Auto-Match Rate non mesurable** (mission P1.9) : une `AiMatchProposal` déjà
  auto-appliquée (`ACCEPTED`, `validatedBy IS NULL`) ne peut être ni corrigée ni rejetée via
  aucun endpoint existant (`AiMatchProposalService.charger` exige `status == PROPOSED`) — le KPI
  a été conçu puis retiré plutôt que de livrer un taux fabriqué (voir `docs/P1_9_AUDIT.md`).

---

## 📞 Support et Contribution

Pour signaler un bug ou proposer une fonctionnalité :
```
Issues : https://github.com/yourusername/comptable/issues
Pull Requests : https://github.com/yourusername/comptable/pulls
```

---

## 📄 Licence

Ce projet est sous licence MIT. Voir le fichier `LICENSE` pour plus de détails.

---

## 📚 Documentation Complémentaire

- **Spring Boot** : https://spring.io/projects/spring-boot
- **Angular 18** : https://angular.io/docs
- **PostgreSQL** : https://www.postgresql.org/docs/
- **JWT** : https://jwt.io/introduction
- **SYSCOHADA** : https://www.ohada.org/

---

**Dernière mise à jour:** 2026-08-13 (mission P1.9)
**Version:** 0.0.1-SNAPSHOT
