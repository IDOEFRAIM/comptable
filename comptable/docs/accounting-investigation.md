# Accounting Debugger — « Pourquoi ? »

Phase E. Package `com.example.comptable.investigation`. Lecture seule, recalculé à la demande, **aucune table**.

## Principe

Depuis un constat, un tiers, une facture, une écriture, une transaction, un relevé, un document ou une immobilisation, le debugger reconstitue

```
source → événements → conséquences comptables → rapprochements → affectations → solde → anomalie
```

et répond à « pourquoi ce solde / cet écart / cette alerte ? ». Il ne **corrige jamais** : aucune méthode ne crée d'écriture, ne modifie un compte, ne
supprime une affectation, ne force un rapprochement, ne change un solde. Les actions suggérées nomment une opération d'un service métier existant
(`SuggestedAction.operation`, ex. « Banque > Règlements à répartir ») que le comptable déclenche lui-même. Il n'existe pas de `Debugger.fix()`.
Le test `RevisionEndToEndTest` (étape 16) vérifie qu'ouvrir toutes les investigations ne change ni le nombre d'écritures ni celui d'affectations.

## Architecture

| Élément | Rôle |
|---|---|
| `AccountingInvestigationService` | Façade : `investiguer(type, id)`, `depuisConstat(findingId)` (contextuel), `causesDuReleve` (pré-clôture). Vérifie l'accès entreprise (404 pour un objet d'une autre entreprise). |
| `TiersInvestigator` | Compte de tiers (client/fournisseur) et lignes de tiers (règlement, facture). |
| `BankInvestigator` | Transaction bancaire et relevé (solde comptable vs solde du relevé). |
| `PieceInvestigator` | Écriture (lineage + banque + affectations), facture, document, immobilisation. |
| `InvestigationResult` | DTO (non persisté). |
| `CauseCodes` | Codes de cause stables. |

Aucune abstraction concurrente : les investigateurs lisent les repositories et services existants (`AccountingLineageService`, `BankLineageService`,
`PaymentAllocationRepository`, `BankReconciliationRepository`, `RapprochementAutomatiqueService.classerCibles`).

## `InvestigationResult`

