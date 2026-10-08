# G3A — Infrastructure TVA indépendante de la réglementation

**Verdict : G3 INFRASTRUCTURE READY.** G3 n'est **pas** DONE : le pack réglementaire Burkina (G2) n'existe pas, la finalisation réglementaire reste **BLOCKED**. Aucune règle burkinabè n'a été codée, aucune convention (« 15 du mois suivant ») n'est utilisée.

## 1. Gates

| Gate | Verdict |
|---|---|
| Fiscal Core | READY |
| VAT Infrastructure (statut, progression, pagination SQL, recherche, filtres, tri, validation en masse, verrouillage optimiste, crédit d'ouverture, constat de cadre) | **READY** |
| VAT Workspace Infrastructure (écran, Cabinet) | **READY** |
| Payment Tracking (suivi interne) | **READY** pour le suivi technique ; **PARTIAL_READY** réglementairement (montant dû = solde déclaré du snapshot, aucune règle de paiement, aucune pénalité) |
| BF Regulatory Pack | **BLOCKED** |
| BF VAT Finalization | **BLOCKED** (sans cadre VERIFIED, `finalizable = false`, raison `REGULATORY_PACK_NOT_VERIFIED`) |

## 2. Ce qui a été fait

* **Échéancier non vérifié supprimé** : `EcheancierTvaGenerator` (15 du mois suivant) est supprimé. `TaxDeadlineGenerator` ne produit une période / une échéance **que** depuis la règle (`periodicity` + `due`) d'un cadre VERIFIED ; sans règle : rien (`DeclarationFiscaleService.genererEcheancierTva` répond 422, `ControlEngine` ne signale plus de déclaration manquante sans règle vérifiée). Tests : cadre sans règle → aucune échéance ; fixture `TEST_ONLY` avec règle → échéance calculée.
* **Évaluateur unique** `VatOperationalStatusEvaluator` : statuts A_PREPARER, EN_PREPARATION, ATTENTION_REQUISE, PRET_POUR_REVUE, EN_REVUE, PRET_A_FINALISER, FINALISE, A_PAYER, PARTIELLEMENT_PAYE, PAYE, CREDIT_A_REPORTER ; progression en 8 étapes (`readySteps`, `totalSteps`, `blockingSteps`), jamais un pourcentage. `EN_REVUE` = période revue mais finalisation encore bloquée. Le portefeuille du cabinet utilise le même évaluateur (`codePortefeuille`).
* **Pagination SQL** (`VatOperationPageQuery`) : filtres, recherche (document, pièce, libellé, tiers, identifiant d'écriture, montant), tri (date, document, montant, statut, tiers) et `LIMIT/OFFSET` en base ; seule la page est lue ; le moteur ne calcule que ces lignes. Les constats actifs sont lus pour la page seulement. Une période FINAL se relit depuis son snapshot (borné, immuable).
* **Validation en masse** : le serveur revalide **chaque** opération (même période, non finalisée, sens de TVA imposant exactement le traitement demandé, aucune décision humaine contraire, aucun constat de conflit) ; seuls `OUTPUT_TAX` et `INPUT_TAX` (déduits du sens comptabilisé) sont acceptés ; tout ou rien ; audit `TRAITEMENT_FISCAL_BULK_MODIFIE`.
* **Verrouillage optimiste** : `tax_period.version` est le jeton de concurrence de la période et de tout ce qu'elle porte (opérations, ajustements, déclaration, paiements, crédit). `expectedVersion` est accepté sur toutes les mutations ; une version périmée donne **409** (« modifiée par un autre utilisateur … Rechargez »). Le jeton est facultatif côté API (appels internes, tests) ; l'écran l'envoie toujours.
* **Suivi interne du paiement** (`tax_payment`, `VatSettlementService`) : déclaré / payé / restant, statuts, paiement partiel, **idempotence** (clé par période : un rejeu ne double jamais le montant ; même clé avec un autre montant = 409), **annulation tracée** (jamais de suppression), refus du dépassement. Aucune API de l'administration, aucun télépaiement.
* **Crédit d'ouverture** (`tax_opening_credit`) : saisi, justifié, avec source ; modification = nouvelle ligne (l'ancienne est marquée remplacée) ; **jamais repris automatiquement** de la période précédente ; **TO_REVIEW** et bloque la finalisation tant qu'un cadre VERIFIED ne prévoit pas le report (`VatRules.carryForward`) ; il n'entre pas dans le solde. `CREDIT_A_REPORTER` n'est actif que si le cadre vérifié prévoit le report.
* **Constat de niveau période** `TAX_FRAMEWORK_NOT_VERIFIED` : un seul par période (jamais par opération), résolu automatiquement quand un cadre VERIFIED est installé et la période recalculée (la revue est alors annulée). Il ne bloque pas la préparation.
* **Finalisation** : toujours bloquée sans cadre VERIFIED (test obligatoire). `finalizable = false` + `finalisationReasons` (codes machine) : le frontend explique, il ne décide pas.
* **Snapshot** : audité — il contient déjà cadre, hash, période, montants, ajustements, contrôles, revue ; le crédit d'ouverture saisi (donnée d'entrée) y est ajouté pour les **nouveaux** snapshots ; les paiements en sont exclus (postérieurs au FINAL). Aucun ancien snapshot n'est modifié (trigger).
* **Cabinet** : un seul indicateur TVA (À préparer, N à traiter, À revoir, Prête, Cadre réglementaire incomplet, Finalisée, À payer, Partiellement payée, Payée, Crédit à reporter) ; « Prête » n'est jamais affichée sans cadre vérifié ; file « À traiter » : opérations à revoir, anomalies, cadre manquant (constats), « TVA à payer : reste X ».
* **Audit** : `PERIODE_FISCALE_STATUT_MODIFIE`, `TRAITEMENT_FISCAL_MODIFIE`, `TRAITEMENT_FISCAL_BULK_MODIFIE`, `AJUSTEMENT_FISCAL_AJOUTE`, `AJUSTEMENT_FISCAL_ANNULE`, `PAIEMENT_FISCAL_ENREGISTRE`, `PAIEMENT_FISCAL_ANNULE`, `CREDIT_OUVERTURE_SAISI`, `CREDIT_OUVERTURE_MODIFIE`.

