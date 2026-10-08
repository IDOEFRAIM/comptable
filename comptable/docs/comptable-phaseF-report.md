# Rapport de la phase F — États financiers SYSCOHADA fiables, explicables et auditables

**Statut : EN COURS — F0 = DONE, F1 = DONE, F1.5 (fondation numérique) = DONE (voir `docs/reporting-pre-f2-report.md`), matrice réglementaire = IN_PROGRESS (`docs/syscohada-official-matrix.md`), F2 = BLOCKED (gate réglementaire, validation utilisateur requise).**

## 1. Baseline

Départ : Maven 805 tests, 0 échec, 0 erreur, 3 ignorés ; `npm run build` OK ; pytest 328.

## 2. Dettes F0

### F0.1 — Annulation d'une facture avec affectations
`FacturationService.annulerFacture` refuse (409) une facture portant des affectations **actives** (`StatutPaiementFactureService.affectationsActives`) : partielles ou totales. Rien n'est désaffecté automatiquement ; le message dit quoi faire (« Annulez d'abord ces affectations (Banque > Affectations) »). Affectations uniquement **annulées** (REVERSED) : annulation autorisée, historique conservé. Autre entreprise : 404. Tests : `FactureAnnulationAllocationTest` (5).
**Limite consignée** : l'annulation ne contre-passe pas l'écriture comptable (statut + stock seulement) ; voir `docs/financial-statements-audit-F1.md` §3.9.

