# Rapport de la phase E — Révision comptable, investigation et pré-clôture explicable

Règle finale de la phase : *normal → silence ; anormal → détecter ; anormal explicable → montrer la cause et les preuves ; cause corrigée → résoudre automatiquement ;
ambigu → décision humaine ; blocage non résolu → clôture interdite ; dossier propre → READY.*

## 1. Baseline

Départ de la phase E : Maven **760 tests, 0 échec, 0 erreur, 3 ignorés** ; `npm run build` OK ; pytest **328**. Les chiffres d'arrivée sont aux sections 25 à 27.

## 2. Dettes de la phase D résolues (E0)

| Dette | Résolution | Où |
|---|---|---|
| E0.1 `PAYEE` source de vérité | statut **calculé** (montant − Σ affectations actives) par `StatutPaiementFactureService` ; mutation manuelle supprimée ; migration `V57` (consigne chaque `PAYEE` manuel dans `migration_report`, puis `VALIDEE`) | `docs/bank-domain.md` |
| E0.2 virements internes | table `bank_internal_transfer` (`V58`), `InternalTransferMatcher` (EXACT / PROBABLE / AMBIGUOUS / UNMATCHED), jamais d'écriture inventée, constats `INTERNAL_TRANSFER_*` | idem |
| E0.3 empreintes des anciennes lignes | `BankFingerprintBackfillService` (simulation, rapport total / calculables / posées / collisions / insuffisantes, rien de supprimé) | idem |
| E0.4 pagination serveur | filtres SQL pour les états persistés, classement limité aux lignes affichées, tri `date|montant|libellé`, index `V59` | idem |

Usages de `PAYEE` classés avant suppression : lecture (recouvrement, relances, score clients, prévision de trésorerie, historique de matching), écriture (service + endpoint),
frontend (bouton), test (3 cas supprimés car sans objet), reporting.

## 3. Architecture du debugger

Package `investigation` : `AccountingInvestigationService` (façade), `TiersInvestigator`, `BankInvestigator`, `PieceInvestigator`, `InvestigationResult`, `CauseCodes`. Lecture seule, sans
table, sans abstraction concurrente (réutilise lineage, banque, affectations). **Il ne corrige jamais** : pas de `fix()`, les actions suggérées nomment une opération existante. Voir
`docs/accounting-investigation.md`.

## 4. Codes de cause

Une trentaine de codes stables (`UNALLOCATED_PAYMENT`, `PARTIAL_PAYMENT`, `OVERPAYMENT`, `MISSING_DOCUMENT`, `BANK_TRANSACTION_UNMATCHED`, `BANK_RECONCILIATION_REVERSED`,
`INVOICE_NOT_POSTED`, `DUPLICATE_INVOICE`, `FIXED_ASSET_WITHOUT_SOURCE`, `CREDIT_BALANCE_EXPLAINED`, `OPENING_BALANCE_MISMATCH`, …). Chaque cause a une **nature** (anomalie / explication / information),
une **base** (persisté > lineage > exact > heuristique), un montant et ses preuves. Table complète dans `docs/accounting-investigation.md`.
Écart avec la liste de la mission : `ACCOUNT_MAPPING_MISSING` reste porté par le constat `ACCOUNT_NOT_CONFIGURED` (le debugger ne recalcule pas le paramétrage).

## 5. Modèle de preuves

`Evidence{kind, entityType, entityId, label, amount, date}` : toujours un libellé lisible (« Règlement BQ-2026-0002 »), jamais un identifiant seul. Un test vérifie qu'aucun UUID n'apparaît dans les libellés ni
les résumés. Les objets liés sont navigables par type + id, plafonnés à 100.

## 6. Chronologie

Triée par date. Tiers : factures, avoirs, règlements, divers, ouverture, affectations (100 derniers événements, la troncature est indiquée). Transaction : import, opération, historique de
rapprochements. Relevé : période et soldes. Écriture : source, proposition, pièce, demande de pièce, banque, affectations. Document : dépôt, validation, rapprochement, comptabilisation.

## 7. Investigation des clients

Équation `solde = ouverture + factures + avoirs + règlements + divers` ; restant dû selon les affectations rapproché du solde du grand livre ; **un solde créditeur s'explique avant de se qualifier**
(`CREDIT_BALANCE_EXPLAINED` ou résidu `…UNEXPLAINED`) ; factures ouvertes détaillées ; balance âgée 0-30 / 31-60 / 61-90 / +90 (même convention que `BalanceAgeeService`).
Scénario de la mission : facture 1 000 000, règlements 600 000 + 700 000, affectations 600 000 + 400 000 → `UNALLOCATED_PAYMENT` 300 000, solde créditeur expliqué. Facture partielle : aucune anomalie.
Voir `docs/third-party-review.md`.

