# Rapport final — Phase D : banque, rapprochement et lettrage

Objectif : *le comptable n'a plus besoin de pointer à la main les opérations bancaires évidentes.*
Les cas certains s'automatisent, les cas forts mais incertains se proposent, plusieurs candidats se demandent au comptable, l'absence de candidat se
signale, un paiement partiel conserve son solde, une erreur de rapprochement s'annule, une opération saine n'encombre pas l'Inbox.

## Avant / après

**Avant** : relevé CSV à colonnes figées → lignes → le comptable pointe chaque ligne à la main ; lignes invalides ignorées en silence ; réimport = doublons ;
pointage = une colonne (ni auteur, ni confiance, ni annulation tracée) ; facture « payée » par un drapeau saisi ; aucune facture proposée.

**Après** : relevé → aperçu chiffré → import tracé par lot (idempotent) → normalisation et indices → rapprochement automatique des cas certains →
propositions expliquées (score) pour les cas probables → Inbox pour les ambiguïtés et anomalies (agrégée par relevé) → règlement affecté aux factures
montant par montant → statut de paiement calculé → lettrage alimenté → pré-clôture.

## 1. Baseline de tests (lot D0)

Baseline officielle de départ : **687 tests** (0 échec, 0 erreur, 2 ignorés). Correction d'une erreur du rapport Phase C+ : `./mvnw test` n'exécute pas les
classes `*IT` ; les 28 tests d'intégration de la collecte, qui ne tournaient qu'avec `-Dtest`, ont été renommés `*Test` (659 + 28 = 687). Écart avec l'ancienne
référence de 661 : `RelancePieceServiceTest` (−6, supprimé : ancienne relance quotidienne remplacée par `RelanceCollecteService`), `RefusComptablePublisherTest`
(−3, obsolète : il testait `codeDe(texte)`, la classification par lecture du texte remplacée par des codes machine `AccountResolutionCode`), un test d'annulation de
`DemandePieceServiceTest` (−1, logique déplacée dans `CollecteService`), nouveaux tests. Les journaux de l'ancien run n'ayant pas été conservés, le décompte test par test
n'a pas pu être reconstitué à l'unité près.

## 2. Modèle bancaire final

Voir `docs/bank-domain.md`. `ligne_releve` **est** la transaction (étendue, V55) ; `bank_import_batch` ; `bank_reconciliation` (V56) ; `payment_allocation` (V54).
Statut de transaction **dérivé** (jamais une colonne qui pourrait diverger). Comptes de tiers/trésorerie : aucun compte codé en dur.

## 3. Import (`docs/bank-import.md`)

`BankStatementImporter` + `BankCsvParser` + `BankCsvMapping` : colonnes déduites de l'en-tête (FR/EN) ou mapping explicite, séparateur, guillemets, BOM, UTF-8 ou
ISO-8859-1, montants `1 234,56` / `1.234,56` / `1,234.56` / `(100)`, 4 formats de date. **Aperçu** (lignes lues, valides, invalides avec raison, doublons exacts, doublons
possibles, période, devise, cohérence des soldes) puis confirmation. Lot tracé (`BANK_STATEMENT_IMPORTED`) : qui, quand, fichier, empreinte, lignes rejetées.

## 4. Empreinte

SHA-256 de compte + date + montant + sens + référence + libellé normalisé + **rang d'occurrence**. Le montant seul ne fait jamais un doublon. `EXACT_DUPLICATE` : non
importé ; `POSSIBLE_DUPLICATE` : importé, marqué, constat `BANK_DUPLICATE_TRANSACTION` jusqu'à décision. Réimport du même fichier : 0 ligne créée (test), y compris sur 5 000 lignes.

## 5. Matching et 6. Score (`docs/reconciliation-engine.md`)

