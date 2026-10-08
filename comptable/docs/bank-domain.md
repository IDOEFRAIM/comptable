# Banque — domaine et audit de l'existant (Phase D, lots D0–D1)

Document vivant : la section « Audit » est figée avant tout code ; les sections suivantes seront complétées au fil de la phase.

## Audit de l'existant (lecture du code, aucune modification)

### Cartographie

```
CSV (date;libelle;montant signé)                      RapprochementBancaireService.importer / parserCsv
   │  colonnes figées [0][1][2], 2 formats de date, séparateur ; ou ,
   ▼
ReleveBancaire (compte_id = COMPTE COMPTABLE, dates, solde_debut/solde_fin saisis à la main)
   └─ LigneReleve  (date, libelle, montant signé, ligne_ecriture_id nullable)        ← « BankTransaction » de fait
   │
   ▼  RapprochementClassifier.classer  (fonction pure : EXACT / PROBABLE / AMBIGUOUS / UNMATCHED)
   │    candidats = LIGNES D'ÉCRITURE du compte de trésorerie (même montant, fenêtre ±15 j ; EXACT si ≤ 5 j)
   │    EXACT → LigneReleve.ligne_ecriture_id posé directement (import + RapprochementAutomatiqueService.appliquerExacts, audit RAPPROCHEMENT_AUTO_APPLIQUE)
   ▼  filets de secours : MatchingSemantiqueService (IA) puis Universal Matching Engine (désactivé par défaut, agent.matching-ia.enabled)
   │    MatchDecisionEngine = « Java Control » d'une réponse IA (re-valide existence, tenant, disponibilité, écart ≤ 20 %, seuils 0,95 / 0,70)
   ▼
DetecteurRapprochementBancaire → ControlResult → Finding : UNMATCHED_PAYMENT (agrégé > seuil), AMBIGUOUS_BANK_MATCH, MATCH_TO_CONFIRM
   ▼
ControleTresorerieTiersCloture.banque (pré-clôture) : lignes non pointées + écart solde relevé / solde comptable (soldeNetAvant)
```

Règlement client/fournisseur = **une écriture** produite par `ReglementRule` via `AccountingOperationEngine` (banque ↔ compte tiers).
Facture ↔ règlement = **lettrage** de lignes d'écriture du compte tiers (`ligne_ecriture.lettrage`, `date_lettrage`, `LettrageService`,
partiel possible : code commun, écart renvoyé). Il n'existe **ni entité `Reglement`, ni allocation montant par montant**.

### Constats

| # | Constat | Conséquence |
|---|---|---|
| 1 | `LigneReleve` n'a ni fingerprint, ni lot d'import, ni sens explicite, ni devise, ni libellé normalisé, ni référence, ni statut | un réimport crée des doublons (aucune idempotence) |
| 2 | Pas de `BankImportBatch` : on ne sait ni qui a importé, ni quand, ni quel fichier, ni combien de lignes rejetées (les lignes invalides sont ignorées **silencieusement**) | traçabilité nulle |
| 3 | Pas d'aperçu avant import ; mapping de colonnes figé ; pas de colonnes débit/crédit séparées ; séparateur décimal décidé par `replace(",", ".")` (ambigu pour `1.234,56`) | fragile sur des CSV réels |
| 4 | Il n'y a pas de « compte bancaire » : `releve_bancaire.compte_id` = compte comptable de trésorerie | acceptable : on le conserve comme identité du compte |
| 5 | Le rapprochement est un simple champ `ligne_ecriture_id` : pas de qui/quand/pourquoi/confiance, **pas de rejet mémorisé, pas d'annulation tracée** (`depointer` écrase en silence) | il faut une entité de rapprochement |
| 6 | Le classifieur ne regarde que le **montant** et la date (aucun tiers, référence, numéro de facture, libellé) ; sens déduit du signe | pas de score explicable ; une proposition « probable » = « même montant, date éloignée » |
| 7 | Les candidats sont uniquement des écritures du compte de trésorerie : factures non réglées, tiers, documents ne sont jamais proposés (sauf Universal Matching Engine IA, éteint) | un paiement ne remonte pas à sa facture |
| 8 | `MatchDecisionEngine` valide une **proposition IA** ; il n'est pas un moteur de scoring déterministe | à étendre pour le contrôle, pas à doubler |
| 9 | `Facture.statut` : `VALIDEE → PAYEE` par action manuelle `marquerPayee` ; `FactureFournisseur` n'a pas de statut de paiement | « flag manuel incohérent avec les montants », exactement le défaut décrit par la mission (D8) |
| 10 | Le lettrage partiel ne mémorise pas d'allocation (quel règlement couvre quelle facture, pour combien) : un virement pour 3 factures ou 3 virements pour 1 facture ne sont pas représentables | à modéliser |
| 11 | Les constats bancaires utilisent déjà `ControlResult` → `Finding` (codes `UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM`) ; agrégation > seuil déjà en place | **à conserver**, ne pas créer `BANK_*` en doublon |
| 12 | Pré-clôture banque : calcule déjà non pointées + écart de solde ; `soldeFin` du relevé est saisi par l'utilisateur, jamais vérifié contre la somme des lignes | ajouter la vérification d'équilibre du relevé |
| 13 | Lineage : aucun maillon relevé → écriture | à brancher |
| 14 | Frontend : un écran `rapprochement-bancaire` (647 lignes) : relevé → pointage manuel ; pas de statuts EXACT/PROBABLE/AMBIGU, pas d'aperçu d'import, pas d'actions groupées | à refondre |
| 15 | Tests existants : `RapprochementClassifierTest` (6), `RapprochementBancaireServiceTest` (3), `…MatchingIaTest` (2), `MatchDecisionEngineTest` (15), `LettrageServiceTest` (17), `ControlesClotureTest` (19, 0 test exécuté dans le dernier run) | base à préserver |