## 3. Audit du module `fiscalite.paiement` existant

| Question | Réponse |
|---|---|
| Entités | `PaiementFiscal` (tentative de télépaiement d'une `DeclarationFiscale` héritée), `PaiementFournisseur` (e-Syntaxe, Faso Arzeka), `StatutPaiement` |
| Source de vérité | la `DeclarationFiscale` héritée (montant plein, statut PAYEE) — **pas** la période fiscale versionnée |
| Simulation / production | simulation par défaut, aucune API réelle accessible ; confirmation manuelle en mode simulation |
| Lien avec la période fiscale | aucun |
| Audit | aucun |
| Multi-tenant | via `EntrepriseAccessService` |
| Idempotence | aucune (nouvelle tentative = nouvelle ligne) ; pas de paiement partiel ni d'annulation |

**Décision** : non réutilisé (modèle de télépaiement fournisseur, montant plein, sans période ni audit). `tax_payment` est une représentation légère du **suivi interne**. Le module existant reste en l'état (hors périmètre de G3A).

## 4. Performance (`VatPerformanceTest`)

Le **nombre de requêtes est identique** de 10 000 à 50 000 opérations (aucun N+1) :

| Appel | JDBC / Hibernate | 10 000 | 50 000 |
|---|---|---|---|
| Synthèse (période) | 35 / 9 | 279 ms | 1 485 ms |
| Page 0 (SQL, 50 lignes) | 14 / 9 | 167 ms | 497 ms |
| Page profonde (page 99) | 14 / 9 | 171 ms | 494 ms |
| Recherche | 14 / 9 | 158 ms | 1 497 ms |
| Filtre TO_REVIEW | 4 / 9 | 111 ms | 421 ms |
| Recalcul (calcul + constats) | 64 / 16 | 703 ms | 3 077 ms |
| File de travail du cabinet | 5 / 0 | 21 ms | 18 ms |

Vérifié explicitement : la requête de page porte `LIMIT/OFFSET` et la requête de chargement complet de la période n'est jamais exécutée pour une page. Les temps sont indicatifs ; le coût résiduel est l'agrégation SQL des écritures de la période (une seule requête). Le recalcul charge encore toute la période en mémoire (nécessaire au calcul des totaux).

## 5. Tests

* Backend : `VatOperationalStatusEvaluatorTest` (6), `TaxDeadlineGeneratorTest` (5), `VatG3AInfrastructureTest` (10 : pagination / recherche / filtres / tri, validation en masse valide et invalide, version périmée, cadre manquant, paiement partiel / idempotence / annulation, crédit, report, échéances, Cabinet), `VatG3AHttpTest` (contrat HTTP, 409, rôles, multi-tenant), `VatPerformanceTest` (10 000 puis 50 000).
* Angular : `tva.component.spec.ts` (27 tests dont 12 nouveaux : cadre non vérifié, progression, pagination, recherche, filtres, validation en masse, règlement, idempotence, crédit, 409, permissions).
* Playwright : `tva-g3a.spec.ts` (A : collaborateur, recherche, filtre, classification, validation en masse, cadre non vérifié → finalisation bloquée ; B : fixture `TEST_ONLY` vérifiée, paiement partiel, complet, annulation).
* Tous les cadres fictifs sont des **fixtures `TEST_ONLY`** (version préfixée `TEST_ONLY-`, codes `TEST_ONLY_*`), propres au cabinet de test, jamais semés par l'application.

## 6. Limites

* Aucune règle burkinabè : taux, exigibilité, déductibilité, exclusions, périodicité, échéance, formulaire, report, avoirs, régularisation après FINAL sont **UNVERIFIED** (voir `burkina-faso/vat-workflow.md`).
* Le jeton de version est facultatif côté API (les mutations sans `expectedVersion` ne sont pas contrôlées) ; l'écran l'envoie toujours.
* Les opérations d'une période FINAL sont paginées en mémoire depuis le snapshot (borné) ; l'ajustement ne propose que les 100 premières opérations (à revoir d'abord).
* Le crédit d'ouverture n'est pas pris en compte dans le solde (aucune règle de report) ; la période précédente peut être référencée mais pas reprise.
* La validation en masse ne confirme que `OUTPUT_TAX` / `INPUT_TAX` ; il n'existe pas encore de notion d'identifiant de règle (`ruleId`) ni de preuve documentaire (Document Hub) par opération.
* Pas de gestion des cadres dans l'interface (API seulement) ; pas de télédéclaration ni de télépaiement.

## 7. Ce qui attend toujours G2

Un pack réglementaire VERIFIED (avec sources, version, date d'effet, `ruleId`) pour : taux, champ d'application, fait générateur / exigibilité (`taxDate`), déductibilité et exclusions, prorata, périodicité, échéance légale, formulaire officiel (lignes et codes), report / crédit, avoirs et régularisations. Tant qu'il n'existe pas : G3 reste **PAS DONE**.

## G3A.1 — Closeout : concurrence, isolation, frontière déclaration / règlement

**Modèle de version.** `tax_period.version` est le jeton agrégé de la période et de tout ce qu'elle porte. INVARIANT (`PeriodVersion.bumpOnce`) : toute mutation fonctionnelle (classification, validation en masse, ajout / annulation d'ajustement, crédit d'ouverture, préparer / revoir / rouvrir / finaliser, paiement, annulation de paiement, recalcul qui change les données) incrémente la version **exactement une fois par transaction** ; les lectures et les refus ne la consomment jamais. Test : `VatG3A1HardeningTest.versionAgregee_*`.

**`expectedVersion` obligatoire côté API.** Le contrôleur exige `expectedVersion` sur toute mutation utilisateur : classification, validation en masse, ajustement (ajout, annulation), crédit d'ouverture, paiement, annulation de paiement, préparer, revoir, rouvrir, finaliser. Absence : **422** ; version périmée : **409** (« modifiée par un autre utilisateur … Rechargez ») ; version correcte : mutation et version N+1. `recalculer` (idempotent) reste facultatif. Les services internes (tâche proactive, tests de service) n'ont pas à fournir de version : ils s'appuient sur le verrou de ligne `for update` de la période.

**Concurrence prouvée avec de vraies transactions parallèles** : deux classifications, deux ajustements ou deux crédits avec la même version → exactement un gagne, l'autre reçoit 409, aucune écriture perdue ; deux paiements avec la même clé et le même montant → un seul paiement métier ; deux paiements de clés différentes dont la somme dépasse le reste à payer → un seul aboutit (le reste est calculé sous verrou).

**Idempotence du paiement.** Même clé + même montant : même résultat logique, aucun second paiement, aucune version consommée. Même clé + autre montant : 409 sans aucune mutation partielle. Annulation : tracée (motif, date, acteur), jamais de DELETE, montants et statut de règlement recalculés ; une seconde annulation est refusée.

**Frontière FINAL / règlement.** Deux concepts distincts : le **statut de déclaration** (`TaxPeriod.status`, FINAL) et le **statut de règlement** (`payment.status` : A_PAYER, PARTIELLEMENT_PAYE, PAYE). PAYE n'est jamais un statut de déclaration. Après FINAL sont **refusés** : classification, validation en masse, ajustement, crédit d'ouverture, préparer / revoir / rouvrir, double finalisation (409). Le **règlement** (paiement, annulation) reste autorisé : il appartient au workflow post-déclaration.

**Snapshot et paiement.** Le snapshot FINAL représente ce qui a été déclaré : un paiement ou son annulation ne modifient ni le snapshot (id, hash, payload), ni `frameworkVersion`, ni `finalized_at` ; il n'existe jamais de second snapshot.

**Cabinet.** Le libellé composite est affiché (« Finalisée — À payer / Partiellement payée / Payée ») ; les deux statuts restent séparés dans le backend. La file « À traiter » porte « TVA à payer : reste X » tant qu'il reste un montant, et la tâche disparaît une fois la période soldée.

**Isolation des tests et horloge.** Cause racine de l'échec de `CollecteWorkflowTest` : `RelanceCollecteService.evaluer(now)` traitait **toutes** les demandes dues de **tous** les cabinets ; avec le temps, d'anciennes demandes de tests précédents devenaient « dues » et faussaient les compteurs. Correction : `evaluer(now, entrepriseId)` restreint la lecture, la modification et le comptage au seul dossier ciblé (la tâche planifiée appelle toujours la version globale) ; les tests créent et vérifient uniquement leurs propres données, sans nettoyage global ni assertion relâchée. Horloge : `TimeConfig` fournit **l'unique** `Clock` (système en production) ; `CabinetWorkspaceService`, `VatWorkspaceService` et `CollecteScheduler` l'utilisent ; `CabinetClockTest` fixe l'horloge à 2031-01-10 et vérifie le classement des échéances indépendamment du jour réel.

**Gates inchangés** : BF Regulatory Pack BLOCKED ; BF VAT Finalization réglementaire BLOCKED.

**Performance (G3A.1) — plan générique PostgreSQL.** Le rejeu du benchmark a révélé un vrai défaut, pas un artefact : la requête de mouvement d'un compte (`VatOperationExtractor.mouvement`), exécutée plus de 5 fois, bascule en plan générique (JDBC prépare côté serveur au 6e appel) ; PostgreSQL combinait alors `idx_ligne_ecriture_compte` et `idx_ligne_ecriture_ecriture` par BitmapAnd **pour chaque écriture** de la période (bitmap du compte reconstruit 50 000 fois) : 29 s pour 50 000 écritures (synthèse 47–110 s, recalcul 93–128 s). Correction : index composite `idx_ligne_ecriture_compte_ecriture (compte_id, ecriture_id)` (migration V68) → la même requête passe de 29 s à 0,23 s en plan générique. Mesures après correction (nombre de requêtes inchangé : 35 JDBC + 9 Hibernate pour la synthèse, 14 + 9 pour une page, 4 pour le filtre TO_REVIEW, 64 + 16 pour le recalcul, 5 pour la file de travail) :

| Appel | 10 000 | 50 000 |
|---|---|---|
| Synthèse | 435 ms | 1 166 ms |
| Page 0 / page profonde | 314 / 217 ms | 650 / 687 ms |
| Recherche | 223 ms | 1 089 ms |
| Filtre TO_REVIEW | 161 ms | 946 ms |
| Recalcul | 729 ms | 2 563 ms |
| File de travail | 21 ms | 20 ms |

Le test d'insertion en masse rafraîchit aussi les statistiques (`analyze`) comme le ferait l'autovacuum en production.