Décision de base : `RapprochementClassifier` (conservé). `RapprochementScorer` ajoute un score 0–100 **expliqué critère par critère** (montant 40, sens 10, date 15,
référence 20, tiers 10, libellé 5) ; la **référence** départage légitimement plusieurs mouvements de même montant ; deux transactions qui désignent le même mouvement
restent ambiguës. Garde-fou de **nature** : un libellé « frais bancaires », « retrait » ou « versement d'espèces » sans aucun point commun avec la cible n'est jamais rapproché
automatiquement (trouvé au test navigateur : « FRAIS TENUE DE COMPTE » avait été rapproché d'un règlement fournisseur de même montant). Aucun second moteur
(`BankMatchingEngineV2` n'existe pas) ; `MatchDecisionEngine` (IA, éteinte par défaut) est conservé.

## 7. Rapprochement (`BankReconciliation`)

Entité tracée : décision, confiance, source (`SYSTEM_EXACT`, `SYSTEM_PROPOSAL`, `USER`, `AI_PROPOSAL`, `LEGACY`), statut (`PROPOSED`, `CONFIRMED`, `REJECTED`, `REVERSED`),
qui/quand/motif. Une seule porte d'écriture (`BankReconciliationService`), invariant `CONFIRMED` ⇔ pointeur renseigné, verrou, index uniques. Un rejet ou une annulation sont
**mémorisés** : le système ne reprend jamais une paire défaite par un humain (défaut trouvé en préparant le test E2E : un contrôle automatique aurait re-rapproché ce qu'un humain venait
d'annuler). Application par lot des décisions du système. Un défaut de mon propre service (pointage manuel sur une cible neuve traité comme une proposition) a été trouvé par les tests et corrigé.

## 8. Allocation des paiements et 9. Lettrage (`docs/letting-and-payment-allocation.md`)

Option A validée : pas d'entité `Reglement`, la vérité reste l'écriture. `PaymentAllocation` relie ligne tiers du règlement ↔ ligne tiers de la facture, montant par montant, avec les
7 invariants demandés, idempotence, annulation tracée (`ACTIVE`/`REVERSED`), verrou de concurrence. Statut facture **calculé** (`NON_PAYEE`, `PARTIELLEMENT_PAYEE`, `PAYEE`), surplus **non affecté**.
Allocation automatique des seuls cas certains. Le lettrage est alimenté uniquement quand tout le groupe est intégralement alloué ; annulé avec l'allocation. `FactureDto.statutPaiement` expose
le statut calculé ; `marquerPayee` reste un geste manuel audité (`FACTURE_MARQUEE_PAYEE_MANUELLEMENT`), plus la source de vérité.

## 10. Constats

Réutilisés : `UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM`. Ajoutés (tous émis) : `BANK_DUPLICATE_TRANSACTION`, `PAYMENT_WITHOUT_INVOICE`, `OVERPAYMENT`,
`PAYMENT_ALLOCATION_NEEDS_REVIEW`. Non créés : `INVOICE_WITHOUT_PAYMENT` (une facture ouverte est un état normal), `UNEXPLAINED_BANK_DIFFERENCE` (déjà porté par la pré-clôture).
Opération saine → **zéro constat** (testé : 12 paires exactes ; 4 500 transactions exactes). Au-delà du seuil, **un constat par relevé** et par catégorie.

## 11. Lineage

Chaîne navigable par les données : transaction → `bank_reconciliation` (cible = ligne de trésorerie) → écriture du règlement → ligne tiers → `payment_allocation` → facture. **Limite** :
l'écran/DTO `AccountingLineage` existant n'a pas été étendu pour afficher ces maillons ; la navigation se fait par l'écran Banque et ses API, pas par le graphe de lineage.

## 12. Pré-clôture

`ControleTresorerieTiersCloture.banque` : lignes non pointées, **propositions à confirmer** (nouveau), écart solde du relevé / solde comptable (déjà présent), à partir des mêmes données.
Le solde de relevé est facultatif (« si disponible ») ; la cohérence ouverture + mouvements = clôture est vérifiée à l'import (aperçu). Elle n'est pas rejouée en pré-clôture : un relevé importé en
plusieurs fois n'aurait pas la somme du fichier d'origine.

## 13. Frontend

Écran Banque (`/entreprises/{id}/rapprochement`) : import avec aperçu et confirmation ; compteurs cliquables ; filtres compte / période / recherche ; liste avec statut, candidat, confiance,
« Pourquoi ? » (critères ✓/⚠) ; confirmer, rejeter, choisir un autre mouvement (recherche métier avec score), annuler avec motif, « deux opérations distinctes » ; validation en lot avec aperçu et seuil de
confiance ; onglet règlements à répartir (recherche de factures, montant modifiable, affectation partielle ou totale, annulation avec motif) ; onglet imports. Aucun identifiant n'est jamais saisi.
L'ancien écran est conservé sous `/rapprochement/classique`.

## 14. Performance (mesurée, `BanqueVolumeTest`)

5 000 transactions (4 500 exactes, 300 probables, 100 ambiguës, 100 sans correspondance), PostgreSQL réel, écritures générées en SQL à partir d'un modèle réel :

| Étape | Avant optimisation | Après |
|---|---|---|
| Import + rapprochement automatique | plus de 10 min (arrêté) | **26 s** (43 s avant le batching JDBC) |
| Passe de contrôles | — | **6 s** |
| Lecture de l'écran (synthèse + page) | — | **1,9 s, 15 requêtes SQL** pour 5 000 transactions |

Causes corrigées : un contrôle de toute la session à chaque confirmation individuelle (→ application par lot, aucune requête dans la boucle), recherche des candidats par balayage (→ index par montant), insertions
une par une (→ batching JDBC activé). Résultat : 4 500 rapprochés seuls, 300 propositions, **3 constats** dans l'Inbox (un par catégorie d'exception), pas de N+1 (< 120 requêtes en lecture).

## 15. Multi-tenant

Testé : compte bancaire d'une autre entreprise → 404 ; transaction A ↔ écriture B → 404 dans les deux sens ; confirmation / rejet / annulation d'un rapprochement d'une autre entreprise → refusés ; allocation avec une ligne
d'une autre entreprise → 404 ; lots d'import invisibles ; l'allocation automatique ne mélange jamais deux entreprises. Sécurité HTTP : `CLIENT` → 403 sur toutes les routes banque, anonyme → 401/403, cabinet jamais bloqué.
Les relevés ne sont jamais écrits dans les journaux (compteurs et empreinte seulement).

## 16. Audit

`BANK_STATEMENT_IMPORTED`, `BANK_TRANSACTION_MATCHED_AUTOMATICALLY`, `BANK_MATCH_PROPOSED`, `BANK_MATCH_CONFIRMED`, `BANK_MATCH_REJECTED`, `BANK_RECONCILIATION_REVERSED`, `PAYMENT_ALLOCATED`,
`PAYMENT_ALLOCATION_REVERSED`, `LETTRAGE_CREATED`, `LETTRAGE_REVERSED`, `FACTURE_MARQUEE_PAYEE_MANUELLEMENT`. (`BANK_TRANSACTION_MATCHED_AUTOMATICALLY` remplace `RAPPROCHEMENT_AUTO_APPLIQUE`.)

## 17. Tests

Nouveaux : `PaymentAllocationTest` (16), `PaiementsControleTest` (8), `PaiementsClientsTest` (3), `BankReconciliationTest` (18), `BanqueE2ETest` (2 : parcours complet par l'API, sécurité), `BanqueVolumeTest` (1),
`BankImportUnitTest` (12), `RapprochementScorerTest` (10), `PaiementFactureEnrichisseurTest` (2). Adaptés : `RapprochementBancaireServiceTest`, `…MatchingIaTest`, `MatchDecisionEngineTest`
(nouvelles dépendances), `DossierPiloteParExceptionTest` (événement d'audit renommé). Test navigateur réel : voir §18 « Navigateur ». Résultats finaux : voir §19.

**Navigateur (backend 8081 + frontend 4201, PostgreSQL)** : import d'un relevé de 6 lignes dont 1 invalide → aperçu (5 valides, 1 rejet « Date illisible », 5 à importer) → confirmation → 5 transactions
(2 rapprochées seules, 1 à confirmer, 1 ambiguë, 1 sans correspondance) ; « Pourquoi ? » détaille les critères ; confirmation du probable ; ambiguë : candidats avec score, recherche « lot B », choix manuel ;
sans correspondance : recherche ; **annulation avec motif** (bouton désactivé sans motif) d'un rapprochement erroné, qui ne revient pas ; filtres par compteur et par recherche ; validation en lot
(aperçu vide à 85 %, 1 proposition à 60 %) ; règlements à répartir : paiement partiel (reste 4 500, « Partiellement payée »), solde, annulation d'une affectation avec motif ; historique des imports.

## 18. Limites

* Seul le **CSV** est importé (pas de PDF, OFX, MT940, CAMT, Open Banking), conformément au périmètre.
* L'écran Banque montre les règlements/factures du **cabinet**, pas une vue dirigeant ; l'accès est refusé au rôle `CLIENT`.
* **Virement interne** (deux transactions opposées entre deux banques) : non finalisé ; il remonte comme transaction sans correspondance.
* Un **paiement qui couvre plusieurs factures** n'est jamais alloué automatiquement ; la répartition est manuelle (constat `PAYMENT_ALLOCATION_NEEDS_REVIEW`).
* Allocation automatique **uniquement** pour un même tiers, un même reste, sans concurrent ; tout le reste est humain.
* Le lineage n'affiche pas encore les maillons banque (voir §11).
* Les lignes bancaires **antérieures** à la Phase D n'ont pas d'empreinte (non protégées contre un réimport) ; leurs pointages sont repris en source `LEGACY`. Dans la reprise, une écriture pointée
  par deux transactions (possible avec l'ancien code) n'est conservée que pour la première.
* La validation des soldes du relevé se fait à l'import, pas en pré-clôture (voir §12).
* `Facture.statut = PAYEE` reste lu par le recouvrement et les relances : la bascule complète vers le statut calculé est progressive, comme demandé.
* Le calcul de l'écran Banque charge les transactions de la période en mémoire (confortable à 5 000, à reconsidérer au-delà de plusieurs dizaines de milliers).
* Les frais bancaires ne sont pas imputés automatiquement (aucun compte codé en dur ; le jour venu : `AccountPurpose` → `AccountingAccountResolver`).
* Le test de volume de l'import dure ~26 s : il fait partie de la suite (≈ 1,5 min avec le démarrage du contexte).

## 19. Résultats finaux (exécutés après la dernière correction)

| Suite | Résultat |
|---|---|
| `./mvnw test` (suite entière, relancée après le dernier correctif) | **760 tests, 0 échec, 0 erreur, 3 ignorés**, BUILD SUCCESS |
| `npm run build` | OK, sans erreur ni avertissement |
| `pytest` (ai-service, non modifié) | 328 passés |

Baseline de départ 687 → 760 (+73). Les 3 tests ignorés sont les outils de jeu de démonstration (Hub, Collecte, Banque), désactivés par défaut.
Deux défauts ont été trouvés par la suite complète elle-même et corrigés avant ce résultat : des doubles de test qui ne reflétaient plus l'API par lot, et
`ReleveBancaire.ajouterLigne` qui supposait une entreprise et un compte toujours renseignés.

