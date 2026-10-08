# Affectation des règlements et lettrage (Phase D)

## Décision d'architecture (option A)

Il n'existe **pas** d'entité `Reglement` autonome. La vérité du paiement reste portée par l'**écriture comptable** :
`EcritureComptable` → `LigneEcriture` du compte de tiers. L'allocation relie, montant par montant, **la ligne tiers du règlement** à **la ligne tiers de
la facture**.

```
BankTransaction ─▶ BankReconciliation ─▶ ligne de trésorerie du règlement
                                              │ (même écriture)
                                              ▼
                              ligne TIERS du règlement ──PaymentAllocation──▶ ligne TIERS de la facture
                                                                                       │
                                                                       statut CALCULÉ (NON_PAYEE / PARTIELLEMENT_PAYEE / PAYEE)
                                                                                       │
                                                                                    lettrage
```

Une transaction bancaire n'est **jamais** liée directement à une facture.

## `PaymentAllocation`

`payment_allocation` : entreprise, ligne de règlement, ligne de facture, montant alloué, devise, source (`USER`, `SYSTEM_EXACT`,
`BANK_RECONCILIATION`), statut (`ACTIVE`, `REVERSED`), créateur, annulation (qui, quand, motif). **Jamais supprimée.** Une allocation `REVERSED` ne compte plus.

### Invariants (vérifiés à chaque allocation, sous verrou des deux lignes)

1. même entreprise (une ligne d'une autre entreprise est « introuvable » — aucune fuite d'existence) ;
2. même compte de tiers, et même tiers quand les deux lignes en portent un ;
3. sens comptables opposés (facture au crédit ↔ règlement au débit, et inversement) ;
4. écritures **validées** ;
5. montant alloué `> 0` ;
6. somme des allocations d'un règlement ≤ montant du règlement ;
7. somme des allocations d'une facture ≤ montant de la facture.

Idempotence : une seule allocation **active** par couple de lignes (index unique partiel). Même couple, même montant → la même allocation (double
confirmation) ; autre montant → 409, il faut annuler d'abord. Deux requêtes simultanées ne créent qu'une allocation.

## Statut de paiement d'une facture

Calculé, jamais saisi : `0 alloué → NON_PAYEE` ; `0 < alloué < dû → PARTIELLEMENT_PAYEE` ; `alloué = dû → PAYEE`. Un **surplus reste non affecté** sur le
règlement (jamais absorbé artificiellement par la facture) et ouvre un constat `OVERPAYMENT` s'il ne reste aucune facture à régler. L'ancien drapeau
manuel `Facture.statut = PAYEE` n'est plus la source de vérité du paiement : le statut de paiement se lit dans les allocations.

Cas couverts par les tests : 1 règlement → 1 facture ; N règlements → 1 facture ; 1 règlement → N factures ; paiement partiel ; paiement total ;
surpaiement avec reliquat ; annulation ; recalcul du statut.

## Allocation automatique (cas certains seulement)

Pour un même tiers : **une** facture ouverte et **un** règlement ouvert du **même reste**, et aucun autre de ce montant de part ni d'autre, de sens
compatibles → allocation `SYSTEM_EXACT`, sans intervention. Tout le reste est laissé au comptable : plusieurs factures de même montant, montants
différents, règlement groupé. Chaque allocation automatique est dans sa propre transaction.

| Situation | Constat |
|---|---|
| règlement non affecté, des factures ouvertes mais rien de certain | `PAYMENT_ALLOCATION_NEEDS_REVIEW` (le comptable répartit) |
| règlement non affecté, aucune facture ouverte | `PAYMENT_WITHOUT_INVOICE` |
| règlement affecté en partie, reliquat sans facture à régler | `OVERPAYMENT` |
| règlement certain affecté automatiquement | aucun constat |

Au-delà de 8 règlements d'une même catégorie, un seul constat les résume.

## Lettrage

L'allocation (affectation **économique**) peut **alimenter** le lettrage (rapprochement **comptable** des mouvements du tiers) — jamais l'inverse.
Quand tout un groupe de lignes reliées (composante connexe des allocations actives) est **intégralement** alloué, il est équilibré par construction et
est lettré (`LETTRAGE_CREATED`, avec le code de séquence habituel). **Un règlement partiel ne fait jamais apparaître une facture comme lettrée.**
Annuler une allocation défait le lettrage qu'elle avait permis (`LETTRAGE_REVERSED`). Le lettrage manuel existant (`LettrageService`) est inchangé.

## Audit

`PAYMENT_ALLOCATED`, `PAYMENT_ALLOCATION_REVERSED`, `LETTRAGE_CREATED`, `LETTRAGE_REVERSED`.

## API (cabinet uniquement) — `/api/entreprises/{id}/banque/paiements`

`GET reglements-a-repartir`, `GET reglements/{ligne}/factures-candidates?q=` (recherche par numéro, libellé ou tiers — jamais un identifiant à saisir),
`GET factures`, `GET allocations`, `POST allocations`, `POST allocations/{id}/annuler` (motif obligatoire).