## 8. Investigation des fournisseurs

Même mécanique (côté normal = crédit) ; factures d'achat sans pièce → `MISSING_DOCUMENT` avec la suite donnée à la demande de pièce ; fournisseur débiteur expliqué par règlements non affectés ou avoirs.

## 9. Investigation bancaire

*Transaction* : rapprochée, proposition à confirmer, ambiguïté, rapprochement annulé (jamais reproposé), virement interne en attente / sans écriture, doublon possible, sinon sans écriture. *Relevé* :
`écart = (solde début relevé − solde comptable à l'ouverture) + Σ transactions sans écriture − Σ écritures de la période sans transaction`, résidu éventuel inexpliqué (`BANK_DIFFERENCE_UNEXPLAINED`).
Scénario de la mission : solde comptable 10 000 000 vs relevé 9 750 000, transaction non rapprochée 250 000 → `BANK_TRANSACTION_UNMATCHED` (−250 000), aucun résidu. Tout se lit par agrégats SQL.

## 10. Investigation des documents

Document traité sans écriture → `INVOICE_NOT_POSTED` ; doublon → `DUPLICATE_INVOICE` ; demande de pièce liée. Écriture sans pièce → `MISSING_DOCUMENT` (+ statut de la demande). Facture non comptabilisée → `INVOICE_NOT_POSTED`.

## 11. Investigation des immobilisations

Bien sans acquisition comptable (`FIXED_ASSET_WITHOUT_SOURCE`, plus grave s'il est en service), double acquisition possible, écart registre / comptabilité (−valeur d'origine).

## 12. Contrôles de révision (E3)

Audit des contrôles existants et matrice complète dans `docs/pre-closing-architecture.md`. Décision : **aucun nouveau contrôle, aucune nouvelle infrastructure** ; ajout d'un **domaine** commun (`ControlDomain`,
table unique par code) et des **causes du debugger** dans le point bancaire de la pré-clôture. TVA, stock et immobilisations ne reposent que sur les invariants déjà implémentés.

## 13. Findings

Pipeline inchangé (détecteur → `ControlResult` → réconciliateur → `Finding`). Ajouts : colonne `finding.domaine` (`V60`, filtre et comptes côté base, index), `typeTiers` dans les constats de règlement pour séparer clients / fournisseurs.
Cause corrigée → résolution automatique (`SYSTEM`, jamais supprimé) ; constat écarté → reste écarté (politique phase B).

## 14. Espace de révision (E6)

L'écran Révision existant est étendu : « Prêt à clôturer : OUI/NON », comptes (bloquants / avertissements / à examiner / résolus aujourd'hui), pré-clôture résumée, cases par domaine (filtre cliquable),
bouton **Pourquoi ?** (résumé, écart, causes avec preuves, chronologie, objets liés navigables, actions possibles), écarter avec justification. Voir `docs/revision-workspace.md`.

## 15. Pré-clôture avancée (E5)

`ClotureReadinessService` étendu (pas de V2) : statuts `READY` / `READY_WITH_WARNINGS` (renommé) / `NOT_READY`, `resume{bloquants, avertissements, enOrdre, nonApplicables}` (aucun pourcentage),
`domaine` par point, `evaluatedAt` et `controlesExecutesLe`. `NOT_APPLICABLE` conservé. Chaque point est explicable (message, explication chiffrée, objets, causes pour la banque).

## 16. Règles de blocage

Seuls les points `BLOCKING` bloquent : écritures en brouillon, écritures déséquilibrées, absence d'exercice, constats de gravité `BLOCKING`. Tout le reste avertit. Aucune règle réglementaire n'a été inventée pour bloquer.
Prouvé : 3 blocages → refus ; 1 blocage → refus ; 0 → possible (`ClotureRevisionWorkflowTest`).

## 17. Fraîcheur

La clôture relance les contrôles puis construit la checklist **dans la même transaction** ; une écriture problématique apparue après un « READY » est vue (test dédié). Course évaluée : verrou pessimiste sur l'exercice
(deux clôtures simultanées → la seconde voit l'exercice clôturé, 409) ; fenêtre résiduelle documentée (validation d'écriture non verrouillée). **Les exécutions de contrôles d'une même entreprise sont désormais
sérialisées** (verrou consultatif PostgreSQL) : défaut réel trouvé au test navigateur (deux écrans ouverts en même temps déclaraient le même constat → 500).

## 18. Workflow de clôture (E7)

