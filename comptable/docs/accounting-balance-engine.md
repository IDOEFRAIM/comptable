# AccountingBalanceEngine — source unique des soldes comptables (phase F1.5)

Cette fondation précède les états financiers. **Elle ne contient aucune rubrique SYSCOHADA** : F2 reste bloqué (voir `docs/syscohada-official-matrix.md`). Elle répond uniquement à : *quel est le solde exact de ce compte, sur quelle période, avec quelle ouverture, quels mouvements, quelles écritures incluses, et peut-on le reproduire plus tard ?*

```
Écritures comptables
        ↓
AccountingBalanceEngine  ← une agrégation SQL, jamais une requête par compte
        ↓
AccountBalance (ouverture / mouvements / clôture) dans une ReportingView
        ↓
N / N-1  ·  DatasetVersion (empreinte)
        ↓
[F2/F3 plus tard] mapping réglementaire versionné → états financiers
```

Code : `comptabilite/balance/` (`AccountingBalanceEngine`, `ReportingView`, `AccountBalance`, `ExerciseBalances`, `DatasetVersion`), requêtes `LigneEcritureRepository.agregerSoldes*`.

## 1. Contrat

| Méthode | Rôle |
|---|---|
| `balances(entrepriseId, from, to, view)` | soldes de tous les comptes ayant une écriture du périmètre jusqu'à `to`, triés par numéro |
| `balance(entrepriseId, compteId, from, to, view)` | un compte (grand livre) ; vide si aucune écriture |
| `balancesExercice(entrepriseId, exerciceId, view)` | bornes de l'exercice (404 si l'exercice est d'une autre entreprise) |
| `avecComparatif(entrepriseId, exerciceId, view)` | exercice N + exercice précédent **réel** (N-1) dans la même vue |
| `datasetVersion(entrepriseId, exerciceId, from, to, view)` | empreinte technique des données (voir §6) |

`AccountBalance` : `accountId, accountNumber, accountLabel, openingDebit/Credit, periodDebit/Credit, closingDebit/Credit, netBalance` (+ `openingNet()`, `periodNet()`). Les soldes d'ouverture et de clôture sont **nets** (un seul côté non nul) ; les mouvements sont les cumuls de la période.

Le moteur ne vérifie pas les droits d'accès (l'appelant le fait : `EntrepriseAccessService`). L'isolation multi-tenant est assurée dans la requête (`compte.entreprise = :entrepriseId`).

## 2. Définitions

* **Ouverture** = somme directe (débit − crédit) de **toutes** les écritures du périmètre de la vue datées **strictement avant** `from`. Jamais « solde courant − mouvements ».
* **Mouvements** = cumuls débit / crédit des écritures datées dans `[from ; to]`, **bornes incluses**.
* **Clôture** = ouverture + mouvements.
* Aucun arrondi : les sommes de montants à 2 décimales n'en demandent pas (`BigDecimal`, jamais `double`/`float`).

Invariants (testés) : `ouverture + mouvements = clôture` par compte ; `Σ débit période = Σ crédit période` et `Σ clôture débit = Σ clôture crédit` sur un jeu complet d'écritures équilibrées ; le solde final du grand livre = la clôture du moteur ; la balance générale = le moteur.

## 3. Vues

Voir `docs/reporting-view-policy.md` : `LEGAL_LEDGER`, `PERIOD_ACTIVITY`, `WORKING_INCLUDING_DRAFTS`.

## 4. N et N-1

`avecComparatif` prend l'exercice dont la **date de fin précède le plus récemment** le début de N (`findFirstByEntrepriseIdAndDateFinLessThanOrderByDateFinDesc`), pas `date − 1 an` : un premier exercice de 18 mois ou un exercice court est géré (test : exercice précédent de 18 mois). Le domaine ne suppose pas 365 jours (`ExerciceComptable` porte deux dates libres). Pas d'exercice précédent → comparatif vide (jamais inventé).

**Continuité** (préparation de l'article 34, au niveau des comptes) : pour tout compte, clôture N-1 = ouverture N, parce que les soldes de bilan se **reportent par cumul historique** — il n'existe pas d'écriture d'à-nouveaux, la nature `OPENING` est réservée et non produite. Les comptes de gestion soldés par la clôture repartent à zéro. Aucun « compte reportable » n'est déduit du numéro : le mécanisme est celui de `ClotureExerciceService`.

## 5. Grand livre et balance générale

`GrandLivreService` (ouverture et clôture du moteur, écritures de la période avec solde progressif) et `BalanceService` (une ligne par compte, totaux par colonne) consomment le moteur en vue `LEGAL_LEDGER`. Contrats d'API et DTO **inchangés** (aucun impact frontend). Les N+1 de la balance (une requête par compte × 2) sont supprimés : **2 requêtes SQL** quel que soit le nombre de comptes (accès + agrégation).

## 6. Empreinte technique (`DatasetVersion`) — pas de version d'entreprise incrémentée

Pour qu'un futur `FinancialStatementSnapshot` puisse prouver de quelles données il provient, `datasetVersion` renvoie : `generatedAt`, nombre de lignes, nombre d'écritures, totaux débit/crédit, date de création de la dernière écriture, et un SHA-256 de ces valeurs + (entreprise, vue, bornes). Deux calculs sur des données identiques donnent la même empreinte ; une écriture ajoutée, validée ou contrepassée dans le périmètre la change (testé, y compris : un brouillon ne change pas l'empreinte de la vue légale).

**Décision (P9) — on n'ajoute PAS de compteur de version incrémenté à chaque mutation** : il imposerait une écriture sur une ligne chaude (entreprise ou exercice) à chaque écriture comptable, en concurrence avec le verrou de clôture de F0.2, pour un gain nul puisque l'empreinte dérivée est exacte pour tout ce qui influence un solde. Limite assumée : une mutation sans effet sur les nombres, totaux ni dates de création (changer un libellé) ne change pas l'empreinte — sans importance pour des montants. Ce n'est **pas** une version réglementaire SYSCOHADA.

## 7. Nature des écritures

Colonne `ecriture_comptable.nature` (V61) : `BUSINESS`, `ADJUSTMENT`, `CLOSING`, `OPENING`, portée par la donnée, jamais déduite d'un code de journal, d'un libellé ou d'un préfixe. Le moteur d'opérations la fixe à partir du type d'opération (`NatureEcriture.pour`) : clôture → `CLOSING` ; régularisations, provisions, dépréciations, dotations aux amortissements, régularisation d'inventaire → `ADJUSTMENT` ; le reste (achat, vente, règlement, avoir, stock, immobilisation, annulation de facture, saisie manuelle) → `BUSINESS`. Les écritures existantes sont reclassées par la migration à partir de leur proposition.

## 8. Performance (mesures sur PostgreSQL 18 local, voir `docs/benchmarks.md`)

Tests normaux (CI) : invariants déterministes + nombre de requêtes (2, indépendant du volume), jamais une durée. Benchmark (`-Dbenchmark=true`) : voir §Mesures du rapport `docs/reporting-pre-f2-report.md`.

## 9. Ce que le moteur ne fait pas

Aucun classement par rubrique, aucune formule de poste, aucune note, aucun mapping Bilan/CR/TFT. Il ne traite pas non plus les écritures de clôture HAO (voir `docs/reporting-pre-f2-report.md` §Limites).
