# Phase F — correction des définitions et implémentation contrôlée F5 / F7 / F8 (lots G0 à G9)

Définitions versionnées : `etats-financiers/SYSCOHADA_REVISE_AUDCIF_2017/2/` (la version `1` reste intacte, immuable). Règle appliquée : une donnée n'entre dans une définition exécutable que si
`verificationStatus = VERIFIED` et provenance `DIRECT_PRIMARY` ou `DERIVED_FROM_PRIMARY`. Le reste produit `UNMAPPED_ACCOUNT` / `INCOMPLETE`, jamais un rangement par défaut.

## G0 — Corrections (v2)
* **BH** : brut `409`, dépréciation **`490`** ; `499` retiré de BH (499 est mappé au Passif, DN).
* **BJ** : dépréciations `492–497`. **BS** : dépréciations `592–594`.

## G1/G2 — Actif
* Codé et VERIFIED : AG, AJ, AP, AR, AS, BA, BB, BH, BI, BJ, BQ, BR, BS, BU ; totaux AD, AI, AQ, AZ, BG, BK, BT, BZ (formules `DERIVED_FROM_PRIMARY`).
* Brut seul, `PARTIAL` (un solde non nul rend le Bilan INCOMPLETE) : AE, AF, AH, AM, AN. Bloqués : AK, AL, et dépréciations « p » (2918p, 2919p, 2938p, 2939p, 2949p).

## G3/G4 — Passif
* 22 postes mappés `DERIVED_FROM_PRIMARY` : CA, CB, CD, CE, CF, CG, CH, CL, CM, DA, DB, DC, DH, DI, DJ, DK, DM, DN, DQ, DR, DV et CJ (identité de résultat).
* Non mappés (restent `UNMAPPED_ACCOUNT`) : 103, 104, 404, 186–188, 4998, 599, soldes créditeurs 54/55/57/581/582.
* CJ = −(solde net classe 13 + classes 6, 7, 8) ; contrôle fort CJ = XI (`RESULT_MISMATCH`) ; BZ = DZ à zéro près (`BALANCE_SHEET_IMBALANCE`).
* Le Bilan est présenté **avant** l'écriture technique de clôture (`CLOSING`) : le résultat reste en CJ et CH n'inclut pas le résultat de l'exercice, ce qui rend un exercice clôturé finalisable
  (correction de la limite constatée au G9 : le snapshot exige un exercice clôturé).
* READY seulement si : aucun compte non mappé/ambigu, aucune ligne PARTIAL avec solde, CJ = XI, BZ = DZ ; sinon INCOMPLETE (pas de FINAL partiel).

## G5 — TFT (`CashFlowStatementService`, `CashFlowExclusionClassifier`)
* ZA = BT − DT à l'ouverture, ZH = BT − DT à la clôture, ZG = ZB + ZC + ZF, `ZA + ZG = ZH` sinon `CASH_FLOW_MISMATCH` (uniquement si le TFT est par ailleurs complet).
* FA (CAFG) = EBE (XD) + 654 − 754 + 787 + 848 − 87 − 89. `INCOMPLETE` si un compte des cinq termes PARTIAL (67, 77, 83, 84, 88) a un mouvement, ou si un compte de gestion n'est classé nulle part.
* FB, FD, FE : variation **par compte** classé `OPERATING` par le classifieur (Notes 7/17/18) ; investissement (404, 414, 481, 482, 485), financement (461, 467, 4726, 465, 4494), non monétaire (49x),
  conversion (478, 479) exclus ; compte non classé qui varie → ligne INCOMPLETE + `UNMAPPED_ACCOUNT` (jamais inclus par défaut).
* FC : **remplacé** par la pré-finalisation (voir plus bas) : variation des stocks BRUTS ; la première version sur stocks nets est abandonnée.
* FF–FJ et FK–FQ : première version « 0 ou INCOMPLETE selon les mouvements de comptes », **remplacée** par le calcul par écritures et événements de trésorerie (voir plus bas).


* Aucune conversion monétaire d'une variation non monétaire. Colonne N-1 du TFT non calculée.

