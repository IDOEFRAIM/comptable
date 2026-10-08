# Comptable — Audit d'architecture existante (Phase 0)

> Livrable 1 de la mission « Grand chantier de refonte fonctionnelle ». Constat uniquement :
> aucune modification de code n'a été faite pour produire ce document. Chaque affirmation a été
> vérifiée dans le code (chemins relatifs à `comptable/src/main/java/com/example/comptable`).
> Documents liés : [workflows](comptable-current-workflows.md) ·
> [gap analysis](comptable-gap-analysis.md) · [architecture cible](comptable-target-architecture.md) ·
> [roadmap](comptable-roadmap.md). Docs antérieurs (P0 à P2) : `docs/P*.md`.

## 0. Résumé exécutif

La mission décrit comme « futurs » plusieurs composants qui **existent déjà** sous un autre nom.
Le chantier n'est donc pas une construction : c'est une **unification et un durcissement**.

| Concept de la mission | Déjà présent sous la forme de | Verdict |
|---|---|---|
| Review Inbox / gestion par exception | `cabinet/InboxService` + `agent/finding/*` (`Finding`) + écran `inbox-validation` | Existe, couvre un périmètre partiel |
| AccountingControlEngine | `agent/finding/ControlEngine` + `dossier/ClotureReadinessService` + `agent/fraude/AnalyseFraudeService` + contrôles dispersés dans les services | **Trois moteurs partiels + contrôles dispersés** : à fédérer, pas à recréer |
| Document Hub | `document/*` + `agent/pipeline/*` (graphe LangGraph-like avec checkpoint) + `agent/whatsapp/*` + `agent/relance/*` | Existe, sans déduplication par hash ni canal e-mail |
| AccountPurpose / résolution de compte | `comptabilite/parametrage/*` (`AccountPurpose`, `AccountingAccountResolver`, statuts RESOLVED/AMBIGUOUS/NOT_CONFIGURED/INVALID) | Existe, **migration incomplète** (cf. §11) |
| Rapprochement exact/probable/ambigu | `agent/matching/*` (`MATCH_AUTO` / `MATCH_REVIEW` / `NO_MATCH` / `AI_UNAVAILABLE`) + `RapprochementBancaireService` + `LettrageService` | Existe pour banque↔écriture ; absent pour paie |
| Explicabilité | `ResultatRegle.explications`, `Finding.description` + `evidence`, `Copilot` avec `EvidenceRefDto` | Existe, hétérogène |
| Audit trail | `audit/AuditTrailService` (distingue HUMAIN / IA) | Existe mais **branché sur 3 services seulement** |
| Mémoire comptable (historique) | `AccountingProposalBuilder.eligibleNiveau3` (historique cohérent, jamais LLM) | Existe de façon étroite |

## 1. Vue d'ensemble technique

- **Stack** : Spring Boot (Java, Maven wrapper), JPA/Hibernate + Flyway (**48 migrations**, V1→V48),
  PostgreSQL ; Angular standalone + signals ; `ai-service` Python (FastAPI) ; Docker Compose, Caddy.
- **Volumétrie** : 547 fichiers Java main (~28 500 lignes), 95 fichiers de test Java, ~35 écrans Angular lazy.
- **Modules backend** (packages racine) : `agent`, `audit`, `cabinet`, `comptabilite`, `config`,
  `copilot`, `dashboard`, `demande`, `document`, `dossier`, `entreprise`, `finance`, `fiscalite`,
  `identite`, `notification`, `recouvrement`, `security`, `stock`, `telemetry`, `common`.
- **Frontend** : routes sous `entreprises/:entrepriseId/*` (tableau-de-bord, documents, demandes-pieces,
  validation, tiers, plan-comptable, parametres-comptables, operations, ecritures, rapports,
  etats-financiers, tresorerie, recouvrement, rapprochement, immobilisations, regularisations,
  facturation, stocks, achats, fiscalite, conformite, inviter) + `cabinet` et `cabinet/indicateurs`.

## 2. Modèle multi-tenant et sécurité

- `Tenant` = cabinet, `Entreprise` = client du cabinet (cf. `docs/P1_ARCHITECTURE.md`).
- **Un seul point de contrôle d'accès** : `entreprise/service/EntrepriseAccessService.verifierAcces`
  (appelé 153 fois). Il compare le tenant de l'entreprise à celui du JWT, répond **404** (pas 403)
  hors tenant, et restreint `ROLE_CLIENT` aux entreprises rattachées (`utilisateur_entreprise`).
- `common/tenant/TenantContext` (ThreadLocal rempli par le filtre JWT) n'est utilisé qu'à 7 endroits.
- **Point d'attention** : l'isolation est **applicative uniquement** (pas de Row-Level Security
  PostgreSQL). Toute nouvelle entité doit porter `entreprise_id` et passer par `verifierAcces` ;
  un oubli dans une requête de repository fuit entre entreprises. Un bug de ce type a déjà existé
  (fuite sur `FindingService.assigner`, corrigé en P1.5).
