# Comptable — Rapport de Phase A : cœur comptable unique avant le premier pilote

> Preuve du chemin unique : [`accounting-single-path.md`](accounting-single-path.md). Suite de
> [`comptable-phase1-report.md`](comptable-phase1-report.md). Chemins relatifs à `comptable/`.

## Résultat

| Vérification | Avant Phase A | Après Phase A |
|---|---|---|
| `./mvnw test` | 571 tests | **606 tests, 0 échec, 0 erreur** |
| `npm run build` (Angular) | OK | **OK** |
| `pytest` (`ai-service`) | — | **328 passés** (aucun contrat Java utilisé par l'IA n'a changé ; lancé par prudence) |

## 1. Architecture avant

```
Documents   → ComptabilisationService → AccountingOperationEngine ✔   (Phase 1)
Facture/Avoir/Règlement/Clôture/Régularisations → moteur ✔
Mouvements de stock   → MouvementStockService  → new EcritureComptable  ✘ (compte lu sur l'article, journal "OD")
Dotations             → ImmobilisationService  → new EcritureComptable  ✘ (journal "OD", une écriture groupée)
Acquisition immo      → AUCUN chemin comptable
Tiers                 → TiersService           → racines "411"/"401" en dur
Déclaration TVA       → DeclarationFiscaleService → "443"/"4456" en dur
TVA (taux + compte)   → TvaRateResolver mélangeait taux (TaxRule) ET compte (CompteSystemeResolver)
Résolution de rôle    → CompteSystemeResolver + AccountingAccountResolver (deux mécanismes)
Suggestion d'imputation → AccountingProposal (nom ambigu avec AccountingOperationProposal), journal "ACH" en dur
Immobilisation        → statut CEDEE jamais affecté, pas de mise en service, pas de nature
Schéma                → tables mortes permission / role_permission / collaborateur_entreprise
```

## 2. Architecture après

Un seul chemin : `SOURCE → RULE → ACCOUNT PURPOSE → RESOLVER → ENGINE → PROPOSAL → ENTRY`, détaillé classe par classe
pour achat, vente, avoir, règlement, stock, immobilisation, amortissement, provision, dépréciation et clôture dans
[`accounting-single-path.md`](accounting-single-path.md).

## 3. Producteurs directs d'écriture

**Inventaire exhaustif avant modification** (recherche de `new EcritureComptable`, `new LigneEcriture`, `LigneEcriture.debit/credit`,
`ecritureComptableRepository.save/saveAll/delete`, `ligneEcritureRepository.save/saveAll`) :

| Producteur | Module | Opération | Création directe ? | Passe par le moteur ? | À migrer ? | Justification |
|---|---|---|---|---|---|---|
| `AccountingOperationEngine#validerInterne` | comptabilité | toutes | oui | **c'est le moteur** | non | chemin normal |
| `EcritureComptableService#creerManuelle` | comptabilité | saisie manuelle | oui | non | non | cas métier assumé ; garde-fous complets (§4 du doc) |
| `ImmobilisationService#genererDotations` | immobilisation | dotation | **oui** | non | **oui → fait** | écriture groupée construite à la main |
| `MouvementStockService#genererEcriture` | stock | entrée / sortie / ajustement | **oui** | non | **oui → fait** | écriture construite à la main |
| `LettrageService` | comptabilité | lettrage | non (met à jour `lettrage`) | — | non | pas une création |
| `ComptabilisationService` | documents | achat / vente | (migré en Phase 1) | oui | — | — |

**Après** : 2 producteurs, tous deux légitimes (moteur, saisie manuelle). Vérifiable :
`grep -rlE "new EcritureComptable\(|ecritureComptableRepository\.save" comptable/src/main/java` renvoie exactement ces deux fichiers.

## 4. AccountPurpose