## G6 — Notes (`NotesService`)
* Catalogue de 46 notes versionné ; `automationType` = proposition produit. Tableaux générés pour les notes 5, 10, 23, 24, 29, 30 (comptes du plan, N et N-1).
* Notes 21, 22, 34 (AUTOMATIC proposées) restent au catalogue : sources non auditées / dépend d'états complets. Notes à colonnes tronquées (3A, 3B, 3C, 27B, 28, 31, 33) non codées.
* Applicabilité : `APPLICABLE` (décision, règle imprimée ou données présentes), `NOT_APPLICABLE` (non jointe), `UNDETERMINED` (règle imprimée sans entrée : Note 35 sans effectif), `TO_DETERMINE`.
* Notes MIXED : sections automatiques / manuelles et complétion (« X % calculé, N information(s) à compléter ») ; note jointe incomplète → `REQUIRED_NOTE_INCOMPLETE`.

## G7/G8 — Findings et snapshot
* Codes ajoutés : `BALANCE_SHEET_IMBALANCE`, `REQUIRED_NOTE_INCOMPLETE` ; auto-résolution (source SYSTEM) au recalcul via `StatementControlPublisher` (Bilan, TFT, Notes).
* `DOSSIER_COMPLET` : un snapshot FINAL immuable contient frameworkVersion, definitionHashes, exerciseId, generatedAt, CR, Bilan (Actif/Passif), TFT, Notes, controls ; refusé si un élément n'est pas READY ou si l'exercice n'est pas clôturé.

## API
`GET /bilan`, `POST /bilan/controles`, `POST /tft/controles`, `POST /notes/generation` (corps optionnel `{effectif, applicabilites}`), `POST /DOSSIER_COMPLET/finaliser`.

---

# Pré-finalisation (2026-10-05)

