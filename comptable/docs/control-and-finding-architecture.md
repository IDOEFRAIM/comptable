# Architecture contrôle → constat → travail humain (Phase B, lots B1, B2, B4)

Avant : quatre systèmes de contrôle qui ne se parlaient pas (`ControlEngine` écrivant des Findings, `AnalyseFraudeService` stockant un rapport JSON
sur le document, `ClotureReadinessService` avec sa propre checklist, `MatchDecisionEngine`/`MissingEvidenceAnalyzer` pour la banque), plus une
convention cachée (`controlSeverity`/`controlCode` glissés dans `evidence_json`) pour que `ControlResult` survive à son passage en `Finding`.

Après : **un contrat, un réconciliateur, une unité de travail**.

```
 détecteur ──► ControlResult ──► ControlReconciler ──► Finding ──► Revue (Inbox)
 (lecture)     (constat)         (créer / mettre à      (unité du    clic, filtre,
                                  jour / résoudre)       travail      décision)
                                                         humain)
```

## 1. `ControlResult` — le contrat produit par les contrôles (B1)

`code`, `category`, `severity`, `status`, `entrepriseId`, `entityType`, `entityId`, `message`, `explanation`, `blocking`, `relatedEntityIds`
(navigation), `metadata` (valeurs : montants, compteurs — c'est l'« evidence »), `suggestedAction`, `detectedAt`.

* **Catégories** (limitées) : `ACCOUNTING, DOCUMENT, TAX, RECONCILIATION, THIRD_PARTY, STOCK, FIXED_ASSET, CLOSING, ANOMALY`.
* **Gravités** : `INFO, WARNING, ERROR, BLOCKING` ; `blocking` est *dérivé* de la gravité (jamais incohérent).
* **Codes** : `ControlCodes` (un code = une décision humaine possible) ; chaque code se rattache à un `TypeFinding` historique pour les écrans existants.

Qui produit des `ControlResult` :

| Source | Produit | Où |
|---|---|---|
| `ControlEngine` | `TAX_DECLARATION_MISSING/LATE`, `DOCUMENT_AT_RISK`, `DRAFT_ENTRY_OLD` | détecteur (`ControlDetector`) |
| `DetecteurPiecesManquantes` | `MISSING_DOCUMENT` | écriture d'achat sans pièce (`Document.ecritureId`) |
| `DetecteurIncoherenceTva` | `VAT_INCONSISTENCY` | HT + TVA ≠ TTC (`CoherenceMontants`, tolérance 0,02) |
| `DetecteurImmobilisations` | `FIXED_ASSET_WITHOUT_SOURCE`, `POSSIBLE_DOUBLE_ACQUISITION` | registre ↔ factures d'achat |
| `DetecteurSoldesTiers` | `CLIENT_CREDIT_BALANCE`, `SUPPLIER_DEBIT_BALANCE` | un seul agrégat SQL |
| `DetecteurStock` | `STOCK_INCONSISTENCY` | quantité/valeur incohérentes selon la politique de stock négatif |
| `DetecteurRapprochementBancaire` | `UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM` | classification déterministe (§4) |
| `RefusComptablePublisher` (moteur) | `ACCOUNT_NOT_CONFIGURED`, `ACCOUNT_AMBIGUOUS`, `ACCOUNT_INVALID` | refus d'imputation du moteur |
| `MissingEvidenceAnalyzer` (IA, désactivée par défaut) | `MATCH_TO_CONFIRM`, `UNMATCHED_PAYMENT` | décision `MatchDecisionEngine` |
| `ClotureReadinessService` | consomme les Findings **+** ses contrôles propres de clôture | voir `accounting-review-inbox.md` |
| `AnalyseFraudeService` | `ControlResult` via adaptateur (`depuisRapportFraude`) ; les documents à risque remontent par `DOCUMENT_AT_RISK` | lecture seule |

**Anti-explosion** : un détecteur *agrège* quand le volume le justifie. 21 écritures d'achat sans pièce → **un** constat `PIECES_MANQUANTES` ;
51 transactions sans correspondance sur un relevé → **un** constat `RELEVE` ; 21 brouillons anciens → **un** constat `ECRITURES_BROUILLON`. Les
opérations saines ne produisent jamais rien.

