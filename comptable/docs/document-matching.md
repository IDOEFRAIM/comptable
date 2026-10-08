# Rapprochement et détection de doublons des documents (Phase C, lots C5, C7 à C10, C16)

Principe : **CERTAIN → automatique ; PROBABLE → proposé ; AMBIGU → constat ; DOUBLON → constat ; ILLISIBLE → constat.** Aucun choix « de la première candidate », jamais.

## 1. Identification du tiers (C7) — `DocumentClassifier`

Nom lu par l'extraction (`donnees_ocr.fournisseur`) comparé aux tiers de l'entreprise (fournisseurs pour un achat, clients pour une vente). Normalisation : accents, majuscules, ponctuation et formes juridiques (SARL, SA, ETS…) ignorés.

| Qualité | Règle | Confiance | Suite |
|---|---|---|---|
| `EXACT` | un seul tiers au nom normalisé identique | 0,97 | la pièce peut progresser seule |
| `PROBABLE` | un seul tiers au nom proche (inclusion, mots communs) | < 0,90 | tiers proposé, **pas de rapprochement automatique** |
| `AMBIGU` | plusieurs tiers également plausibles (ou même nom normalisé) | — | constat `DOCUMENT_AMBIGUOUS`, aucun tiers retenu |
| `INCONNU` | aucun tiers | — | la pièce attend la création du fournisseur, puis « Réanalyser » |

La confiance de 51 % de l'exemple de la mission ne peut pas progresser : seul `EXACT` autorise la suite.

## 2. Matching Document ↔ Facture (C8) — `DocumentMatcher`

Même philosophie que `MatchDecisionEngine` (EXACT / PROBABLE / AMBIGUOUS / UNMATCHED), sans second moteur concurrent : c'est une règle déterministe sur des factures, pas sur des transactions bancaires.

**Facture d'achat** (`FactureFournisseur`, dont le `numero` est interne ; le numéro du fournisseur est `numero_fournisseur`, facultatif) :

| Situation | Niveau |
|---|---|
| numéros des deux côtés, égaux, même TTC (± 0,01) | `EXACT` |
| numéros égaux, TTC différent | **jamais un rapprochement** → constat `DOCUMENT_AMOUNT_MISMATCH` |
| numéros des deux côtés, différents | autre facture : exclue |
| numéro absent d'un côté, même TTC, date à ≤ 5 jours | `PROBABLE` |
| sinon | `POSSIBLE` (listée dans le sélecteur, jamais proposée seule) |

**Facture de vente** (`Facture`, le numéro est le nôtre) : numéro normalisé égal + TTC égal = `EXACT`.

**Décision** : un seul EXACT → `EXACT` ; plusieurs EXACT → `AMBIGUOUS` ; sinon un seul PROBABLE → `PROBABLE`, plusieurs → `AMBIGUOUS` ; aucun → `UNMATCHED`. Une candidate refusée par l'utilisateur (`cible_rejetee_id`) n'est plus jamais reproposée, même après « Réanalyser ».