| | |
|---|---|
| **Ajoutés** | `STOCK_PRODUIT` (produits finis et intermédiaires), `STOCK_AUTRE` (articles de nature AUTRE) — le modèle `TypeArticle` les exige ; classes attendues : 3 |
| **Supprimés** | `VENTE_MARCHANDISE`, `VENTE_PRODUIT`, `VENTE_SERVICE` : 0 usage (le compte de produit d'une vente est toujours explicite) ; un mapping sur ces purposes n'aurait eu aucun effet |
| **Désormais utilisés** | `STOCK_*`, `VARIATION_STOCK`, `AMORTISSEMENT`, `IMMOBILISATION_CORPORELLE/INCORPORELLE` (n'étaient demandés par aucun code opérationnel) |

## 5. Types d'opération du moteur

| | |
|---|---|
| **Ajoutés** | `STOCK_ENTREE`, `STOCK_SORTIE`, `STOCK_AJUSTEMENT`, `IMMOBILISATION_ACQUISITION`, `AMORTISSEMENT_DOTATION` |
| **Supprimés** | aucun : les 17 types existants ont tous au moins un usage |
| **Volontairement absents** | `IMMOBILISATION_CESSION` (règles de cession non vérifiées), `MANUAL_ENTRY` (la saisie manuelle n'est pas une opération du moteur, cf. doc §3) |

## 6. Hardcodes supprimés

| Avant | Maintenant |
|---|---|
| `TiersService` : `"411"` / `"401"` | racine résolue par `AccountingAccountResolver` (purposes `CLIENT` / `FOURNISSEUR`, compte collectif sans exigence de détail) ; sous-compte dérivé du numéro résolu ; refus 422 explicite si non utilisable |
| `DeclarationFiscaleService` : `"443"` / `"4456"` | purposes `TVA_COLLECTEE` / `TVA_RECUPERABLE` ; refus 422 explicite |
| `MouvementStockService`, `ImmobilisationService` : journal `"OD"` | journal par **type** résolu par le moteur (`JournalResolver`) |
| `AccountingProposalBuilder` : journal `"ACH"` | le journal ne fait plus partie de la suggestion (colonne supprimée) |
| `TvaRateResolver` : compte de TVA via rôle système | `TvaRateResolver` ne traite plus que le **taux** ; le compte vient du résolveur dans `AchatRule` / `VenteRule` |
| `JournalResolver#resoudreParCode`, `CompteSystemeResolver` | supprimés (aucun appelant ; mécanisme fusionné dans le résolveur central) |
| `FactureAvoirService` : `RoleCompteSysteme.TVA_COLLECTEE` direct | purpose `TVA_COLLECTEE` via le résolveur |

## 7. Hardcodes restants (justifiés)

| Où | Valeur | Catégorie | Justification |
|---|---|---|---|
| `AchatRule` | classes `"2"` / `"6"` | REFERENCE | cohérence nature d'achat ↔ classe SYSCOHADA (pas un compte) |
| `LigneEcritureRepository`, `FinancialSnapshotService`, `CashForecastService` | `5%`, `6%`, `7%` | REFERENCE | agrégats de **reporting** par classe SYSCOHADA, aucune écriture |
| `CashPositionService` | `startsWith("5")`, `startsWith("57")`, libellé `CAISSE` | REFERENCE (reporting) | classement affichable des canaux de trésorerie ; **à remplacer par les purposes `BANQUE`/`CAISSE`** en phase suivante |
| `EtatsFinanciersService` | préfixes `66`, `77`, `40`, `41`, poste `120/129` | REFERENCE (reporting) | dépend d'une table de correspondance SYSCOHADA **vérifiée** (roadmap phase 8) ; ne crée aucune écriture |
| `EntrepriseService` | journaux `ACH`, `VTE`, `BQ`, `OD` | SEED | création des journaux par défaut d'une entreprise ; les services ne les relisent plus par code |
| migrations `V2`, `V4`, `V8`, `V31`, `V33` | numéros de compte | MIGRATION / SEED | référentiel **de démonstration** (V33 le dit explicitement) |
| tests | numéros de compte | TEST | fixtures |
| `docs/` | numéros de compte | DOCUMENTATION | — |

## 8. Cycle Stock final

```
MouvementStockService  (quantités, CUMP via StockValuationService — INCHANGÉ, testé par PumpCalculatorTest)
   │  fait économique : type, quantité, valeur au CUMP
   ▼
MouvementStock.save  →  comptabiliser()           (même transaction)
   │  valeur 0 → pas d'écriture
   ▼
StockMouvementRule + StockAccountResolver
   │  STOCK_MARCHANDISE | STOCK_MATIERE | STOCK_PRODUIT | STOCK_AUTRE  (selon TypeArticle)  +  VARIATION_STOCK
   │  premier palier = comptes explicites de l'article ; sinon mapping ; sinon NOT_CONFIGURED
   ▼
AccountingOperationEngine.proposerEtValider(STOCK_ENTREE | STOCK_SORTIE | STOCK_AJUSTEMENT, source MOUVEMENT_STOCK/id)
   ▼
EcritureComptable VALIDEE, MouvementStock.ecritureId
```
Inventaire : `InventaireService.valider` → `InventaireRegularisationRule` (mêmes purposes via `StockAccountResolver`) → proposition
`REGULARISATION_INVENTAIRE`, **désormais idempotente par inventaire**. Invariant transactionnel : si la comptabilisation est refusée
(compte invalide, exercice clos…), le mouvement physique et le CUMP ne sont pas enregistrés (test `stock_compte_desactive_est_INVALID…`).

## 9. Cycle Immobilisation final

```
creer            → ACQUISE          (nature CORPORELLE/INCORPORELLE, aucun plan, aucune écriture)
comptabiliser-acquisition (optionnel) → IMMOBILISATION_ACQUISITION : D actif HT / D TVA récupérable / C fournisseur TTC
mise-en-service  → EN_SERVICE       (dateMiseEnService ≥ dateAcquisition ; le plan démarre à cette date)
generer-dotations(année) → une écriture AMORTISSEMENT_DOTATION par immobilisation et par année (idempotent, tout-ou-rien)
sortie           → SORTIE           (dateSortie, motif CESSION | MISE_AU_REBUT, prix de cession éventuel ; plus de dotation à partir de l'année de sortie)
```
Audit : `IMMOBILISATION_ENREGISTREE`, `IMMOBILISATION_ACQUISE`, `IMMOBILISATION_MISE_EN_SERVICE`, `AMORTISSEMENT_COMPTABILISE`, `IMMOBILISATION_SORTIE`.
**Non construit volontairement** : écritures de sortie (VNC, plus/moins-value, reprise d'amortissements) — règles non vérifiées ; l'écran indique
qu'elles se saisissent à la main. **À vérifier réglementairement** : le prorata de la première année utilise la formule *déjà existante* du
calculateur, désormais alimentée par la date de mise en service au lieu de la date d'acquisition ; le prorata de l'année de sortie n'est pas défini.

## 10. Tables et schéma (migration `V49__coeur_comptable_unique.sql`)

| Changement | Détail |
|---|---|
| **Tables supprimées** | `permission`, `role_permission`, `collaborateur_entreprise` : aucune entité, repository, service, contrôleur, test, seed, FK entrante ni usage frontend (recherche exhaustive) |
| **Table renommée** | `accounting_proposal` → `imputation_suggestion` ; colonne `journal_id` supprimée ; index renommés |
| `immobilisation` | + `type`, `date_mise_en_service`, `acquisition_ecriture_id`, `date_sortie`, `motif_sortie`, `montant_cession` ; statut par défaut `ACQUISE` ; reprise des données de développement (`EN_SERVICE` → `date_mise_en_service = date_acquisition` ; `CEDEE` → `SORTIE`) |
| `dotation_amortissement` | `ligne_ecriture_id` → `ecriture_id` |

**Migrations non réinitialisées — décision motivée.** Les 49 migrations sont additives et cohérentes ; le seul poids mort (3 tables, un renommage) est
traité par V49. Un rebasage n'est pas un simple nettoyage ici : le référentiel comptable en base est une **démonstration** (`V33` : « pas la
nomenclature SYSCOHADA officielle »), `V8` seede des comptes de développement et `tax_rule` contient des fixtures `FIXTURE_DEMO`. Séparer schéma /
référentiel réglementaire / données de démo est une décision produit (que deviennent les comptes de démonstration ?) qui doit accompagner le
chantier du **référentiel SYSCOHADA officiel**. À faire à ce moment-là, avant la mise en production : `pg_dump --schema-only` d'une base migrée,
dump des seules tables de référence, `docker compose down -v`.

## 11. Entités fusionnées / renommées

| Avant | Après |
|---|---|
| `AccountingProposal` (+ `Service`, `Repository`, `Builder`, `Controller`, `Dto`, `StatutAccountingProposal`), package `agent.comptabilisation` | **`ImputationSuggestion`** (+ famille), package `agent.imputation` — *suggestion d'imputation* (quel tiers, quel compte, avec quelle confiance). **`AccountingOperationProposal`** reste la *proposition comptable complète* (lignes, journal, exercice) produite par le moteur. Les deux rôles étaient bien distincts : renommage, pas fusion |
| `CompteSystemeResolver` + `AccountingAccountResolver` | fusionnés dans `AccountingAccountResolver` ; `RoleCompteSysteme` **conservé** comme palier 4 (repli depuis `plan_comptable_reference.role_systeme`) |
| `StatutImmobilisation.CEDEE` | `StatutImmobilisation.SORTIE` (+ `ACQUISE`) |

`RoleCompteSysteme` n'est pas supprimé : c'est le lien du référentiel vers les purposes (TVA, tiers, trésorerie, résultat, dotations) ; le résolveur central
en est le seul consommateur, il n'existe plus deux systèmes équivalents.

## 12. Endpoints modifiés (frontend adapté)

| Avant | Après |
|---|---|
| `GET …/comptabilisation/documents/{id}/proposition` | `GET …/imputation/documents/{id}/suggestion` |
| `POST …/comptabilisation/propositions/{id}/accepter|rejeter` | `POST …/imputation/suggestions/{id}/accepter|rejeter` |
| — | `POST …/immobilisations/{id}/comptabiliser-acquisition` |
| — | `POST …/immobilisations/{id}/mise-en-service` |
| — | `POST …/immobilisations/{id}/sortie` |
| `POST …/immobilisations` | + champ `type` obligatoire |
| `POST …/immobilisations/generer-dotations` | réponse : `ecritureIds[]` (une écriture par immobilisation) au lieu de `ecritureId` |
| `ImmobilisationDto` | + `type`, `dateMiseEnService`, `acquisitionEcritureId`, `dateSortie`, `motifSortie`, `montantCession` ; statuts `ACQUISE/EN_SERVICE/SORTIE` |
| `ImputationSuggestionDto` | `journalCode` supprimé |

Angular : tableau de bord (suggestion d'imputation) et écran Immobilisations (nature, statut, comptabiliser l'acquisition, mettre en service, sortir).

## 13. Multi-tenant, idempotence, audit, transactions (preuves)

`CoeurComptableUniqueTest` (PostgreSQL réel) :
- **Isolation** : l'utilisateur A ne peut ni mettre en service, ni sortir, ni comptabiliser l'acquisition d'une immobilisation de B (404) ; ne peut pas mouvementer un article de B ; ne peut pas valider une proposition de B ; ne peut pas utiliser un tiers ou un compte de B ; un autre tenant n'accède pas à l'entreprise. Nouveau : `ImmobilisationRepository#findByIdAndEntrepriseId` ; `AiMatchProposalService` vérifiait la ligne de relevé sans filtre entreprise — corrigé.
- **Idempotence** : acquisition rejouée → 409, une seule écriture ; dotation rejouée → 0 nouvelle ; commande de stock rejouée pour le même mouvement → même proposition, même écriture. L'inventaire a reçu une idempotence par source (aucun test dédié de rejeu : son `valider` est déjà protégé par la garde de statut, testée).
- **Failure model** : `NOT_CONFIGURED` / `INVALID` ⇒ aucune écriture, 422 explicite, `PROPOSITION_COMPTABLE_A_REVOIR` audité ; mapping supprimé → refus, reconfiguré → succès au rejeu, sans doublon. `AMBIGUOUS` reste impossible en base (index uniques) et couvert par `AccountingAccountResolverTest`.
- **Audit** : événements du moteur (`PROPOSITION_COMPTABLE_CREEE/VALIDEE`, `ECRITURE_CREEE`) pour **tous** les flux (stock inclus : `STOCK_ENTREE/SORTIE/AJUSTEMENT` portés par la proposition) + événements Immobilisation + saisie manuelle (`ECRITURE_MANUELLE_CREEE`, `ECRITURE_VALIDEE`, `ECRITURE_SUPPRIMEE`).
- **Transactions** : chaque service métier est `@Transactional` ; l'état métier (mouvement + CUMP, statut immobilisation + lien d'écriture + ligne de dotation) et l'écriture sont atomiques.

## 14. Tests

**Supprimés ou remplacés (justification)**

| Test | Classement | Remplacement |
|---|---|---|
| `ImmobilisationServiceTest` (5 anciens : création, liste, dotation groupée, « ne génère rien », exercice clos) | COMPORTEMENT LEGACY (écriture groupée construite par le service) + INVARIANTS conservés | réécrit : 16 tests (cycle de vie, orchestration, invariants exercice clos / déjà généré conservés) |
| `TvaRateResolverTest` : « compte système non configuré », « par règle avec compte résolu » | DÉTAIL D'IMPLÉMENTATION (le resolveur ne porte plus de compte) | `AchatRuleTest` / `VenteRuleTest` : « taux connu mais compte de TVA non configuré » |
| `JournalResolverTest#resoudreparcode…` | DÉTAIL D'IMPLÉMENTATION (méthode supprimée, aucun appelant) | — |
| `MouvementStockServiceTest#refuse_lenregistrement_hors_dun_exercice_ouvert` | COMPORTEMENT LEGACY (contrôle fait par le service) | `un_refus_du_moteur_comptable_remonte_et_annule_le_mouvement` ; invariant « exercice clos » couvert par `ExerciceOuvertGuard` et le moteur |
| `InventaireRegularisationRuleTest`, `FactureAvoirServiceTest`, `AccountingAccountResolverTest`, `DeclarationFiscaleServiceTest`, `ImputationSuggestion*Test` | adaptés (fixtures et dépendances) | invariants métier **conservés** |

**Ajoutés** : `CoeurComptableUniqueTest` (8), `StockRulesTest` (5), `ImmobilisationRuleTest` (6), `ImmobilisationServiceTest` (16), +3 `MouvementStockServiceTest`, +1 `ControlesClotureTest`, +1 `DeclarationFiscaleServiceTest`, +1 `AchatRuleTest`, +1 `VenteRuleTest`.

## 15. Risques restants avant la Phase B

1. **Double comptabilisation d'une acquisition** : `comptabiliserAcquisition` refuse un second appel pour la même immobilisation, mais ne peut pas savoir que la même acquisition a déjà été comptabilisée via la facture fournisseur correspondante (nature `IMMOBILISATION` d'`AchatRule`). À lier explicitement (rapprocher immobilisation ↔ facture) en Phase B.
2. **Comptes explicites de l'article** : les 4 comptes restent obligatoires à la création d'un article ; les mappings `STOCK_*` n'interviennent donc que si ces comptes disparaissent (hypothèse de modèle future : les rendre facultatifs).
3. **Règles réglementaires non vérifiées** : prorata d'amortissement (première année et année de sortie), écritures de cession/mise au rebut, TVA sur cession. Rien n'est inventé ; l'interface le signale.
4. **Dotations tout-ou-rien** : un compte non configuré sur *une* immobilisation annule la génération des autres (explication nominative). À assouplir si les volumes l'exigent.
5. **Reporting par préfixes** (`CashPositionService`, `EtatsFinanciersService`) reste adossé à la nomenclature de démonstration : à remplacer avec le référentiel officiel.
6. **Base de développement existante** : V49 migre les données en place ; les conteneurs doivent être reconstruits (`docker compose up -d --build backend frontend`). Aucun reset de volume nécessaire.
7. **Isolation tenant toujours applicative** (pas de RLS PostgreSQL) ; chaque nouvelle table doit reproduire les tests d'isolation.
8. **Rebasage des migrations** à planifier avec le référentiel SYSCOHADA officiel (cf. §10).
