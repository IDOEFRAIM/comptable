# Espace de travail des Notes annexes (F8.1)

Backend et contrat API. Aucun écran Angular. Les faits réglementaires (matrice, closeout, JSON de statut) ne sont pas modifiés : seul l'état d'implémentation l'est.

## 1. Modèle persistant

Table `financial_statement_note` (migration `V64`), une ligne par `(entreprise, exercice, cadre, version de définition, note)` :

| Colonne | Rôle |
|---|---|
| `applicability_status` | `TO_DETERMINE`, `NOT_APPLICABLE_CANDIDATE` (proposition du moteur), `NOT_APPLICABLE` (décision), `APPLICABLE` |
| `applicability_decided_by / _at / _reason` | non nul = décision **humaine** ; nul = statut calculé, recalculé à chaque lecture |
| `completion_status` | `INCOMPLETE`, `READY`, `MANUAL_REQUIRED`, `NOT_APPLICABLE` |
| `generated_data` (jsonb) | données calculées : remplacées à chaque recalcul |
| `manual_data` (jsonb) | données saisies : **jamais** écrasées par un recalcul |
| `manual_comment`, `prepared_by/at`, `reviewed_by/at` | commentaire, préparation, revue |
| `version` | `@Version` : verrouillage optimiste |

`generatedData` et `manualData` sont deux colonnes distinctes. Le schéma applicatif est strict : sections et champs connus de la définition versionnée, types validés à l'écriture, audit.

## 2. Schéma des sections et champs

`notes-r4.json` (version `2`) porte pour chaque note `sections[]` : `code`, `label`, `sourceType` (`GENERATED`, `MANUAL`, `MIXED`), `required`, `fields[]` (`code`, `label`, `type`, `required`, `options`, `sourceType`). Types : `TEXT`, `DECIMAL`, `INTEGER`, `DATE`, `BOOLEAN`, `ENUM`.

Les sections viennent uniquement de ce que la matrice documente (`autoCalculatedSections` → sections générées, `manualRequiredSections` → sections manuelles avec un champ `contenu` TEXT ; une section « conditionnelle » est facultative). Aucune colonne rognée n'est inventée : une note au schéma `PARTIAL` (3A, 3B, 3C, 27B, 28, 31, 33) reste `INCOMPLETE` (manque de nature `SCHEMA`) même remplie. La Note 35 porte une section `effectif` (INTEGER) qui pilote sa règle d'applicabilité.

Note 17 : table générée ajoutée (401, 402, 408 — rattachement dérivé du Passif DJ, Note 17) ; groupe et échéances restent manuels.

## 3. Statut (`NoteCompletionEvaluator`)

* non applicable décidée → `NOT_APPLICABLE` (non jointe, décision tracée) ;
* applicabilité non décidée (à déterminer ou candidate non validée) → `MANUAL_REQUIRED` ;
* applicable : calcul impossible (compte non placé, état source non READY, tableau non généré) ou schéma partiel → `INCOMPLETE` ; sinon champ obligatoire manquant → `MANUAL_REQUIRED` (note entièrement manuelle) ou `INCOMPLETE` (note à partie générée) ; sinon `READY`.

Note 21/22 : totaux générés, ventilations manuelles. Note 34 : dépend de CR, Bilan et TFT (jamais READY si un état source ne l'est pas) et ses indicateurs sans formule vérifiée restent manuels.

## 4. API (`/api/entreprises/{id}/etats-financiers-syscohada`)

| Verbe | Chemin | Effet |
|---|---|---|
| GET | `/notes?exerciceId` | espace de travail : 46 notes, statuts, comptes |
| GET | `/notes/{code}?exerciceId` | note : `generatedSections`, `manualSections`, `missingSections`, `generatedData`, manques, revue, version |
| PATCH | `/notes/{code}/manual-data` | modifie des champs (`sectionCode`, `fieldCode`, `value`, `comment`, `source`) sans toucher au reste ; `expectedVersion` |
| PATCH | `/notes/{code}/applicability` | `APPLICABLE`, `NOT_APPLICABLE` ou `TO_DETERMINE` (annule la décision) |
| POST | `/notes/validate-candidates` | valide **explicitement** en masse les non-applicabilités proposées |
| POST | `/notes/{code}/review` · `/reopen` | revue (note READY) · réouverture (annule revue et décision de non-applicabilité) |
| POST | `/notes/controles` | synchronise les Findings `REQUIRED_NOTE_INCOMPLETE` |
| GET | `/notes/catalogue` | catalogue réglementaire |

## 5. Règles

* **Candidate** : jamais `NOT_APPLICABLE` sans action explicite. Notes qualitatives (1, 2, 16B, 16B bis, 16C, 35, 29) jamais déduites non applicables. Une note portant des données comptables ne peut pas être déclarée non applicable ; un effectif > 250 interdit de déclarer la Note 35 non applicable.
* **Revue** : réservée à l'expert-comptable, sur note READY. Toute modification (donnée manuelle, applicabilité, données générées, statut non READY) annule la revue (`NOTE_REVUE_INVALIDEE`).
* **Rôles** : lecture ouverte à qui a accès à l'entreprise ; saisie, applicabilité, validation en masse, réouverture : COLLABORATEUR ou EXPERT_COMPTABLE (CLIENT : 403) ; revue : EXPERT_COMPTABLE.
* **Tenant** : entreprise ou exercice d'un autre tenant : 404.
* **Concurrence** : `expectedVersion` périmée → 409 ; `@Version` protège aussi les écritures concurrentes.
* **Audit** : `NOTE_APPLICABILITE_MODIFIEE`, `NOTE_DONNEES_MANUELLES_MODIFIEES` (avant/après), `NOTE_REVUE`, `NOTE_REVUE_INVALIDEE`, `NOTE_ROUVERTE`, `NOTE_CANDIDATES_VALIDEES`.
* **Findings** : `REQUIRED_NOTE_INCOMPLETE` porte `noteCode`, et pour chaque manque `section`, `missingInformation`, `kind`, `fieldCodes` ; auto-résolu quand la note redevient READY.
* **Recalcul** : à chaque lecture et finalisation, les notes sont recalculées depuis les états sources ; une note READY dont la source change repasse INCOMPLETE, les données manuelles sont conservées.

## 6. Snapshot

`DOSSIER_COMPLET` fige, pour chaque note jointe : code, applicabilité, statut, données générées et manuelles **copiées**, revue, version de définition ; les notes non applicables sont tracées à part (décision, décideur, date, raison), non jointes. Le snapshot reste immuable (UPDATE, DELETE interdits) quelles que soient les modifications ultérieures de la note courante.

## 7. Interface

L'onglet « Notes annexes » du Dossier financier (`docs/financial-review-workspace.md`) consomme ces endpoints : filtres, formulaires générés depuis `sections[]`/`fields[]`, `expectedVersion`, validation en masse précédée de la liste, revue, message « Revue devenue obsolète ». Les sections qui pilotent l'applicabilité (effectif de la Note 35) restent saisissables tant que la note n'est pas jointe.

## 8. Concurrence

L'actualisation des notes d'un exercice est sérialisée par un verrou consultatif PostgreSQL (`pg_advisory_xact_lock`) pris au début de la transaction : deux chargements simultanés (écran qui charge synthèse et notes en parallèle, deux utilisateurs) ne créent plus de lignes en double ni de conflit de version sur les mêmes lignes. Le verrouillage optimiste (`expectedVersion`, `@Version`) continue de protéger les modifications humaines concurrentes.