Contrôles relancés → préparation évaluée → `BLOCKING` : refus 422 avec la liste → avertissements : 409 `AVERTISSEMENTS_A_CONFIRMER` avec la liste, puis confirmation **avec motif obligatoire** → clôture.
Corps facultatif `{avertissementsConfirmes, motif}` (un corps `{}` ou absent ne bloque jamais un dossier propre — voir défauts trouvés). L'écran demande le motif et relance. L'appel interne historique est conservé.

## 19. Audit

Noms français (convention du projet) : `REVUE_OUVERTE`, `CONTROLES_EXECUTES`, `FINDING_CREE`, `FINDING_RESOLU_AUTO`, `FINDING_RESOLU`, `FINDING_REJETE`, `INVESTIGATION_OUVERTE`, `CLOTURE_CONTROLEE`, `CLOTURE_REFUSEE`,
`CLOTURE_AVERTISSEMENTS_ACCEPTES` (auteur, date, motif, liste), `CLOTURE_TERMINEE`. Correspondance avec la liste anglaise de la mission dans `docs/pre-closing-architecture.md`. Vérifié sur le parcours complet (étape 27 du test E2E)
et dans la base après le test navigateur (2 refus, 1 acceptation, 1 clôture).

## 20. Multi-tenant

Constat, compte de tiers, facture, écriture, transaction, relevé, document, immobilisation, pré-clôture, clôture, espace de révision : **404 pour une entreprise d'un autre cabinet** (`AccountingInvestigationTest`,
`ClotureRevisionWorkflowTest`, `RevisionEndToEndTest` étape 25). L'espace de révision d'un autre cabinet est vide.

## 21. Sécurité des rôles

Les nouvelles routes sont sous `/revue/**` et `/cloture-readiness` : cabinet seulement (`COLLABORATEUR`, `EXPERT_COMPTABLE`). `PhaseCSecuriteHttpTest` (étendu : `espace`, `investigation`, `investigations/**`,
`cloture-readiness`) : un `CLIENT` reçoit 403 partout, le cabinet n'est jamais bloqué, sans jeton 401/403. Aucun rôle nouveau.

## 22. Performances (mesurées)

*Pagination Banque* (E0.4, 5 000 / 20 000 / 50 000 transactions) : page de 25 lignes 110–160 ms, 7–19 requêtes SQL, ≈ 8,7 Ko ; statuts calculés (« sans correspondance », synthèse) 0,5 → 3,5 s selon le volume **non expliqué** (détail dans `docs/bank-domain.md`).