## Audit initial de l'itération G
Constats avant correction : (1) FC sur stocks **nets** : une dotation de dépréciation (39) devenait un flux ; (2) FF–FQ « tout ou rien » par numéro de compte, sans preuve de trésorerie ; (3) tests de préfixe de classe dispersés (`13`, `6`, `7`, `8`) dans le Bilan et le TFT ; (4) Notes : un seul booléen `attached`, finding sans structure ; (5) Notes 21, 22, 34 non générées ; (6) aucun test de succès de `DOSSIER_COMPLET`, ni scénario E2E ; (7) `startsWith` métier de la CAFG en clair. Vérifié sans défaut : pas de fallback silencieux (comptes non mappés → `UNMAPPED_ACCOUNT`), N-1 = exercice précédent réel (soldes d'ouverture du grand livre), signes du Bilan et du CR.

## Corrections
* **FC** : variation des comptes de stock **bruts** (dépréciations ignorées). Test : stock inchangé, 39 + 100 → FC = 0, aucun `CASH_FLOW_MISMATCH`.
* **Événements de trésorerie** : le lineage existant (`sourceType/sourceId/sourceEvent`) ne couvre ni capital, ni emprunt, ni dividende, ni subvention, ni cession (écritures manuelles ; la sortie d'immobilisation ne génère aucune écriture). Pas de second bus : une colonne `ecriture_comptable.cash_flow_event` (V63, enum `CashFlowEvent`) déclarée à la saisie. `CashFlowEventResolver` : une écriture sans ligne de trésorerie n'a aucun flux ; une écriture avec trésorerie est qualifiée (montant = lignes de contrepartie de la famille, sens et montant contrôlés contre la trésorerie), ou acquisition payée comptant (preuve dans l'écriture), ou non qualifiée → lignes INCOMPLETE + finding `UNQUALIFIED_CASH_EVENT`. Le lineage est joint à chaque composant (drill-down).
* **FF–FJ** : acquisition payée = flux ; achat à crédit = 0 jusqu'au paiement ; paiement d'une dette d'immobilisation = qualifié ; cession : FI = encaissement réel (jamais le prix comptable), cession non encaissée et mise au rebut = 0.
* **CAFG** : un terme `PARTIAL` sans mouvement vaut 0 ; avec mouvement, `UNRESOLVED_CAFG_COMPONENT` (finding listant les comptes déclencheurs).
* **ZA / ZH / N-1** : soldes d'ouverture et de clôture de l'exercice réel (jamais « date moins un an ») ; la matrice n'établit pas de colonne N-1 du TFT : non inventée.
* **Drill-down TFT** : ligne → comptes (FA-FE) ou écritures/événements/lineage (FF-FQ).
* **Notes** : statuts `NOT_APPLICABLE / READY / INCOMPLETE / MANUAL_REQUIRED` ; applicabilité `NOT_APPLICABLE_CANDIDATE` prouvée par les soldes et mouvements (Notes 4, 6, 9 et notes à tableau sans donnée), validation globale unique (`validerCandidates`), jamais déduite pour l'information qualitative (engagements, litiges, retraites, intérêts non comptabilisés) ; finding `REQUIRED_NOTE_INCOMPLETE` avec `noteCode`, `section`, `missingInformation`.
* **Notes 21, 22, 34** : définitions v2 (21 et 22 : totaux par famille issus des lignes VERIFIED du CR, ventilation par zone manuelle — le plan de comptes de référence n'a aucun sous-compte régional ; 34 : dépendance explicite sur CR, Bilan et TFT, jamais READY si une source ne l'est pas, indicateurs sans formule vérifiée manuels). Aucun snapshot FINAL n'ayant été produit sur la version 2 avant ces ajouts, les notes de la v2 ont été complétées ; toute correction ultérieure après un snapshot FINAL exige une version 3.
* **Constantes** : tests de classe de compte centralisés dans `StatementAccounts`.

## Snapshot
`DOSSIER_COMPLET` : succès testé (CR, Bilan, TFT READY, exercice clôturé, décisions humaines sur les 46 notes) ; contenu : version du cadre, 5 empreintes de définitions, exercice, date, états FINAL, notes, controls ; UPDATE, DELETE et seconde finalisation refusés ; autre tenant : 404. Refus testés : notes non décidées, TFT INCOMPLETE.

## Gates après cette mission
F5 PARTIAL_READY, F7 PARTIAL_READY, F8 PARTIAL. Blocages réglementaires restants : AK/AL, « p », brut seul AE/AF/AH/AM/AN, 6 réserves du Passif, 5 termes de la CAFG, formules d'indicateurs de la Note 34, ventilations des Notes 21/22. Blocage non réglementaire : contenus manuels des Notes non stockés.

---

# F8.1 — Espace de travail des Notes (2026-10-05)

Voir `docs/syscohada-notes-workspace.md`. Changements de contrat : `GET /notes` renvoie désormais l'espace de travail d'un exercice (le catalogue est sous `/notes/catalogue`) ; `POST /notes/generation` est remplacé par `POST /notes/controles` ; la finalisation (`/{type}/finaliser`) lit les décisions et données manuelles persistées (plus de corps `Decisions`). Statuts de note : un manque de saisie rend `INCOMPLETE` une note à partie générée (21, 22, 17, 29…) et `MANUAL_REQUIRED` une note entièrement manuelle. Gate F8 : PARTIAL_READY.

---

# F9/F10 — Espace de révision (2026-10-05)

Voir `docs/financial-review-workspace.md`. Changements de contrat : `POST /{type}/finaliser` est réservé à l'EXPERT_COMPTABLE ; les routes `/etats-financiers-syscohada/**` sont restreintes à COLLABORATEUR et EXPERT_COMPTABLE ; les snapshots sont exposés en JSON natif (`SnapshotView`) ; nouvelle valeur d'enum `CashFlowEvent.NO_TFT_FLOW` ; l'ancien contrôleur `/etats-financiers/{bilan,compte-resultat}` est supprimé (le service `EtatsFinanciersService` reste utilisé par la clôture).

---

# Hardening final (2026-10-05)

La clôture (`ClotureExerciceService`) calcule le résultat par le moteur canonique (classes 6, 7, 8) ; `EtatsFinanciersService` et ses DTO sont supprimés. `StatementComputationCache` mémoïse, par transaction, soldes, empreintes et états. `CashFlowEventResolver` ne charge que les écritures touchant la trésorerie. `NoteWorkspaceService.actualiser` prend un verrou consultatif par (entreprise, exercice).