| Décision | `statut_rapprochement` | Effet |
|---|---|---|
| `EXACT` (tiers `EXACT` ou vente) | `RAPPROCHE`, origine `SYSTEME`, confiance 1,0 | lien posé ; numéro du fournisseur reporté sur la facture s'il manquait ; piece = justificatif de l'écriture de la facture |
| `PROBABLE` | `A_CONFIRMER` + `cible_*` = suggestion | file de suggestions du Hub (**pas de Finding** : l'UX a sa propre file) |
| `AMBIGUOUS` | `AMBIGU`, aucune cible | constat `DOCUMENT_AMBIGUOUS` listant les candidates |
| `UNMATCHED` | `NON_RAPPROCHE` | normal pour une pièce « à comptabiliser » ; constat `DOCUMENT_UNMATCHED` (info) après `documents.non-rapproche-jours` (7) |

**Garde anti double-comptabilisation** : une pièce rapprochée à une facture déjà comptabilisée reçoit son `ecriture_id` ; `ComptabilisationService` refuse de la comptabiliser séparément (409). Sans cela, la cause `FACTURE_FOURNISSEUR` et la cause `DOCUMENT` (B0) auraient pu produire deux écritures.

## 3. Doublons (C5, C16) — `DetecteurDocuments`

| Niveau | Règle | Code | Certitude |
|---|---|---|---|
| 1 | même `content_hash` dans l'entreprise | `EXACT_DUPLICATE` | certain |
| 2 | même tiers + même numéro normalisé (autre fichier) | `DUPLICATE_INVOICE` | certain |
| 3 | même tiers + même TTC + dates proches (3 j) + numéro absent d'un côté | `POSSIBLE_DUPLICATE` | **suspicion** (gravité INFO) |

* la pièce la plus ancienne du groupe est l'**original** et n'est jamais signalée ; les suivantes portent le constat (`relatedEntityIds` = l'original) ;
* une pièce n'a qu'un seul constat de doublon (exact > facture > possible) ;
* le hash est **par entreprise** : le même fichier chez deux entreprises n'est pas un doublon ;
* **marquer doublon** (action humaine, l'original est choisi dans une liste) : `doublon_de_id`, la pièce sort du travail, le constat se ferme, rien n'est supprimé ; **écarter** le constat (motif obligatoire) : jamais recréé tant que la cause persiste (clé de dédoublonnage de la Phase B) ;
* au-delà de 20 constats d'un même code, un seul constat agrégé (`DOCUMENTS`) porte les identifiants.

Autres constats : `DOCUMENT_UNREADABLE` (extraction en erreur), `DOCUMENT_AMBIGUOUS`, `DOCUMENT_UNMATCHED`, `DOCUMENT_AMOUNT_MISMATCH`. Seuls les codes réellement détectés existent.

## 4. Document ↔ Transaction bancaire (C9)

**Préparé, pas automatisé.** Le rapprochement bancaire (Phase B) classe déjà ligne de relevé ↔ écriture ; une pièce **rapprochée à une facture comptabilisée** relie donc indirectement document → facture → écriture → ligne de relevé via le lineage. Aucun lien direct document ↔ ligne de relevé n'a été créé : il n'y a pas de signal fiable (montant + date + libellé) qui ne se règle déjà par l'écriture. À reprendre si des paiements sans écriture doivent être justifiés.

## 5. Lineage documentaire (C10)

* `GET /lineage/documents/{id}` : document → facture visée → propositions comptables → écritures (et écritures issues directement de la pièce) ;
* `GET /lineage/ecritures/{id}` : l'écriture liste ses pièces (`DIRECT` ou `VIA_FACTURE`) ;
* `GET /lineage/sources/{type}/{id}` : une facture liste les pièces qui lui sont rapprochées.

Uniquement des liens enregistrés (`cible_*`, `ecriture_id`, source de la proposition) — aucune recherche floue.

## 6. Actions humaines (C14, C15) — `DocumentTraitementService`

Toutes vérifient l'entreprise (404), refusent l'incohérent (409/422) et sont tracées : `associer` (parmi les candidates, jamais un identifiant saisi), `rejeterProposition`, `marquerDoublon`, `valider`, `retraiter`.
**Validation en masse** : l'aperçu liste uniquement les rapprochements **certains faits par le système** non encore validés ; la validation prend les identifiants vus dans l'aperçu et **ignore (en le signalant)** toute pièce dont l'état a changé entre-temps.

## 7. Audit

`DOCUMENT_RECU`, `DOCUMENT_EXTRAIT`, `DOCUMENT_CLASSE`, `DOCUMENT_RAPPROCHE` (auto ou humain), `DOCUMENT_RAPPROCHEMENT_PROPOSE`, `DOCUMENT_RAPPROCHEMENT_REJETE`, `DOCUMENT_MARQUE_DOUBLON`, `DOCUMENT_VALIDE`, `DOCUMENT_DEMANDE`, `DOCUMENT_RECU_APRES_DEMANDE`, `DOCUMENT_LIE_A_ECRITURE` (+ les événements de constats de la Phase B).