### Décisions de conservation / extension / unification

| Élément | Décision |
|---|---|
| `ReleveBancaire`, `LigneReleve` (= BankTransaction) | **ÉTENDRE** (colonnes ajoutées par migration), pas de nouvelle table `bank_transaction` |
| `RapprochementClassifier` (EXACT/PROBABLE/AMBIGUOUS/UNMATCHED) | **ÉTENDRE** : scoring explicable multi-critères, candidats élargis (factures, règlements) — pas de `BankMatchingEngineV2` |
| `MatchDecisionEngine` | **CONSERVER** pour la couche IA optionnelle ; le résultat déterministe garde la priorité |
| `RapprochementAutomatiqueService`, `DetecteurRapprochementBancaire` | **ÉTENDRE** (même chemin ControlResult → Finding) |
| `LettrageService` + `ligne_ecriture.lettrage` | **CONSERVER** comme lettrage comptable ; **AJOUTER** une allocation de paiement montant par montant (nouvelle entité), le statut de facture devient calculé |
| `Facture.marquerPayee` (flag manuel) | **UNIFIER** : statut de paiement dérivé des allocations ; l'action manuelle devient un cas particulier tracé |
| Pointage direct `ligne_ecriture_id` | **UNIFIER** derrière une entité `RapprochementBancaire` (source, confiance, qui, quand, annulable) ; la colonne reste en lecture dérivée pendant la transition |
| Import CSV inline dans `RapprochementBancaireService` | **EXTRAIRE** vers `BankStatementImporter` (justifié : preview + mapping + lot) |
| Codes `UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM` | **CONSERVER** ; n'ajouter que `BANK_DUPLICATE_TRANSACTION`, `PAYMENT_WITHOUT_INVOICE`, `INVOICE_WITHOUT_PAYMENT`, `OVERPAYMENT` s'ils sont réellement émis |
| Matching IA / WhatsApp de résolution | **CONSERVER** tel quel (éteint par défaut) |
| Rien | **SUPPRIMER** : rien à supprimer à ce stade |

### Point d'architecture à trancher (voir le rapport d'audit)

Une facture est réglée par une **écriture de règlement** ; le « règlement » n'existe pas comme objet. Il y a deux manières de modéliser
l'allocation (voir `docs/letting-and-payment-allocation.md` quand il sera écrit) : (a) allocation entre une *ligne d'écriture de règlement*
et une *ligne d'écriture de facture*, montant par montant (fidèle au lettrage et au grand livre, aucun nouvel objet métier concurrent) ;
(b) entité `Reglement` autonome. La recommandation est (a).

---

## Modèle bancaire final (après la Phase D)

Pas de nouvelle table « transaction » : **`ligne_releve` EST la transaction bancaire**, étendue (V55) ; un relevé = `releve_bancaire` (compte comptable de
trésorerie + période + soldes **facultatifs**) ; un import = `bank_import_batch` ; un rapprochement = `bank_reconciliation` (V56) ; une affectation de
règlement = `payment_allocation` (V54).

