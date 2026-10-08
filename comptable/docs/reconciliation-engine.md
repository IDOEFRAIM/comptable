# Rapprochement bancaire (Phase D)

Règle absolue : **jamais « le premier candidat »**.

```
CERTAIN                → automatiser   (EXACT)
PROBABLE               → proposer      (PROBABLE, confirmation humaine)
PLUSIEURS CANDIDATS    → AMBIGUOUS     (constat, le comptable choisit)
AUCUN CANDIDAT FIABLE  → UNMATCHED     (constat)
```

## Pas de second moteur

La décision de base reste celle de `RapprochementClassifier` (montant, fenêtre de date, exclusivité d'un mouvement). `RapprochementScorer` ne fait
qu'**enrichir** : un score explicable par candidat, et la référence comme discriminant. `MatchDecisionEngine` (validation des propositions de
l'IA, désactivée par défaut) est conservé tel quel ; ses rapprochements passent désormais par la même porte d'écriture.

## Candidats

Pour une transaction : les **lignes d'écriture du compte de trésorerie** (le règlement côté banque) validées, de même montant et de sens
compatible, dans une fenêtre de ±15 jours autour du relevé — jamais toute la base. Les candidats sont indexés par `(sens, montant)` : la
recherche est directe (pas de balayage de tous les mouvements pour chaque ligne). Une écriture déjà rapprochée n'est plus candidate.

## Score (0–100), toujours expliqué

| Critère | Points |
|---|---|
| Montant exact | 40 |
| Sens compatible | 10 |
| Date : ≤ 2 j → 15 ; ≤ 5 j → 10 ; ≤ 15 j → 5 | 15 |
| Référence ou numéro de facture retrouvé | 20 |
| Même tiers (mots significatifs du libellé) | 10 |
| Libellés proches | 5 |

Chaque candidat porte la liste de ses critères (✓ / ⚠ et points) : c'est ce que l'écran affiche dans « Pourquoi ? ». Le score ne décide pas à lui
seul : il **explique** et **trie**.

| Niveau | Règle |
|---|---|
| `EXACT` | un seul mouvement de même montant, à ≤ 5 jours, revendiqué par cette seule transaction ; ou, parmi plusieurs mouvements de même montant, **un seul porte la référence** de la transaction |
| `PROBABLE` | un seul mouvement de même montant mais à plus de 5 jours |
| `AMBIGUOUS` | plusieurs mouvements possibles, ou un même mouvement revendiqué par plusieurs transactions (y compris deux transactions qui le désignent par référence) |
| `UNMATCHED` | aucun mouvement de même montant |

Une proposition **rejetée** ou un rapprochement **annulé** par un humain n'est plus jamais reproposé ni reconfirmé automatiquement.

## Entité `BankReconciliation`

`bank_reconciliation` : transaction, cible (ligne d'écriture), décision (`EXACT`, `PROBABLE`, `MANUAL`), confiance, source (`SYSTEM_EXACT`,
`SYSTEM_PROPOSAL`, `USER`, `AI_PROPOSAL`, `LEGACY`), statut (`PROPOSED`, `CONFIRMED`, `REJECTED`, `REVERSED`), explication, qui/quand
(confirmation, rejet, annulation + motif). Jamais supprimée.

Invariant : **`CONFIRMED` ⇔ `ligne_releve.ligne_ecriture_id` renseigné**. `BankReconciliationService` est la **seule** porte d'écriture du pointeur
(import, contrôles planifiés, IA, comptable) ; les deux sont modifiés dans la même transaction, sous verrou de la transaction bancaire. Index uniques
partiels : une transaction n'a qu'un rapprochement confirmé ; une écriture n'est confirmée que pour une transaction ; un seul état « vivant » par couple.
Les rapprochements historiques sont repris en `LEGACY`.

Les décisions du système sur un relevé entier sont appliquées **par lot** (`appliquerLot`) : états existants lus en quelques requêtes, décision en
mémoire, écriture groupée — sans quoi chaque ligne déclencherait un contrôle de toute la session.

## Réversibilité et audit

Annuler exige un **motif** ; la transaction redevient « à traiter ». Événements d'audit : `BANK_STATEMENT_IMPORTED`,
`BANK_TRANSACTION_MATCHED_AUTOMATICALLY`, `BANK_MATCH_PROPOSED`, `BANK_MATCH_CONFIRMED`, `BANK_MATCH_REJECTED`, `BANK_RECONCILIATION_REVERSED`.

## Constats (ControlResult → Finding, jamais un `BankAlert` parallèle)

`UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM` (codes existants, conservés), `BANK_DUPLICATE_TRANSACTION`. **Les transactions saines ne
produisent aucun constat.** Au-delà de 50 transactions d'une même catégorie sur un relevé, **un seul constat résume le relevé** (la validation se
fait alors en lot depuis l'écran Banque).

## Ce que la banque ne fait PAS

Aucune écriture n'est créée à partir d'une transaction non reconnue. Un retrait n'est pas une charge. Un virement interne (deux transactions
opposées liées) est modélisé comme cas à traiter, pas finalisé. Les frais bancaires ne sont pas imputés automatiquement (un compte ne se code pas en
dur : le jour où ce sera fait, `AccountPurpose` → `AccountingAccountResolver`).

## Écran Banque (`/entreprises/{id}/rapprochement`)

Compteurs cliquables (transactions, rapprochées, à confirmer, ambiguës, sans correspondance, taux), filtres compte / période / recherche, liste avec
statut, candidat, confiance et « Pourquoi ? ». Actions : confirmer, rejeter, choisir un autre mouvement (recherche métier avec score), annuler (motif),
« deux opérations distinctes ». Validation en lot : **aperçu obligatoire**, filtre de confiance (≥ 85 % par défaut, jamais en dessous de 50), chaque
proposition revalidée côté serveur. L'ancien écran relevé par relevé reste accessible sous `/rapprochement/classique`.
