# Rapport F1.5 — Fondation numérique des états financiers (sans coder F2)

**Statut : F0 = DONE · F1 = DONE · F1.5 = DONE · F2 = BLOCKED** (gate réglementaire : voir `docs/syscohada-official-matrix.md`). Aucune rubrique SYSCOHADA, aucun mapping, aucune formule, aucune note, aucune donnée réglementaire n'a été créée ni modifiée ; le PDF n'a pas été relu.

## 1. Sémantique clôture / ouverture retenue
Colonne `ecriture_comptable.nature` (V61) : `BUSINESS`, `ADJUSTMENT`, `CLOSING`, `OPENING`, fixée par le moteur d'opérations selon le type d'opération (`NatureEcriture.pour`), jamais déduite d'un journal, d'un libellé ou d'un préfixe de compte. L'écriture de clôture **reste une vraie écriture du grand livre** (aucune suppression, aucune double vérité) ; seule la *vue* décide si elle compte. `OPENING` est réservée : le mécanisme actuel ne poste pas d'à-nouveaux (les soldes de bilan se reportent par cumul historique). Détails et table des vues : `docs/reporting-view-policy.md`.

## 2. Moteur canonique
`AccountingBalanceEngine` (`comptabilite/balance/`) : une seule source de soldes, une agrégation SQL par appel. Aucun autre moteur n'a été créé ; `BalanceService`, `GrandLivreService`, `EtatsFinanciersService` (legacy) et la clôture l'utilisent. Contrat : `balances / balance / balancesExercice / avecComparatif / datasetVersion`, sortie `AccountBalance`. Vues : `LEGAL_LEDGER`, `PERIOD_ACTIVITY`, `WORKING_INCLUDING_DRAFTS`.

## 3. Ouverture / période / clôture
Ouverture = somme directe de l'historique du périmètre strictement avant `from` (jamais « solde courant − mouvements »). Mouvements = `[from ; to]` bornes **incluses**. Clôture = ouverture + mouvements. `BigDecimal` partout, aucun arrondi nécessaire. Invariants testés : ouverture + mouvements = clôture ; Σ débit = Σ crédit ; clôture débit = clôture crédit.

## 4. N / N-1
`avecComparatif` utilise l'exercice **précédent réel** (fin la plus récente avant le début de N) : testé avec un exercice précédent de **18 mois**. Pas d'exercice précédent → comparatif vide. Continuité clôture N-1 = ouverture N vérifiée compte par compte (test E2E avec clôture réelle de N-1).

## 5. Grand livre — 6. Balance
Tous deux sur `LEGAL_LEDGER`, ouverture et clôture issues du moteur ; le solde progressif de la dernière ligne = la clôture du moteur (testé sur tous les comptes). Contrats d'API / DTO inchangés.

## 7. N+1 supprimés
La balance générale faisait 2 requêtes par compte ; le bilan et le compte de résultat legacy 1 par compte ; la clôture 1 par compte de classe 6/7. Tout passe par une agrégation : **2 requêtes** (accès + agrégation) quel que soit le nombre de comptes (test : 2 comptes vs 61 comptes, même nombre de requêtes ≤ 6).

## 8. Bug « CR = 0 après clôture » — corrigé au niveau fondation
Cause racine : l'écriture de clôture (datée du dernier jour) était recomptée dans l'activité de la période. Correction : nature `CLOSING` + vue `PERIOD_ACTIVITY`. Test central : vente 1 000 000 → activité 1 000 000 avant **et** après clôture ; grand livre légal contient l'écriture de clôture (2 lignes sur le compte 701, solde nul) ; le compte de résultat legacy ressort à 1 000 000 après clôture. Les requêtes d'activité `mouvementNetPeriode`, aperçu financier (`mouvementNetClasseSurPeriode`) et prévision de trésorerie excluent désormais `CLOSING`/`OPENING`.

## 9. Annulation de facture et conséquence comptable
`annulerFacture` produit désormais la **contrepassation** de l'écriture de validation : mêmes comptes, mêmes tiers, débit/crédit échangés, via `AccountingOperationEngine` (nouveau type `ANNULATION_FACTURE`, nature `BUSINESS`). Aucune écriture validée n'est supprimée ni modifiée ; une facture sans écriture liée (donnée ancienne) n'a rien à contrepasser. Refusée (409) s'il existe un **avoir validé** (la comptabilité est déjà corrigée) ; le garde-fou F0.1 (allocations actives → refus) est inchangé et passe avant. Le stock continue d'être réintégré : test facture + stock + comptabilité (compte 311 revenu à 100 000, 701 et clients à zéro). La contrepassation est datée du jour (l'exercice de la facture a pu être clôturé).