### F0.2 — Atomicité pré-clôture → clôture
Mécanisme : la clôture prend le verrou **exclusif** de la ligne d'exercice (`SELECT … FOR UPDATE`) puis **recalcule** ses contrôles ; toute écriture passe par `ExerciceOuvertGuard`, qui lit le statut réel de l'exercice sous verrou **partagé** (`SELECT … FOR SHARE`, scalaire natif, jamais l'entité en cache). Conséquences : une clôture attend les écritures en cours puis les voit ; une écriture qui arrive pendant la clôture attend puis voit l'exercice clôturé. Stratégies écartées : version d'exercice / révision comptable (colonne et incrément à chaque écriture, plus invasif), timestamp + validation (fenêtre résiduelle).
Tests de concurrence PostgreSQL (`ClotureAtomiciteTest`, vrais threads) : READY puis écriture en cours → la clôture **attend** puis est **refusée** (brouillon bloquant) ; écriture pendant la clôture → attend puis refusée (exercice clôturé) ; deux clôtures simultanées → une seule réussit (200 / 409). **Contre-épreuve** : sans le verrou partagé, deux de ces trois tests échouent.

### F0.3 — Benchmarks reproductibles
Voir `docs/benchmarks.md`. Tests de volume à échelle réduite par défaut, durées et grandes échelles seulement avec `-Dbenchmark=true`, `ANALYZE` avant mesure.

## 3. Audit du reporting existant (F1)

Voir `docs/financial-statements-audit-F1.md` : matrice des composants, recherche exhaustive des hardcodes et leur classification, défauts constatés (dont **compte de résultat d'un exercice clôturé = 0**, **référentiel de démonstration divergent du plan officiel**, classes HAO ignorées, pas de N-1, N+1), décisions.

## 4. Source réglementaire

Voir `docs/syscohada-financial-statements-reference.md` : Acte uniforme (articles 25-34) **vérifié** ; plan comptable général, modèles des états et liste des notes consolidés dans `docs/syscohada-official-matrix.md` (PDF officiel archivé ; statuts VERIFIED/PARTIAL/UNVERIFIED). **Aucune rubrique n'est codée.**

## 3 bis. F1.5 — Fondation numérique (sans coder F2)

`AccountingBalanceEngine` (source unique des soldes, vues `LEGAL_LEDGER` / `PERIOD_ACTIVITY` / `WORKING_INCLUDING_DRAFTS`), nature explicite des écritures (`BUSINESS`/`ADJUSTMENT`/`CLOSING`/`OPENING`, V61), correction du résultat nul après clôture, N / N-1 sur l'exercice précédent réel, grand livre et balance sur le même moteur (N+1 supprimés), annulation de facture par contrepassation idempotente. Voir `docs/accounting-balance-engine.md`, `docs/reporting-view-policy.md`, `docs/reporting-pre-f2-report.md`. **Aucune rubrique SYSCOHADA codée.**

## 3 ter. Gate réglementaire levé partiellement — F2, F3 (CR, Actif partiel), F6, F7 (structure), F8 (catalogue)

Paquet réglementaire intégré (`docs/syscohada-official-matrix.md`, statuts + provenance). Implémenté (package `etatsfinanciers`) : modèle de définition versionné et immuable (`FinancialStatementDefinition`, fichiers JSON versionnés, empreinte SHA-256, validation au démarrage : codes/ordres uniques, formules, aucune règle non `VERIFIED`, **aucun chevauchement de règles**), `FinancialStatementMappingResolver` (moteur unique, `UNMAPPED`/`AMBIGUOUS`), `FormulaEvaluator`, `IncomeStatementService` (compte de résultat complet N / N-1, 42 lignes, XA…XI), `BalanceSheetService` (Actif partiel, Passif structure seule, état `INCOMPLETE`), `CashFlowStatementService` (structure, formules, invariants `ZA + ZG = ZH`), `NotesCatalogueService` (46 notes R4), `StatementDrillDownService` (rubrique → comptes → solde → écritures → lineage), `StatementControlPublisher` (`UNMAPPED_ACCOUNT`, `AMBIGUOUS_MAPPING`, `RESULT_MISMATCH`, `CASH_FLOW_MISMATCH` → Findings), snapshot FINAL immuable (V62, trigger). Voir le rapport de l'itération. Corrections de la relecture directe (p. 1068-1069) intégrées : XA = TA + RA + RB `VERIFIED`, BJ sauf 478 (478 → BU), AJ = 22 / 282, 292. **La Phase F n'est pas terminée** : le Passif (mapping direct) et les Notes nécessaires au dossier FINAL ne sont pas couverts.

## 3 quater. Closeout réglementaire (2026-10-04)

Lecture directe des pages du Bilan, du TFT, des Notes et de la Fiche R4 : `docs/syscohada-regulatory-closeout.md`. Bilan Actif PARTIAL_READY (14 postes complets), Bilan Passif PARTIAL_READY (22 postes dérivés des Notes + plan), TFT PARTIAL, Notes : catalogue READY / automatisation PARTIAL (proposition). **Erreur trouvée dans la définition F2** : BH dépréciation 490, pas 499 (non corrigée : mission sans code). La Phase F n'est pas terminée.

## 4 bis. Implémentation F5 / F7 / F8 et pré-finalisation (2026-10-05)

Détail : `docs/syscohada-phaseG-implementation.md`. Gates : **F5 PARTIAL_READY, F7 PARTIAL_READY, F8 PARTIAL** (voir `syscohada-regulatory-closeout.md` §10). Le Bilan est calculé (BZ = DZ, CJ = XI), le TFT reconstitue FC, FB-FE, FF-FJ et FK-FQ à partir des soldes et des événements de trésorerie qualifiés, les Notes 5, 10, 21, 22, 23, 24, 29, 30 et 34 sont générées avec statuts et manques structurés. La Phase F n'est pas terminée : cinq termes de la CAFG restent `PARTIAL` et les contenus manuels des Notes ne sont pas stockés.

## 4 ter. F8.1 — Espace de travail des Notes (2026-10-05)

Détail : `docs/syscohada-notes-workspace.md`. Les Notes ont désormais un stockage persistant (données générées et manuelles séparées, applicabilité décidée et tracée, revue invalidée à toute modification, audit, verrouillage optimiste, rôles, tenant) et le snapshot `DOSSIER_COMPLET` fige leur contenu réel. Gate F8 : PARTIAL_READY. Le chemin de succès n'est plus « toutes les notes non applicables » : une note automatique, une mixte, une manuelle et une non applicable validée sont finalisées de bout en bout.

## 4 quater. F9/F10 — Espace de révision et dossier financier final (2026-10-05)

Détail : `docs/financial-review-workspace.md`. Écran Angular « Dossier financier » (route `/entreprises/:id/etats-financiers`, remplace l'ancien écran) : vue d'ensemble, Compte de résultat, Bilan, TFT, Notes annexes, Contrôles (travail par exception, qualification des flux de trésorerie), Pourquoi ?, Version finale (pré-finalisation, clôture, finalisation, visionneuse du snapshot, exports BROUILLON et FINAL). Backend : `FinancialDossierService` (readiness, `finalizable`), `CashFlowQualificationService` (enum `CashFlowEvent` validé côté serveur), `FinancialDossierExportService` (HTML imprimable), rôles (`DossierRoles` : finalisation EXPERT_COMPTABLE). Parcours réel validé dans le navigateur (connexion, qualification d'un flux, saisie de notes, revue, invalidation de la revue, validation des suggestions, clôture, finalisation, lecture du snapshot, exports). **La Phase F n'est pas déclarée terminée** : voir les limites dans `financial-review-workspace.md`.

## 4 quinquies. Hardening final et closeout (2026-10-05)

Détail : `docs/financial-review-workspace.md`. Réalisé : clôture migrée sur le moteur de balance canonique et ancien reporting entièrement supprimé ; synthèse du dossier optimisée (35 → 14 requêtes, 6,6 s → 2,3 s sur 30 000 lignes) par mémoïsation transactionnelle ; tests unitaires Angular (Karma + Jasmine, 19 tests) ; E2E navigateur Playwright automatisé sur le parcours complet jusqu'au snapshot FINAL ; bug de concurrence corrigé (création simultanée des lignes de notes) ; tests de hash du snapshot, d'audit, de N-1 réel, de double finalisation et de multi-tenant. **Verdict Phase F : voir `financial-review-workspace.md` et le rapport de mission.**

## 5 à 30

À venir (F2 modèle versionné, F3 mapping, F4 balance, F5-F8 états, F9-F11 moteur et contrôles, F12-F13 écran et dossier, F14-F17 audit, tests, documentation).