*Volume Phase E* (`RevisionVolumeTest` : 10 002 écritures, 2 000 factures d'un même client, 5 000 transactions, 3 000 documents) :

| Mesure | Durée | SQL | Réponse |
|---|---|---|---|
| Exécution complète des contrôles | ≈ 6 à 7 s | — | **2 constats actifs** pour 10 002 écritures |
| Espace de révision | ≈ 2,1 s | 33 | 0,4 Ko |
| Pré-clôture | ≈ 1,6 s | 31 | 22 Ko |
| Pourquoi ? — tiers à 2 000 factures | ≈ 0,4 s | 9 | 65 Ko (causes résumées, chronologie tronquée, 100 objets liés) |
| Pourquoi ? — relevé de 5 000 transactions | ≈ 0,1 s | 10 | 8,7 Ko |
| Pourquoi ? — une transaction | ≈ 0,1 s | 9 | 1,7 Ko |
| Pourquoi ? — une facture | ≈ 0,4 s | 25 | 1,7 Ko |

Les exceptions sont très inférieures aux opérations ; le debugger ne charge pas le grand livre ; aucun N+1 (le nombre de requêtes de la pré-clôture et de l'espace est indépendant du volume). Une première version renvoyait 224 Ko pour le tiers
(1 851 objets liés) : plafonnée, 65 Ko.

## 23. Tests PostgreSQL

`RevisionEndToEndTest` : **27 étapes** numérotées sur un vrai PostgreSQL (dossier, anomalies, contrôles, espace par domaine, filtres, Pourquoi ?, équation du tiers, relevé, non-correction, pré-clôture, blocage, refus, correction, résolution
automatique, compteur du jour, écartement, 409, 422, isolation, clôture, audit). Autres : `AccountingInvestigationTest` (9), `ClotureRevisionWorkflowTest` (9), `ClotureHttpTest` (4), `ControleConcurrenceTest` (1),
`RevisionVolumeTest` (1), `BanquePaginationEchelleTest` (1), `RecouvrementStatutCalculeTest` (3), `VirementsInternesTest` (6), `InternalTransferMatcherTest` (9), `BankFingerprintBackfillTest` (3).

## 24. Tests navigateur (navigateur intégré, serveurs de développement, jeu de données local)

Parcours réel : Révision (bandeau, domaines, filtres) → clic sur la case **Banque** (un seul constat) → **Pourquoi ?** sur le trop-perçu client, la transaction sans écriture, l'immobilisation, la pièce manquante → chronologie → navigation vers la facture
(écran Écritures) → écran Banque (tri par montant, filtre « sans correspondance », virements internes) → **réaffectation** d'un règlement (Règlements à répartir → Affecter) → retour à la Révision (constat résolu automatiquement, « 1 résolu aujourd'hui ») →
relance des contrôles → clôture refusée avec la liste (brouillon) → validation du brouillon dans Écritures → pré-clôture « 0 bloquant » → clôture avec avertissements : l'écran affiche les 9 avertissements et demande le motif → exercice clôturé, exercice suivant ouvert.

**Défauts réels trouvés et corrigés grâce à ce test** (aucun n'était visible aux tests de service) :
1. `server.error.include-message` n'a plus d'effet en Spring Boot 4 : **aucun message de refus n'atteignait l'écran** (« Impossible de clôturer cet exercice » au lieu de la liste des bloquants) → `spring.web.error.include-message=always`.
2. Le corps `{}` envoyé par l'écran faisait **400** sur le nouvel endpoint de clôture → champ `Boolean` nullable ; test HTTP dédié.
3. Deux écrans ouverts ensemble déclenchaient deux exécutions de contrôles qui déclaraient le même constat → **500** → exécutions sérialisées par entreprise ; test de concurrence.
4. Le point bancaire / le debugger : `RapprochementScorer.natureContredit` levait une **NPE** sur un libellé sans moyen de paiement reconnaissable (« Operation 1 ») lors d'un import → corrigé (trouvé par les tests d'investigation).

## 25. Maven

**805 tests, 0 échec, 0 erreur, 3 ignorés** (baseline 760 : +45), `BUILD SUCCESS`, suite entière relancée après la dernière modification.

## 26. Angular

`npm run build` : OK (relancé après la dernière modification du frontend).

## 27. pytest

`ai-service` (venv du dépôt) : **328 passés** (inchangé en phase E, relancé en fin de phase).

## 28. Limites restantes

* Les **avoirs** ne sont pas des affectations : une facture partiellement créditée affiche son reste ; le debugger le dit (`CREDIT_NOTE_NOT_APPLIED`) sans le corriger.
* Annuler une facture portant des affectations actives n'est pas bloqué par le moteur.
* Le point de pré-clôture `lettrage` repose encore sur le lettrage, pas sur les affectations ; `caisse` et `rapprochement` restent `NOT_APPLICABLE`.
* Les statuts bancaires **calculés** croissent avec le volume non expliqué (≈ 3,5 s pour 5 000 lignes à traiter) ; persister le classement serait la suite si ce volume devient courant.
* Fenêtre résiduelle de course entre la checklist et le passage à `CLOTURE` (la validation d'écriture n'est pas verrouillée).
* Le motif d'acceptation d'un avertissement est global à la clôture (une seule justification pour la liste), pas par avertissement.
* Les constats de règlement antérieurs à `V60` restent dans « Autres » jusqu'à leur prochaine détection.
* Le solde d'ouverture d'un tiers = lignes antérieures à l'exercice ; aucun report à nouveau distingué.
* Navigation « Voir le paiement / la facture » : mène à l'écran concerné, pas encore à la ligne précise (aucun lien profond par identifiant dans les écrans existants).
* Hors périmètre, non faits : Bilan / Compte de résultat / TFT / annexes, paie, consolidation, prévisions, chatbot générique, microservices, event sourcing. L'IA n'intervient pas : toutes les explications sont déterministes.

## Résultats finaux

| Suite | Résultat |
|---|---|
| `./mvnw test` (entière, après la dernière modification) | 805 tests, 0 échec, 0 erreur, 3 ignorés |
| `npm run build` | OK |
| `pytest` (ai-service) | 328 passés |

Incidents de la phase finale, pour mémoire :
* Une première suite complète a révélé 1 échec (`P1CabinetOperatingSystemE2ETest`) : une facture validée **sans écriture** était exclue de la prévision de trésorerie depuis E0.1. Corrigé dans `StatutPaiementFactureService.ouvertes` : sans écriture, aucune affectation n'est possible, la facture est entièrement à payer.
* Une deuxième suite a échoué sur le seuil de durée de `BanqueVolumeTest` (contrôles 1 277 s au lieu de ≈ 5 s) : statistiques PostgreSQL périmées après les jeux de volume (50 000 lignes) accumulés dans la base de test, pas une régression de code ; après `VACUUM ANALYZE` le test repasse (13 s seul, 4 s en suite). À savoir pour la base de développement : relancer `vacuum analyze` après des tests de volume.