## 10. Idempotence
Source `FACTURE/<id>/ANNULATION` (unique côté moteur) + statut de la facture : une seconde annulation est refusée (409) et ne crée **aucune** seconde contrepassation (comptage des propositions `ANNULATION_FACTURE` = 1).

## 11. Lineage
facture → proposition `VENTE` (source `FACTURE/<id>/VALIDATION`) → écriture ; facture → proposition `ANNULATION_FACTURE` (source `FACTURE/<id>/ANNULATION`) → écriture de contrepassation. Deux propositions avec écriture par facture annulée (testé).

## 12. Multi-tenant
Filtre `compte.entreprise` dans les requêtes du moteur ; exercice d'une autre entreprise → 404 ; compte d'une autre entreprise → 404 au grand livre / vide au moteur ; E2E : l'autre entreprise ne voit aucun solde.

## 13. Performance (PostgreSQL 18 local, `ANALYZE` avant mesure, `-Dbenchmark=true`)
| Comptes | Écritures | Requêtes SQL | Durée |
|---|---|---|---|
| 100 | 10 000 | 2 | 108 ms |
| 1 000 | 50 000 | 2 | 311 ms |
| 1 000 | 100 000 | 2 | 733 ms |

Mode normal (CI) : échelle réduite, invariants et nombre de requêtes uniquement (aucune durée). `BalanceEngineVolumeTest`.

## 14. API impactées
Balance générale et grand livre : mêmes contrats. Bilan / compte de résultat legacy : mêmes contrats, mais le résultat n'est plus nul après clôture. Annulation de facture : nouveau 409 « avoir déjà valide » ; nouvelle écriture de contrepassation. **Aucun changement frontend.**

## 15. Reporting legacy
`EtatsFinanciersService` est marqué `@Deprecated` (« ne pas utiliser comme source réglementaire ») et ne calcule plus aucun solde lui-même (rebasé sur le moteur). Il n'est **pas supprimé** : l'écran États financiers actuel le consomme et aucune rubrique officielle n'existe encore pour le remplacer. Ses défauts de classement (66/77 « financier » par préfixe, classe 8 ignorée, pas de N-1, aucune rubrique officielle) subsistent et sont documentés ; F2 le remplacera.

## 16. Tests ajoutés
* `AccountingBalanceEngineTest` (15) : invariants, bornes incluses, brouillon vs légal, période invalide, vente/achat + grand livre + balance, amortissement (ADJUSTMENT), clôture (activité inchangée, grand livre légal, résultat legacy), clôture N → ouverture N+1, N-1 irrégulier (18 mois), premier exercice, annulation (contrepassation, lineage, idempotence), annulation avec stock, multi-tenant, nombre de requêtes, empreinte technique.
* `ReportingFoundationE2ETest` (1) : N-1 → clôture → N (achat, vente, paiement, stock, immobilisation, dotation, annulation), balance/grand livre/N-N-1/ouvertures/tenant.
* `BalanceEngineVolumeTest` (3) : petite / grande / très grande échelle.
* Tests unitaires migrés vers le moteur : `EtatsFinanciersServiceTest`, `ClotureExerciceServiceTest`, `FacturationServiceTest`.

## 17. Résultats de tests
Voir « Résultats finaux » ci-dessous. Angular : non touché (aucun contrat changé), `npm run build` non relancé. ai-service : non touché, pytest non relancé.

## 18. Limites
* **La clôture ne solde que les classes 6 et 7** (logique historique) : les comptes **HAO (classe 8)** restent ouverts après clôture. Choisir leur compte de résultat est une décision réglementaire : signalé, non corrigé.
* Le plan de comptes de démonstration diverge du plan officiel (F1) ; non touché.
* L'empreinte (`DatasetVersion`) ne détecte pas une mutation sans effet sur nombres, totaux et dates de création (voir `docs/accounting-balance-engine.md` §6, décision de ne pas incrémenter un compteur de version).
* L'écriture de contrepassation est datée du jour de l'annulation : si l'exercice de la facture est clôturé, l'effet comptable tombe dans l'exercice courant (correct comptablement, à connaître).
* Les écritures existantes ont été reclassées par la migration à partir de leur proposition ; une écriture de clôture sans proposition (impossible en production, la clôture passe par le moteur) resterait `BUSINESS`.

## 19. Confirmation
**F2 = BLOCKED.** Aucune donnée `PARTIAL` ou `UNVERIFIED` n'est entrée dans le code.

## Résultats finaux
Dernière exécution complète après la dernière modification : `./mvnw -o test` → **832 tests, 0 échec, 0 erreur, 3 ignorés, BUILD SUCCESS** (baseline 813 + 19 nouveaux). Benchmarks (`-Dbenchmark=true`) : `BalanceEngineVolumeTest` 3/3. Angular et ai-service non touchés : non relancés.