- Les traitements système (pipeline agentique, WhatsApp) s'exécutent **sans `UtilisateurPrincipal`** ;
  ils re-vérifient les garde-fous eux-mêmes (cf. `ComptabilisationService.comptabiliserAutomatiquement`,
  `AccountingOperationEngine.proposerEtValiderImmediatementSysteme`).

## 3. Noyau comptable

### 3.1 Écritures et référentiel
- Entités : `EcritureComptable` → `LigneEcriture`, `Journal`, `ExerciceComptable`, `CompteComptable`
  (rattaché à une `PlanComptableReference` via `parent`/`role_systeme`), `Tiers`.
- Référentiel SYSCOHADA hiérarchique : `PlanComptableReference` + `PlanComptableImportService` +
  `ReferentielComptableService` (migrations V30–V33).
- Statuts d'écriture : `BROUILLON` → `VALIDEE` (`StatutEcriture`), exercices `OUVERT`/`CLOTURE`.

### 3.2 Moteur d'opérations (cœur à préserver)
`comptabilite/operation/common/AccountingOperationEngine` (751 lignes) :
`proposer*` → `AccountingOperationProposal` (+ lignes, `StatutOperationProposal`) → `valider` / `rejeter`
→ `EcritureComptable`. Les règles métier sont dans des `*Rule` : `AchatRule` (442 l.), `VenteRule`,
`ReglementRule`, `AvoirRule`, `InventaireRegularisationRule`, `RegularisationRule`, `ProvisionRule`,
`DepreciationRule`, `ClotureRule`, plus `TvaRateResolver`/`JournalResolver`.
- Principe déjà appliqué : `ResultatRegle` ne contient que des lignes **entièrement résolues** ; un manque
  devient une entrée de `explications`, **jamais un compte deviné**.
- **Idempotence** par `(entrepriseId, sourceType, sourceId, type)` (repository de propositions).
- Variantes `proposerEtValiderImmediatement` (humain) / `...Systeme` (sans utilisateur, `createdBy` null).

### 3.3 Résolution de compte
- `AccountPurpose` : 23 rôles (ACHAT_*, VENTE_*, TVA_*, STOCK_*, CLIENT, FOURNISSEUR, BANQUE, CAISSE,
  IMMOBILISATION_*, AMORTISSEMENT, DOTATION_*, RESULTAT_*). `classesAttendues()` ne valide qu'à la
  **création** d'un mapping, jamais pour deviner.
- `AccountingAccountResolver` : mapping configuré (contexte spécifique puis générique) → repli
  `CompteSystemeResolver` (rôles système du référentiel). Statuts `RESOLVED / AMBIGUOUS /
  NOT_CONFIGURED / INVALID`, `NiveauConfiance`. Ne tranche jamais arbitrairement entre candidats.
- `RoleCompteSysteme` : 9 rôles (TVA_COLLECTEE, TVA_DEDUCTIBLE, DOTATIONS_AMORTISSEMENTS,
  RESULTAT_BENEFICE/PERTE, RACINE_CLIENT/FOURNISSEUR, TRESORERIE_BANQUE/CAISSE).

### 3.4 Autres sous-domaines
- **Stock** (`stock/*`) : articles, dépôts, mouvements, CUMP (`PumpCalculator`, `CumpValuationService`),
  inventaires + régularisation via le moteur, commandes d'achat, avoirs fournisseur.
- **Immobilisations** (`ImmobilisationService`, `PlanAmortissementCalculator`, `DotationAmortissement`).
- **Régularisations / provisions / dépréciations** : 3 sous-modules, tous via le moteur.
- **Fiscalité** : déclarations, échéancier TVA, `TaxRule` (table **vide**, cf. P1), paiement fiscal
  (`fiscalite/paiement`, providers e-Syntaxe / Faso Arzeka en mode simulation).
- **Trésorerie / finance** : `CashPositionService`, `CashForecastService` (633 l.), `FinancialSnapshotService`.

## 4. Couche « agent » (déjà largement construite)