`subject{type,id,label}`, `summary`, `comparison{expectedLabel,expected,actualLabel,actual,difference}`, `causes[]`, `evidence[]`, `timeline[]`,
`relatedEntities[]`, `suggestedActions[]`, `details{}` (données structurées propres au sujet : équation du solde, balance âgée, factures ouvertes, équation de
l'écart bancaire), `confidence`, `generatedAt`.

* **Cause** : `code` stable (machine), `nature` (`ANOMALY` à traiter, `EXPLANATION` qui explique un écart, `INFO` situation normale décrite),
  `explanation` lisible, `basis`, `amount`, `evidence[]`.
* **Preuve** : jamais un identifiant seul — libellé lisible, montant, date, type + id pour naviguer.
* **Priorité des sources** (`basis`) : `PERSISTED` (affectation, rapprochement, virement interne enregistrés) > `LINEAGE` > `EXACT` (correspondance exacte,
  absence de lien vérifiable) > `HEURISTIC`. `confidence` = la plus faible base parmi les anomalies : `HIGH` / `MEDIUM` / `LOW`.
* **Navigation sans UUID** : `relatedEntities[].label` est un libellé métier (« Facture VF-12 », « Règlement BQ-2026-0002 »). Un test vérifie qu'aucun UUID n'apparaît
  dans les libellés ni dans le résumé.

## Plafonds (le clic ne charge pas le grand livre)

Un relevé de 5 000 lignes se lit par **agrégats SQL** (`agregatNonPointees`, `montantNonPointeEntre`) et 20 lignes d'exemple. Une transaction se classe avec les seules
lignes du même relevé et du même montant signé (même résultat qu'un classement complet, prouvé : le statut d'une ligne ne dépend que d'elles). Un tiers de milliers
de lignes : factures ouvertes et sans pièce **résumées** (nombre, montant, âge, exemples), liste détaillée limitée à 50, chronologie aux 100 derniers événements
(`details.chronologieTronquee` dit combien ont été omis), 100 objets liés maximum.

## Codes de cause

| Code | Nature | Sens |
|---|---|---|
| `UNALLOCATED_PAYMENT` | ANOMALY | Un règlement n'est pas (entièrement) affecté à des factures. |
| `OPEN_INVOICES_AVAILABLE` | EXPLANATION | Des factures ouvertes existent : on peut répartir. |
| `OVERPAYMENT` | EXPLANATION | Plus aucune facture ouverte : reliquat = trop-perçu (avoir, acompte ou remboursement à décider). |
| `PAYMENT_WITHOUT_INVOICE` | EXPLANATION | Aucune facture pour ce tiers (acompte ? facture non comptabilisée ?). |
| `PARTIAL_PAYMENT` / `UNPAID_INVOICE` | INFO | Facture partiellement réglée / non réglée : **situation normale, pas d'anomalie**. |
| `CREDIT_NOTE_NOT_APPLIED` | EXPLANATION | Avoir qui réduit le solde sans être rattaché à une facture (limite connue : les avoirs ne sont pas des affectations). |
| `CREDIT_BALANCE_EXPLAINED` / `DEBIT_BALANCE_EXPLAINED` | EXPLANATION | Solde à contre-sens **justifié économiquement** par règlements non affectés / avoirs non imputés. |
| `CREDIT_BALANCE_UNEXPLAINED` / `DEBIT_BALANCE_UNEXPLAINED` | ANOMALY | Part du solde à contre-sens que rien n'explique. |
| `NORMAL_BALANCE` | INFO | Solde du côté normal. |
| `ALLOCATION_IMBALANCE` | ANOMALY | Les affectations ne s'équilibrent pas entre factures et règlements du tiers. |
| `BANK_TRANSACTION_UNMATCHED` | ANOMALY | Transaction (ou ensemble de transactions d'un relevé) sans écriture. |
| `BANK_MATCH_AMBIGUOUS` / `BANK_MATCH_TO_CONFIRM` | ANOMALY | Plusieurs écritures possibles / une seule à confirmer. |
| `BANK_RECONCILIATION_REVERSED` | ANOMALY | Rapprochement annulé par un humain (jamais reproposé à la même écriture). |
| `BOOKED_NOT_ON_STATEMENT` | EXPLANATION | Écritures de la période du relevé sans transaction (chèque non encaissé, virement en transit…). |
| `OPENING_BALANCE_MISMATCH` | ANOMALY | Solde de début du relevé ≠ solde comptable à l'ouverture. |
| `BANK_DIFFERENCE_UNEXPLAINED` | ANOMALY | Écart résiduel après toutes les explications ci-dessus. |
| `INTERNAL_TRANSFER_PENDING` / `INTERNAL_TRANSFER_WITHOUT_ENTRY` | ANOMALY | Virement interne à confirmer / confirmé sans écriture. |
| `BANK_DUPLICATE_TRANSACTION` | ANOMALY | Même jour, même montant, même sens. |
| `BANK_RECONCILED` | INFO | Transaction rapprochée. |
| `MISSING_DOCUMENT` | ANOMALY | Écriture/facture d'**achat** sans pièce (+ suite donnée à la demande de pièce). |
| `INVOICE_NOT_POSTED` | ANOMALY | Facture ou document traité sans écriture. |
| `DUPLICATE_INVOICE` | ANOMALY | Document doublon d'un document déjà déposé. |
| `DOCUMENT_REQUEST_PENDING` | INFO | Le document répond à une demande de pièce. |
| `FIXED_ASSET_WITHOUT_SOURCE` / `POSSIBLE_DOUBLE_ACQUISITION` | ANOMALY | Immobilisation sans acquisition comptable / acquisition peut-être comptée deux fois. |

`ACCOUNT_MAPPING_MISSING` (compte non configuré) reste porté par les constats `ACCOUNT_NOT_CONFIGURED` du refus comptable : l'investigation d'un constat agrégé le
décrit (cause = code du contrôle) sans re-calculer le paramétrage.

## Chronologie

Tiers : factures, avoirs, règlements, écritures diverses, ouverture (lignes antérieures au début de l'exercice), affectations. Transaction : import, opération,
historique de rapprochements (proposé / confirmé / rejeté / annulé). Relevé : période et soldes annoncés. Écriture : opération source, proposition, saisie
manuelle, pièce, demande de pièce, transactions bancaires, affectations. Document : dépôt, validation, rapprochement, comptabilisation. Triée par date.

## Endpoints (cabinet uniquement : `/api/entreprises/*/revue/**` → COLLABORATEUR / EXPERT_COMPTABLE, jamais CLIENT)

* `GET /api/entreprises/{id}/revue/constats/{findingId}/investigation` — contextuel : un constat de règlement ouvre le règlement, un constat `UNMATCHED_PAYMENT`
  la transaction ou le relevé, `TIERS` le compte, etc. Trace l'audit `INVESTIGATION_OUVERTE`.
* `GET /api/entreprises/{id}/revue/investigations/{type}/{objetId}?au=AAAA-MM-JJ` — `TIERS`, `LIGNE_ECRITURE`, `ECRITURE`, `FACTURE`, `LIGNE_RELEVE`, `RELEVE`,
  `DOCUMENT`, `IMMOBILISATION`.

Un constat dont l'objet est agrégé (`ENTREPRISE`, `PIECES_MANQUANTES`…) donne un résumé, la cause portant le code du contrôle, et les objets cités rendus navigables.

## Limites connues

* Les avoirs ne sont pas modélisés comme des affectations : une facture partiellement créditée montre toujours son reste (cause `CREDIT_NOTE_NOT_APPLIED`).
* Annuler une facture portant des affectations actives n'est pas bloqué par le moteur (à traiter dans une phase dédiée au cycle de vie des factures).
* Le « solde d'ouverture » d'un tiers est la somme des lignes antérieures au début de l'exercice : aucune écriture de report à nouveau n'est distinguée.
* Les écritures de banque du côté normal du tiers (remboursements) sont rangées en « divers », jamais en règlement à répartir.
