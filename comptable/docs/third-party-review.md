# Revue des comptes de tiers (clients et fournisseurs)

Phase E, investigation `TIERS` (`TiersInvestigator`). Réponse à : « pourquoi ce client est-il créditeur ? pourquoi cette facture reste-t-elle ouverte ? ».

## Ce que l'on reconstitue

Pour un tiers à une date d'arrêté (par défaut aujourd'hui) :

```
solde = ouverture + factures + avoirs + règlements + écritures diverses
```

Chaque ligne du compte du tiers (portée par le tiers **ou** par son compte auxiliaire) est classée par journal et par sens :

| Journal | Côté normal du tiers (client : débit, fournisseur : crédit) | Côté opposé |
|---|---|---|
| Ventes / Achats | **Facture** | **Avoir** |
| Banque / Caisse | écriture diverse (remboursement) | **Règlement** |
| Opérations diverses | écriture diverse | écriture diverse |

Les lignes antérieures au début de l'exercice de la date d'arrêté forment l'**ouverture**.

`details.equationDuSolde` donne chaque terme. `comparison` rapproche le **restant dû selon les affectations**
(`Σ reste des factures − Σ règlements non affectés − Σ avoirs + divers`) du **solde du grand livre** ; l'écart est nul quand les affectations sont cohérentes
(sinon cause `ALLOCATION_IMBALANCE`).

## Un solde à contre-sens s'explique avant de se qualifier

* **Client débiteur / fournisseur créditeur** : situation normale (`NORMAL_BALANCE`), jamais un constat.
* **Client créditeur / fournisseur débiteur** : on cherche d'abord ce qui le justifie. Montant expliqué = `min(solde à contre-sens, règlements non affectés + avoirs non
  imputés)`. S'il couvre tout : `CREDIT_BALANCE_EXPLAINED` (ou `DEBIT_…`), nature *explication*, avec la décision restante (affecter, avoir, acompte, remboursement).
  Sinon : la part expliquée **et** `…_UNEXPLAINED` pour le résidu (anomalie).

Scénario de référence (test `AccountingInvestigationTest`) : facture 1 000 000, règlements 600 000 et 700 000, affectations 600 000 et 400 000 → reste de facture 0,
règlement non affecté 300 000 → `UNALLOCATED_PAYMENT` (300 000), `OVERPAYMENT` (plus aucune facture ouverte), `CREDIT_BALANCE_EXPLAINED` (300 000), écart d'équation 0.
Une facture partiellement réglée (600 000 sur 1 000 000) donne `PARTIAL_PAYMENT` (info, reste 400 000) et **aucune anomalie**.

## Factures ouvertes (`details.facturesOuvertes`, plafonné à 50, les plus anciennes d'abord)

Pièce, date, montant, affecté, reste, âge en jours, pièce justificative, règlements affectés (avec indication « rapproché en banque »).

## Balance âgée (`details.balanceAgeeFacturesOuvertes`)

Même convention que `BalanceAgeeService` : tranches **0-30, 31-60, 61-90, +90 jours**, âge calculé depuis la date d'écriture jusqu'à la date d'arrêté.
Différence assumée : `BalanceAgeeService` part des lignes **non lettrées** ; l'investigation part des **restes d'affectation** (vérité du paiement depuis la phase D).
Les deux coexistent tant que le lettrage n'est pas remplacé par les affectations.

## Règlement non affecté

* Factures ouvertes disponibles → `OPEN_INVOICES_AVAILABLE` + action « Répartir le règlement » (jamais « la première facture »).
* Plus de facture ouverte, règlement déjà partiellement affecté → `OVERPAYMENT` (+ action « Décider du reliquat »).
* Aucune facture pour le tiers → `PAYMENT_WITHOUT_INVOICE` (+ action « Comptabiliser la facture / saisir un acompte »).
* Rapprochement bancaire annulé → `BANK_RECONCILIATION_REVERSED`.

## Factures d'achat

Chaque facture d'achat sans pièce justificative produit `MISSING_DOCUMENT` avec la suite donnée (aucune demande / demande existante et son statut). Les factures de vente
émises par le cabinet n'en ont pas besoin et ne sont jamais signalées.

## Ce que cette revue ne fait pas

Elle ne modifie rien. Répartir, créer un avoir, rembourser, relancer une demande de pièce passent par les écrans et services existants.
