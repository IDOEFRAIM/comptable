# TVA Burkina Faso — workflow fiscal (G3) : audit initial et gate d'entrée

Statut de ce document : **audit et gate d'entrée de G3**, mis à jour après **G3A** (infrastructure indépendante de la réglementation, voir `../g3a-infrastructure-report.md`). Le prérequis réglementaire (pack TVA Burkina vérifié, issu de G2) n'existe toujours pas dans le dépôt : aucune règle burkinabè n'a été codée.

## 1. Constat sur l'entrée de G3

| Vérification | Résultat |
|---|---|
| Dossier `docs/fiscal/burkina-faso/` avant ce document | absent |
| Source officielle burkinabè (CGI, textes DGI, formulaire de déclaration TVA) archivée (`docs/sources-reglementaires/`) | **aucune** (seul l'AUDCIF/SYSCOHADA y figure) |
| Cadre fiscal VERIFIED en base ou en code | **aucun** (aucun cadre n'est semé, voir `fiscal-core.md`) |
| `tax_rule` | table volontairement vide (« aucune source réglementaire vérifiée pour le Burkina Faso », `TaxRule.java`) |
| Identifiant de règle (`ruleId`), résolveur de date d'exigibilité (`TaxDateResolver`), règles de déductibilité, de crédit ou de régularisation dans le modèle | **absents** (`VatRules` ne porte que taux, périodicité, règle d'échéance, tolérances, lignes de déclaration) |

**Conclusion : G2 n'a pas produit de pack utilisable. Tout ce qui, dans G3, doit s'appuyer sur une règle VERIFIED est bloqué.**

## 2. Gate d'entrée — BF VAT Regulatory Pack = **BLOCKED**

| Règle essentielle | Statut | Remarque |
|---|---|---|
| Taux | UNVERIFIED | aucun taux dans le code ni la base (les 10 / 5 des tests sont fictifs) |
| Champ d'application | UNVERIFIED | |
| Exigibilité (date de fait générateur / `taxDate`) | UNVERIFIED | pas de `TaxDateResolver` ; aucun repli silencieux sur la date de facture ne sera ajouté |
| Déductibilité | UNVERIFIED | |
| Exclusions du droit à déduction | UNVERIFIED | |
| Périodicité | UNVERIFIED | |
| Échéance | UNVERIFIED | `EcheancierTvaGenerator` (15 du mois suivant) est une convention codée en dur, **non vérifiée** |
| Formulaire officiel (lignes, codes) | UNVERIFIED | structure générique non officielle utilisée |
| Crédit / report | UNVERIFIED | |
| Avoirs / régularisations | UNVERIFIED | |

## 3. Audit de l'existant (après G0/G1)

**Complet (infrastructure, indépendant du pays)**
* Cadre fiscal versionné DRAFT / VERIFIED / RETIRED, hash, sources obligatoires à la vérification.
* Période déclarative (OPEN → PREPARING → READY_FOR_REVIEW → REVIEWED → FINAL), invalidation de la revue après modification.
* Moteur de calcul à partir de la comptabilité, classification (`TaxTreatment`), ajustements justifiés et annulables, rapprochement grand livre / opérations.
* Findings fiscaux proactifs, résolution automatique, déclaration, revue, finalisation, snapshot immuable (trigger), hash.
* Intégration Cabinet : file à traiter, colonne TVA du portefeuille, échéance conditionnelle à un cadre VERIFIED.
* Espace Angular TVA, tests backend / Karma / Playwright, performance à nombre de requêtes constant.
* Module de télépaiement fiscal préexistant (`fiscalite.paiement`, simulation par défaut, aucune API réelle) : à auditer avant toute création de `TaxPayment`.

**Partiel**
* *(levé par G3A)* pagination SQL, recherche, filtres, statut opérationnel (A_PAYER, PAYE, CREDIT_A_REPORTER…), suivi interne du paiement, crédit d'ouverture.
* Restent : pas de gestion des cadres dans l'interface ; pas de `ruleId` ni de preuve documentaire par opération.

**Manquant pour une vraie période TVA** (et qui dépend d'un pack VERIFIED) : `taxDate` et son règle, déductibilité partielle (ratio), exclusions, justificatifs (Document Hub), avoirs vérifiés, crédit reportable et crédit d'ouverture, paiement et solde dû, `ruleId` sur chaque opération et ligne, sources réglementaires affichées, export fidèle au formulaire officiel, benchmark 50 000 opérations.

## 4. Ce qui peut avancer sans règle réglementaire, et ce qui ne le peut pas

* **Peut avancer (infrastructure, testable avec des cadres fictifs)** : statut opérationnel et progression en étapes, pagination et recherche SQL, bulk review conditionné à une règle identique, mécanisme de crédit et de crédit d'ouverture tracé, suivi interne de paiement (réutiliser ou étendre `fiscalite.paiement`), optimistic locking, extension de l'audit.
* **Ne peut pas avancer** : tout calcul de taxDate, de déductibilité, de report, de régularisation après FINAL, de formulaire officiel et d'échéance, tant que le pack n'est pas VERIFIED. Ces fonctionnalités restent INCOMPLETE.

## 5. Verdict G3 à ce stade

**G3 INFRASTRUCTURE READY — G3 PAS DONE.** Gates : Fiscal Core READY ; VAT Infrastructure READY ; VAT Workspace Infrastructure READY ; Payment Tracking READY (suivi interne) / PARTIAL_READY (réglementairement) ; **BF Regulatory Pack BLOCKED** ; **BF VAT Finalization BLOCKED** (BF VAT Declaration, Engine réglementaire et Cabinet réglementaire : PARTIAL_READY faute de règle vérifiée).

**Pour débloquer** : fournir les sources officielles (texte du CGI sur la TVA, formulaire de déclaration, textes sur l'exigibilité, la déduction, le crédit et les régularisations) afin de produire le pack G2 (avec référence, version, date d'effet, `ruleId`).