## 2. `Finding` — l'unique unité de travail humain (B2)

Aucune table `AccountingAlert`/`ReviewTask`/… : le `Finding` existant est étendu (migration V51).

Colonnes ajoutées : `control_code`, `category`, `control_severity`, `resolution_type` (`MANUAL`/`SYSTEM`/`DISMISSED`), `resolution_comment`,
`last_detected_at`. `severity` (CRITICAL/IMPORTANT/REVIEW) reste la projection d'affichage historique de `control_severity`.

**Clé de déduplication métier** : *(entreprise, control_code, source_type = entityType, source_id = entityId)* parmi les constats actifs
(`OPEN`/`IN_PROGRESS`), **garantie par un index unique partiel** (`uq_finding_actif`).

`FindingService.declarer(entreprise, ControlResult, type)` :
1. un constat actif existe → **mis à jour** (gravité, texte, valeurs) — jamais dupliqué ; `last_detected_at` rafraîchi au plus une fois par heure ;
2. sinon un constat **écarté** (`DISMISSED`) existe pour la même clé → **rien n'est recréé** tant que la cause persiste ;
3. sinon → créé (`FINDING_CREE`).

**Cycle de vie** : `OPEN → RESOLVED | DISMISSED` (`IN_PROGRESS` existe mais n'est pas nécessaire au parcours).
* `RESOLVED` / `SYSTEM` : la cause a disparu au passage suivant des contrôles (`FINDING_RESOLU_AUTO`) ;
* `RESOLVED` / `MANUAL` : résolu par un humain (`FINDING_RESOLU`) ;
* `DISMISSED` : écarté par un humain **avec motif obligatoire** (400 sinon) (`FINDING_REJETE`). Écarter n'est pas supprimer : la ligne reste
  consultable (`resolution_comment`, `resolved_by`, `resolved_at`) et auditée.
* Rien n'est jamais supprimé.

## 3. `ControlReconciler`, `ControlRunService`, `control_run`

* `ControlDetector` : `codes()` (périmètre dont le détecteur est l'unique autorité) + `detecter(entreprise)` = **tous** les constats actuellement vrais.
* `ControlReconciler.reconcilier(entreprise, codes, detectés)` : déclare chaque constat `OPEN`, puis **ferme (SYSTEM)** les Findings actifs du périmètre
  qui n'ont plus de constat. Un détecteur en échec n'écrit rien et ne ferme rien (jamais de résolution sur une détection incomplète).
* `ControlRunService.executer` : (1) pointe automatiquement les rapprochements bancaires **EXACTS** (CERTAIN → automatisé, audité), (2) exécute les
  détecteurs, (3) journalise le passage dans `control_run` (durée par détecteur, créés/mis à jour/résolus).
* `executerSiPerime` : **pas de recalcul complet à chaque GET** — la dernière exécution est réutilisée pendant `controles.ttl-secondes` (15 s par
  défaut) ; exécutée dans une transaction indépendante (`REQUIRES_NEW`) car les lectures (dossier, cockpit, copilote) sont en lecture seule.
  La clôture force un passage frais (`ClotureReadinessService.actualiserControles`).
* `POST /api/entreprises/{e}/revue/executer` relance explicitement les contrôles.

## 4. Rapprochement bancaire déterministe (B4)

`RapprochementClassifier` (fonction pure, partagée par l'import, le rapprochement relancé et le détecteur) classe une ligne de relevé face aux
mouvements validés du compte de trésorerie (même sens et même montant, hors mouvements déjà pointés) :

| Statut | Règle | Conséquence |
|---|---|---|
| `EXACT` | un seul mouvement, revendiqué par cette seule ligne, à ≤ 5 jours | **pointé automatiquement** (CERTAIN), audit `RAPPROCHEMENT_AUTO_APPLIQUE` |
| `PROBABLE` | un seul mouvement mais date plus éloignée | `MATCH_TO_CONFIRM` (WARNING→INFO) : *proposer* |
| `AMBIGUOUS` | ≥ 2 mouvements, **ou** un même mouvement revendiqué par plusieurs lignes | `AMBIGUOUS_BANK_MATCH` listant tous les candidats ; **jamais le premier** |
| `UNMATCHED` | aucun mouvement | `UNMATCHED_PAYMENT` |

Changement de comportement assumé : avant, un unique candidat par montant était pointé quelle que soit sa date, et deux lignes de même montant se
disputant un mouvement unique voyaient la première le prendre. Désormais seul l'EXACT est automatisé. Les propositions de l'IA (`aiMatchProposalId`)
restent à la revue : le détecteur ne les ferme pas, l'IA n'est jamais la vérité comptable.

Chaîne prévue pour les autres rapprochements (progressive, sans moteur universel) : Document ↔ Facture (`MISSING_DOCUMENT`, `DOCUMENT_AT_RISK`),
Facture ↔ Règlement (soldes de tiers), Règlement ↔ Banque (ci-dessus), Banque ↔ Tiers (`CLIENT_CREDIT_BALANCE`, `SUPPLIER_DEBIT_BALANCE`),
Immobilisation ↔ Facture (`FIXED_ASSET_WITHOUT_SOURCE`, `POSSIBLE_DOUBLE_ACQUISITION`), Écriture ↔ Source (lineage B0).

## 5. Refus du moteur → constat d'imputation

`proposerEtValider*` refuse en 422 et annule sa transaction. `RefusComptablePublisher` écrit donc le constat en **transaction indépendante**
(comme l'audit du refus) : il survit au rollback. Le code (`ACCOUNT_NOT_CONFIGURED/AMBIGUOUS/INVALID`) est déduit de l'explication du refus ;
une explication sans rapport avec un compte ou un journal (ex. escompte non géré) n'est pas publiée. Quand la **même cause** aboutit plus tard
(`validerInterne`), les constats d'imputation ouverts sur cette source se ferment automatiquement. Une opération saisie sans cause métier n'est
pas publiée : l'utilisateur a déjà la réponse en direct.

## 6. Règle de décision finale

| État | Traitement |
|---|---|
| CERTAIN | automatisé (rapprochement EXACT, écriture du moteur) |
| NORMAL | ne dérange pas : aucun constat |
| PROBABLE | proposé (`MATCH_TO_CONFIRM`) |
| AMBIGU | Finding (`AMBIGUOUS_BANK_MATCH`, `ACCOUNT_AMBIGUOUS`) — jamais tranché par le système |
| ANORMAL | Finding (solde de tiers, stock, TVA…) |
| BLOQUANT | empêche l'action (clôture, comptabilisation) et ouvre un constat `BLOCKING` |
| RÉSOLU | disparaît du travail restant (SYSTEM), reste en base et dans l'audit |

## 7. Audit (noms français)

`FINDING_CREE`, `FINDING_MIS_A_JOUR`, `FINDING_RESOLU` (humain), `FINDING_RESOLU_AUTO` (système), `FINDING_REJETE` (écarté),
`FINDING_ASSIGNE`, `RAPPROCHEMENT_AUTO_APPLIQUE`, `DOCUMENT_LIE_A_ECRITURE`, `SOURCE_COMPTABLE_LIEE`.

## 8. Composants historiques supprimés ou remplacés

* `FindingService.signaler(...)` (dédoublonnage sur (source, OPEN), un seul constat par objet même pour deux contrôles) → `declarer(...)`.
* `ControlEngine.analyser(entreprise)` (écriture directe de Findings à chaque lecture du dossier) → détecteur + `ControlRunService`.
* Convention `evidence.controlSeverity` / `evidence.controlCode` et `ControlResultAdapters.categorieDe` → colonnes `control_severity`, `control_code`, `category`.
* Catégories `FISCAL` / `BANK` → `TAX` / `RECONCILIATION`.
* Heuristique « premier candidat par montant » du rapprochement bancaire → classification déterministe.
