# Politique des vues de reporting (phase F1.5)

Une **vue** dit quelles écritures entrent dans un calcul de soldes. Table unique de vérité : `ReportingView` (code) ; aucune règle implicite dans les services.

## 1. Audit des écritures de clôture (origine du bug « résultat = 0 après clôture »)

`ClotureExerciceService` solde les comptes de classes 6 et 7 : pour chaque compte, le mouvement net de l'exercice est repris en contrepartie sur le compte de résultat (`RESULTAT_BENEFICE` / `RESULTAT_PERTE`, résolu par `AccountingAccountResolver`), via `ClotureRule` et `AccountingOperationEngine` (type `CLOTURE_EXERCICE`, source `EXERCICE/<id>/CLOTURE`, journal « opérations diverses », date = fin d'exercice, VALIDEE). Aucune écriture d'à-nouveaux n'est postée : les comptes de bilan se reportent par cumul historique.

* **Avant** : le calcul d'activité (`mouvementNetPeriode`, `EtatsFinanciersService`, aperçu financier) additionnait *toutes* les écritures validées de la période, **y compris** l'écriture de clôture datée du dernier jour : produits et charges s'annulaient, le résultat tombait à 0 pour tout exercice clôturé.
* **Cause racine** : le modèle n'avait aucun attribut pour dire qu'une écriture est technique. Le contourner par `if (journal == CLOTURE)` aurait reposé sur un code ou un libellé (l'écriture de clôture est dans un journal « opérations diverses » partagé).
* **Correction** : colonne `nature` (V61, `NatureEcriture`) fixée par le moteur d'opérations ; la clôture reste une vraie écriture du grand livre ; seule la **vue** décide si elle compte.

| Nature | Produite par | Représente |
|---|---|---|
| `BUSINESS` | achat, vente, règlement, avoir, stock, immobilisation (acquisition), annulation de facture, saisie manuelle | activité courante |
| `ADJUSTMENT` | régularisation (+ reprise), régularisation d'inventaire, provision, dépréciation, dotation aux amortissements | inventaire de fin de période |
| `CLOSING` | clôture d'exercice | détermination du résultat — technique |
| `OPENING` | *(réservée, non produite)* | à-nouveaux — le mécanisme actuel n'en poste pas |

## 2. Table des vues

| Vue | VALIDEE | BROUILLON | BUSINESS | ADJUSTMENT | CLOSING | OPENING | Usage |
|---|---|---|---|---|---|---|---|
| `LEGAL_LEDGER` | oui | non | oui | oui | **oui** | oui | grand livre, balance générale, bilan (soldes de clôture), FEC, tout document officiel |
| `PERIOD_ACTIVITY` | oui | non | oui | oui | **non** | non | mesure de l'activité économique d'une période (futurs compte de résultat / flux) ; solde de bilan **avant** détermination du résultat |
| `WORKING_INCLUDING_DRAFTS` | oui | **oui** | oui | oui | oui | oui | vue de travail, jamais pour un document officiel (`officielle() == false`) |

Pour chaque vue, l'ouverture suit le **même filtre** que les mouvements.

## 3. Quelle vue pour quel besoin

* **Grand livre légal, balance générale** : `LEGAL_LEDGER` — la clôture y figure (compte de produit soldé, résultat porté en classe 1).
* **Mesure de l'activité économique de N** (même après clôture) : `PERIOD_ACTIVITY` — le chiffre d'affaires reste 1 000 000 après la clôture.
* **États financiers futurs** : écritures **validées uniquement** ; soldes patrimoniaux de clôture en `LEGAL_LEDGER`, activité en `PERIOD_ACTIVITY` ; les deux vues se complètent (le résultat de `PERIOD_ACTIVITY` = ce que la clôture a transféré en classe 1).
* **Bilan d'ouverture N+1** : `LEGAL_LEDGER` — clôture N = ouverture N+1 pour tous les comptes reportés (aucun compte reportable n'est déduit du numéro).
* **Brouillons** : jamais dans un document officiel ; seule la vue de travail les inclut, explicitement.

## 4. Requêtes d'activité existantes alignées

`mouvementNetPeriode`, `mouvementNetClasseSurPeriode` (aperçu financier du tableau de bord) et `rechercherLignesClasseSurPeriode` (prévision de trésorerie) excluent désormais `CLOSING` et `OPENING` : leur sémantique était « activité de la période » ; elles étaient fausses après une clôture. Les requêtes de **solde** (`soldeNetAvant`, trésorerie, rapprochement bancaire) restent légales, toutes natures : la clôture ne touche que des comptes de gestion.

## 5. Limites connues

* La clôture ne solde que les classes 6 et 7 (logique historique de `ClotureExerciceService`). Les comptes **HAO (classe 8)** ne sont pas soldés par la clôture : décision réglementaire (compte de résultat cible) hors périmètre tant que la matrice n'est pas validée — signalé, non corrigé.
* `OPENING` est réservée : si une écriture d'à-nouveaux devait un jour être postée, elle serait exclue de `PERIOD_ACTIVITY` sans autre changement.