| Besoin du cahier | Où |
|---|---|
| `entrepriseId`, `compteBancaireId` | `ligne_releve.entreprise_id`, `compte_id` (le compte comptable de trésorerie est l'identité du compte bancaire) |
| `dateOperation`, `dateValeur` | `date_operation`, `date_valeur` |
| `amount`, `currency`, `direction` | `montant` (signé), `currency`, `direction` (`CREDIT`/`DEBIT`, dérivée du signe) |
| `labelOriginal` / `labelNormalized` | `libelle` (jamais modifié) / `libelle_normalise` |
| `reference`, `counterpartyName` | `reference`, `counterparty_name` (+ `payment_method`, `invoice_hint` : indices, jamais des vérités) |
| `source`, `importBatchId` | `source` (`CSV`), `import_batch_id` |
| `contentFingerprint` | `content_fingerprint` (SHA-256, unique par entreprise et compte) |
| `status` | **dérivé** (rapprochée / à confirmer / ambiguë / sans correspondance) : pas de colonne, pour ne jamais pouvoir diverger de `bank_reconciliation` |
| `createdAt` | `releve_bancaire.created_at` |

## Pipeline final

```
CSV ─▶ aperçu ─▶ import (lot) ─▶ LigneReleve (empreinte, libellé normalisé, indices)
    ─▶ candidats (écritures de trésorerie, index par montant) ─▶ RapprochementScorer (score expliqué)
    ─▶ EXACT        ─▶ BankReconciliation CONFIRMED / SYSTEM_EXACT   (automatique, audité)
       PROBABLE     ─▶ BankReconciliation PROPOSED                    (le comptable confirme / rejette)
       AMBIGUOUS    ─▶ constat AMBIGUOUS_BANK_MATCH                   (le comptable choisit un candidat)
       UNMATCHED    ─▶ constat UNMATCHED_PAYMENT                      (le comptable cherche)
    ─▶ ligne de trésorerie du règlement ─▶ PaymentAllocation ─▶ factures ─▶ statut calculé ─▶ lettrage
    ─▶ ControlResult ─▶ Finding ─▶ Inbox (exceptions uniquement)      ─▶ pré-clôture « Banque »
```

## Éléments de l'audit : décision finale

| Élément | Résultat |
|---|---|
| `ReleveBancaire`, `LigneReleve` | **étendus** (V55), aucune table concurrente |
| `RapprochementClassifier` | **conservé** (décision de base), enrichi par `RapprochementScorer` ; indexation par montant |
| `MatchDecisionEngine` | **conservé** (IA désactivée par défaut) ; ses pointages passent par `BankReconciliationService` |
| `RapprochementAutomatiqueService`, `DetecteurRapprochementBancaire` | **étendus** (même chemin ControlResult → Finding, agrégation par relevé) |
| `LettrageService` | **conservé** ; reçoit `lettrerGroupeSolde` / `delettrerGroupe` pour que l'allocation l'alimente |
| `Facture.marquerPayee` | **supprimé en Phase E (E0.1)** : le statut de paiement n'est plus saisi, il est **calculé** (montant − Σ affectations actives) par `StatutPaiementFactureService` ; voir « Dettes de la phase D résolues » |
| pointage direct `ligne_ecriture_id` | **unifié** derrière `BankReconciliation` (invariant `CONFIRMED` ⇔ pointeur) |
| import CSV inline | **extrait** vers `BankStatementImporter` |
| `RapprochementBancaireService.parserCsv` | **supprimé** (remplacé par `BankCsvParser`) |
| codes `UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM` | **conservés** ; ajoutés : `BANK_DUPLICATE_TRANSACTION`, `PAYMENT_WITHOUT_INVOICE`, `OVERPAYMENT`, `PAYMENT_ALLOCATION_NEEDS_REVIEW` (tous émis) |
| `INVOICE_WITHOUT_PAYMENT`, `UNEXPLAINED_BANK_DIFFERENCE` | **non créés** : une facture ouverte est un état normal, l'écart de solde est déjà porté par la pré-clôture |

## Dettes de la phase D résolues (Phase E, E0)

### E0.1 — `PAYEE` n'est plus une source de vérité
* Usages classés avant suppression : **lecture** (recouvrement, relances, scoring clients, prévision de trésorerie, historique de matching), **écriture** (`FacturationService.marquerPayee`
  + endpoint `marquer-payee`), **frontend** (bouton « marquer payée »), **test** (3 cas du service), **reporting** (score du dossier).
* Toute lecture passe par `StatutPaiementFactureService` (`ouvertes`, `payees`, `situationsParEcriture`) : une facture est en retard tant qu'il reste à payer, et un paiement partiel
  la laisse en retard. `CashForecastService` prévoit le **reste** à encaisser (plus le TTC).
* La mutation manuelle est supprimée (endpoint, service, bouton, tests devenus sans objet — justifié : il n'y a plus de geste à tester). Le bouton est remplacé par l'indication
  « le statut se calcule à partir des affectations ».
* Migration `V57` : chaque facture manuellement `PAYEE` est consignée dans `migration_report` (`FACTURE_PAYEE_MANUELLE`), puis repassée en `VALIDEE`. **Aucune** affectation n'est
  inventée : l'ancien drapeau n'avait ni règlement ni montant.

### E0.2 — Virements internes (banque A → banque B)
* Table `bank_internal_transfer` (V58) : deux jambes (débit, crédit), montant, décision EXACT / PROBABLE / AMBIGUOUS, source, statut PROPOSED / CONFIRMED / REJECTED / REVERSED.
* `InternalTransferMatcher` (pur) note : montant (40), sens opposé (10), comptes différents (10), date (20/10), libellé de virement (20/10), référence commune (10) ; fenêtre 3 jours.
  EXACT exige ≤ 2 jours et un libellé de virement ou une référence commune ; PROBABLE est **proposé** ; AMBIGU devient un constat (`INTERNAL_TRANSFER_AMBIGUOUS`), jamais un choix arbitraire.
* Les jambes sortent du rapprochement avec les tiers (nature `VIREMENT_INTERNE`) : ni charge, ni produit, ni règlement.
* **L'écriture n'est jamais inventée** : si une écriture de virement existe déjà, les jambes y sont rapprochées ; sinon constat `INTERNAL_TRANSFER_WITHOUT_ENTRY`.
* Écran Banque : tuile « Virements internes », actions confirmer / « ce n'est pas un virement interne » / annuler (motif).

### E0.3 — Empreintes des anciennes transactions
`BankFingerprintBackfillService` (`simuler()` sans effet, `appliquer()`) : calcule l'empreinte d'une ancienne transaction **seulement** si elle est légitime. Libellé vide →
« insuffisante » (jamais date + montant seuls) ; même empreinte déjà portée par une autre transaction (typiquement deux imports d'un même fichier) → **collision rapportée, rien
n'est supprimé ni modifié** ; le rang d'occurrence ne distingue que des lignes identiques d'un même relevé. Rapport (`total`, `calculables`, `posées`, `collisions`,
`insuffisantes`) consigné dans `migration_report` (`BANK_FINGERPRINT_BACKFILL`, `_COLLISION`, `_INSUFFISANT`). Idempotent. Lancement : `banque.backfill-empreintes=true`.

### E0.4 — Pagination, filtres et tri côté serveur
`GET /banque/transactions?compteId&periode&statut&q&tri=date|montant|libelle&ordre=asc|desc&page&taille` (taille ≤ 200).
Les états **persistés** se filtrent en SQL (`rapprochée`, `virement interne confirmé`, « non expliquée » = tout le reste) ; seules les transactions affichées, ou les non expliquées,
reçoivent un classement. Le classement d'une ligne ne dépend que des lignes du même relevé et du même montant signé, donc classer une page donne **le même résultat** qu'un
classement complet. Pourquoi ?, confirmation, annulation, lot et répartition sont inchangés (mêmes DTO). Index `V59`.

Mesures (PostgreSQL, 90 % rapprochées / 10 % à traiter, `BanquePaginationEchelleTest`) :

| Transactions | page de 25 | page profonde | filtre rapprochée | recherche | filtre « sans correspondance » | synthèse |
|---|---|---|---|---|---|---|
| 5 000 | 158 ms / 11 SQL | 63 ms / 7 SQL | 67 ms | 55 ms | 670 ms / 9 SQL | 547 ms / 11 SQL |
| 20 000 | 114 ms / 15 SQL | 70 ms / 7 SQL | 240 ms | 96 ms | 1 532 ms / 13 SQL | 1 157 ms / 15 SQL |
| 50 000 | 134 ms / 19 SQL | 83 ms / 7 SQL | 169 ms | 108 ms | 2 529 ms / 17 SQL | 3 457 ms / 19 SQL |

Une page pèse ≈ 8,7 Ko de JSON quel que soit le volume ; la mémoire retenue par une page reste de quelques Mo. Les vues **calculées** (« sans correspondance », synthèse) croissent avec
le volume **non expliqué**, pas avec l'historique : à 50 000 transactions dont 5 000 à traiter, ≈ 120 Mo et 2,5–3,5 s. Aucun seuil arbitraire n'a été fixé ; si ce volume non expliqué
devient courant, la prochaine étape serait de persister le classement (non fait : aucune donnée ne le demande aujourd'hui).