| Sous-module | Rôle | Remarque |
|---|---|---|
| `agent/pipeline` | Graphe de pièce justificative : extraction → fraude → conformité → validation humaine → écriture, avec checkpoint JDBC | Reprise après validation (`reprendreApresValidation`) |
| `agent/extraction` | `AiServiceOcrExtractionService` (VLM) vs `HeuristiqueOcrExtractionService` (PDFBox + regex) | Exclusifs via `agent.vlm.enabled` ; **en Docker les flags IA sont tous à `false`** |
| `agent/fraude` | `AnalyseFraudeService` : TVA, montant rond, doublon (montant sur fenêtre de 3 j) | Déterministe, rapport JSON sur `Document` |
| `agent/finding` | `Finding` (8 `TypeFinding`, 3 sévérités), `ControlEngine`, `MissingEvidenceAnalyzer` | Idempotence par source |
| `agent/matching` | `MatchDecisionEngine` (seuils 0,95 / 0,70), `AiMatchProposal` | IA désactivée par défaut |
| `agent/comptabilisation` | `AccountingProposal` + `AccountingProposalBuilder` (niveau 3 = historique cohérent) | **Deuxième entité de proposition**, distincte de `AccountingOperationProposal` |
| `agent/accounting` | Postage IA via le moteur (`AiAccountingPostingService`) | Passe par le moteur et le résolveur |
| `agent/whatsapp`, `agent/relance` | Ingestion WhatsApp, relance des pièces manquantes | `DemandePiece` côté `demande/*` |
| `copilot`, `cabinet`, `dossier`, `recouvrement` | Copilot (lecture seule), Cockpit, Inbox, score de dossier | Projections, pas de nouvelle source de vérité |

`ai-service` (Python) : classification comptable (`accounting/classifiers/deterministic.py`,
`posting_policy.py`), matching, fraude, copilot, graphes CFO / cash forecast. Les décisions
comptables restent côté Java (« puis Java décide »).

## 5. Audit trail
`audit/AuditTrailService` écrit dans `audit_log` avec `source=HUMAIN` ou `source=IA` (modèle, version de
prompt, validé ou non). **Appelé uniquement par** `FindingService`, `AccountingAccountMappingService`,
`DemandePieceService`. Les écritures, la clôture, le moteur d'opérations, la comptabilisation de
document et le rapprochement **n'écrivent pas d'événement d'audit** (le moteur garde néanmoins
`createdBy`/`validatedBy` sur ses propositions).

## 6. Tests
95 fichiers de test Java ; 12 `@SpringBootTest` (dont 3 E2E sur PostgreSQL), 2 usages de `MockMvc`.
`ai-service/tests` : ~40 fichiers pytest. Baseline `mvn test` / `ng build` : voir §7.

## 7. Baseline de référence (avant tout chantier)
- `ng build` : OK le 2026-10-01 (bundle généré sans erreur).
- `./mvnw test` : **503 tests, 0 échec, 0 erreur, 0 ignoré — BUILD SUCCESS** (2026-10-02, incluant les
  3 tests E2E `@SpringBootTest`, dont le nouveau test de non-régression de l'extracteur OCR).
- `ng build` (production) : OK. `pytest` côté `ai-service` : non relancé dans cette phase (aucune modification).

## 8. Dettes architecturales (synthèse, détails dans le gap analysis)
1. **Trois chemins de création d'écriture hors moteur** : `ComptabilisationService` (document → écriture,
   *c'est le flux de l'UI*), `MouvementStockService`, `ImmobilisationService`, plus la saisie manuelle
   `EcritureComptableService`.
2. **Hardcodes résiduels** : `ComptabilisationService` (`"4456"`, `"443"`), `DeclarationFiscaleService`
   (`"443"`, `"4456"`), `TiersService` (`"411"`, `"401"`), `EtatsFinanciersService` (préfixes `66`, `77`, `40`, `41`).
3. **Contrôles dupliqués** : « exercice clos » écrit 4 fois, « HT + TVA = TTC » 3 fois (fraude, nœud
   conformité, comptabilisation), équilibre débit/crédit 6 fois.
4. **Deux représentations de facture fournisseur** (`Document` FACTURE_ACHAT vs `facture_fournisseur`),
   déjà identifiée en P0/P2, non résolue.
5. **Deux entités de proposition** (`AccountingProposal` vs `AccountingOperationProposal`).
6. **3 tables mortes** (`permission`, `role_permission`, `collaborateur_entreprise`).
7. **Clôture** : 12 points de la checklist sont `NOT_APPLICABLE` (« aucune logique de contrôle approfondie »).
8. **Extraction heuristique fragile** : bug réel corrigé le 2026-10-01 (HT lu à 1 à cause d'un en-tête de
   tableau) — cf. `HeuristiqueOcrExtractionService.extraireMontant`.

## 9. Risques de régression
- Toucher `ComptabilisationService` impacte l'écran Documents (flux utilisateur principal) et le pipeline
  agentique (`EcritureNode`).
- Toucher `AccountingOperationEngine` impacte stock, facturation, avoirs, régularisations, clôture, IA.
- Toute nouvelle contrainte de contrôle peut bloquer des flux qui passent aujourd'hui (ex. exiger une
  pièce) : introduire en `WARNING` d'abord, `BLOCKING` ensuite.
