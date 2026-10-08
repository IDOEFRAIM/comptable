# Cabinet Workspace (Phase H)

Poste de travail du cabinet : « quel dossier nécessite mon attention, pourquoi, qui doit agir, qu'est-ce qui bloque, quel dossier est prêt pour revue / à clôturer ». Le comptable travaille par exception. Rien n'est dupliqué : le Workspace **agrège** Findings, demandes de pièces, banque, notes, clôture et snapshot.

## 1. Audit initial (ce qui existait / manquait)

| Existant, réutilisé | Manquant, créé |
|---|---|
| `Finding` (assigné à, échéance, sévérité, catégorie) = source de vérité des anomalies ; `DemandePiece` (+ `finding_id`) ; `bank_reconciliation`, `ligne_releve` ; `financial_statement_note` (applicabilité, complétude, revue) ; `financial_statement_snapshot` ; `exercice_comptable` ; `AuditTrailService` ; rôles `EXPERT_COMPTABLE` / `COLLABORATEUR` / `CLIENT` ; `DossierRoles` | Affectation d'un dossier (collaborateur, expert) ; échéances du cabinet ; statut opérationnel, progression, priorité ; agrégation portefeuille paginée ; création d'un collaborateur par l'expert |
| Ancien « Cockpit » `/api/cabinet/cockpit` et `/inbox` (conservés, route `/cabinet/cockpit`) : un appel par client, non paginé | Remplacés comme page d'accueil par le Workspace (requêtes groupées, pagination serveur) |

Aucune entité `Dossier` : un dossier est **(entreprise, exercice)**. Deux tables seulement (migration `V65`) : `dossier_affectation` (unique par entreprise+exercice) et `dossier_echeance`.

## 2. Modèle du dossier

Dossier = entreprise + exercice, avec des données **calculées** (responsable, statut, progression, priorité, constats, pièces, banque, échéance, activité). Les constats, demandes, documents et la banque sont rattachés à l'entreprise : ils alimentent le **dossier courant** (exercice ouvert le plus ancien, sinon le plus récent). Les écritures, régularisations, notes et le snapshot sont propres à chaque exercice.

## 3. Statut opérationnel (`DossierOperationalStatusEvaluator`, fonction pure de `DossierSignals`)

Première règle qui s'applique :

1. `FINALISE` : snapshot `DOSSIER_COMPLET` final ;
2. `CLOTURE` : exercice clôturé, pas encore finalisé ;
3. `A_DEMARRER` : aucune activité (écriture, document, demande, constat, ligne bancaire, note) ;
4. `ATTENTION_REQUISE` : constat **bloquant ou important** que le cabinet peut traiter ;
5. `EN_ATTENTE_CLIENT` : le cabinet n'a plus rien à faire seul — une pièce a été **demandée au client** (demande `A_ENVOYER`, `EN_ATTENTE`, `ECHEC_ENVOI`). Un constat dont la pièce a déjà été demandée n'est plus une action du cabinet ;
6. `EN_COURS` : une étape de préparation n'est pas prête ;
7. préparation terminée : `PRET_POUR_REVUE` (aucune note applicable revue), `EN_REVUE` (revue commencée), `PRET_A_CLOTURER` (revue terminée, exercice ouvert). Sans note applicable à revoir, il n'y a pas de revue à attendre → `PRET_A_CLOTURER`.

Le frontend ne calcule jamais ce statut.

## 4. Progression par étapes (jamais un pourcentage)

`Pièces · Banque · Comptabilité · Contrôles · Pré-clôture · États financiers · Finalisation` : « 5 / 7 étapes prêtes », avec les étapes restantes nommées. Un dossier sans activité n'a aucune étape prête (l'absence de problème n'est pas de l'avancement).

| Étape | Prête quand |
|---|---|
| Pièces | aucune demande en attente du client, aucun document à traiter, aucune pièce reçue à rattacher |
| Banque | aucune ligne non rapprochée (hors doublons et virements internes confirmés), aucune proposition à valider, aucun constat de rapprochement actif |
| Comptabilité | écritures validées > 0 et aucun brouillon |
| Contrôles | aucun constat actif traitable par le cabinet |
| Pré-clôture | contrôles + comptabilité prêts et aucune régularisation non finalisée |
| États financiers | notes ouvertes, aucune à décider, aucune applicable incomplète |
| Finalisation | snapshot final |

## 5. Priorité (déterministe, sans IA)

`BLOCKER` constat bloquant (bloque la clôture) · `HIGH` constat important, demande en retard, échéance en retard ou à ≤ 7 jours, élément actif depuis ≥ 14 jours · `NORMAL` · `LOW` (dossier clôturé, finalisé ou à démarrer).

## 6. API (`/api/cabinet/**`, COLLABORATEUR et EXPERT_COMPTABLE ; CLIENT → 403)

| Route | Rôle |
|---|---|
| `GET /dashboard?mes=` | `summary` (5 indicateurs), `workQueue` (8 premiers), `reviewQueue` (expert), `deadlines` |
| `GET /dossiers?filtre&q&collaborateurId&expertId&exercice&statut&tri&ordre&page&taille` | portefeuille paginé (taille ≤ 100) |
| `GET /work-queue?mes&page&taille` | file « À traiter » paginée côté SQL |
| `GET /deadlines?periode=&mes=` | `EN_RETARD`, `AUJOURDHUI`, `CETTE_SEMAINE`, `A_VENIR` |
| `GET /membres` | collaborateurs / experts actifs (sélecteurs) |
| `GET /equipe`, `POST /membres`, `PATCH /membres/{id}` | équipe ; ajout, rôle, activation réservés à l'expert |
| `POST /controles/synchroniser` | backfill explicite des constats financiers (expert) |
| `PATCH /dossiers/{entreprise}/{exercice}/affectation` | remplace collaborateur + expert (expert-comptable) |
| `PATCH /dossiers/affectation` | affectation en masse (champs fournis seulement, tout ou rien, ≤ 500) |
| `POST /echeances`, `PATCH /echeances/{id}` | échéances (collaborateur et expert) |

Filtres : `MES_DOSSIERS`, `TOUS`, `A_TRAITER` (en cours + attention requise), `EN_ATTENTE_CLIENT`, `PRETS_REVUE` (prêt pour revue + en revue), `PRETS_CLOTURE`, `FINALISES` ; secondaires : collaborateur, expert, exercice, statut. Tris : priorité, entreprise, dernière activité, échéance. Recherche serveur : nom, identifiant (numéro de contribuable), responsable.

## 7. File « À traiter »

`Finding` actifs traitables par le cabinet + pièces reçues à rattacher : entreprise, problème, domaine, impact (« Bloque la clôture », « À corriger avant la clôture », « À vérifier », « Pièce reçue à rattacher »), ancienneté, responsable (constat assigné, sinon collaborateur du dossier), cible d'ouverture (`REVISION`, `DEMANDES`, `BANQUE`, `ETATS_FINANCIERS`). Tri : bloquants, importants, puis le reste ; le plus ancien d'abord.

**Les anomalies des états financiers sont proactives** : voir §7 bis. Aucune visite de l'écran « dossier financier » n'est nécessaire.

## 7 bis. Findings financiers : cycle de vie et synchronisation proactive (Phase H0.1)

### Audit de l'ancien flux
Les constats des états (`UNMAPPED_ACCOUNT`, `AMBIGUOUS_MAPPING`, `RESULT_MISMATCH`, `BALANCE_SHEET_IMBALANCE`, `CASH_FLOW_MISMATCH`, `UNRESOLVED_CAFG_COMPONENT`, `UNQUALIFIED_CASH_EVENT`, `REQUIRED_NOTE_INCOMPLETE`) ne devenaient des `Finding` que par `StatementControlPublisher.publier{CompteResultat,Bilan,Tft,Notes}`, appelés uniquement par des `POST …/controles` que **aucun écran ni opération n'appelait** : le dossier financier affichait des « exceptions » calculées, jamais des Findings. Le Cabinet Workspace (qui lit les Findings) ne voyait donc rien. Contournements écartés : ouvrir tous les dossiers, cron de visite, GET factice, polling.

### Stratégie : le constat naît de l'opération métier
`FinancialControlSynchronizationService` recalcule **seulement les états concernés** et publie / résout les Findings via `StatementControlPublisher` (déduplication et audit existants, source SYSTEM à la résolution). Elle s'exécute **après le commit** de l'opération, dans sa propre transaction (`REQUIRES_NEW`), et ne fait jamais échouer l'opération (erreur journalisée).

| Déclencheur (écouteur JPA sur l'entité : couvre tous les chemins d'écriture) | Domaines resynchronisés |
|---|---|
| `EcritureComptable` **validée** créée ou modifiée (saisie, moteur d'opérations, banque, facturation, clôture, qualification d'un flux de trésorerie) — un brouillon ne déclenche rien | compte de résultat, bilan, TFT, notes |
| `FinancialStatementNote` créée ou modifiée (données manuelles, applicabilité, revue) | notes |

`FinancialControlChangeTracker` **coalesce** les signaux d'une transaction : un import de 10 000 écritures dans une transaction produit **une** synchronisation. Les écritures de la synchronisation elle-même (lignes de notes recalculées) ne la redéclenchent pas (garde thread-local) : pas de boucle.

### Propriétés
* **Idempotente** : deux recalculs identiques ne créent aucun doublon (un constat actif par contrôle et par objet ; un constat par état concerné : compte de résultat et bilan portent chacun `UNMAPPED_ACCOUNT`).
* **Auto-résolution** : une anomalie qui disparaît passe en `RESOLVED` / `SYSTEM` sans ouvrir le dossier (testé : compte non mappé corrigé par écriture inverse, flux qualifié, note complétée).
* **Le dashboard et la file ne recalculent rien** : ils lisent des Findings déjà synchronisés.
* **Backfill** : `POST /api/cabinet/controles/synchroniser` (expert) resynchronise tous les exercices ouverts des entreprises actives du cabinet ; action explicite, pas de cron. À lancer une fois pour les dossiers antérieurs à cette synchronisation.

### Coût (Hibernate, requêtes préparées ; temps indicatifs)
| Opération | Requêtes | Temps |
|---|---|---|
| Écriture créée + validée (dossier à 1 écriture) | 107 | 381 ms |
| Idem, dossier à 17 écritures | 103 | 303 ms |
| Qualification d'un flux | 90 | 267 ms |
| Modification d'une note | 61 | 152 ms |
| Synchronisation seule (4 domaines) | 81 | 191 ms |

Le nombre de requêtes **ne dépend pas du volume d'écritures** du dossier ; la synchronisation ajoute ~80 requêtes à une validation d'écriture (le reste, ~25, est l'opération elle-même). Pas de seuil de durée dans les tests.

## 7 ter. Équipe du cabinet (gestion des membres)

Écran `/cabinet/equipe` : nom, e-mail, rôle, statut, dossiers affectés, dernière activité (dernière action tracée à l'audit). Lecture : collaborateur et expert ; **ajout, changement de rôle, désactivation / réactivation : expert-comptable uniquement**. Rôles supportés : `COLLABORATEUR`, `EXPERT_COMPTABLE` (aucun rôle inventé). Un membre ajouté reçoit un **mot de passe temporaire affiché une seule fois** (aucune infrastructure d'e-mail n'existe) ; il est disponible immédiatement dans les sélecteurs d'affectation et les filtres. Un membre désactivé (`statut = INACTIF`) ne peut plus se connecter, n'est plus proposé à l'affectation, reste visible dans l'équipe.

API : `GET /equipe`, `POST /membres`, `PATCH /membres/{id}` (`role`, `statut`). Validation backend : e-mail valide, e-mail unique (insensible à la casse, 409), rôle inconnu / `CLIENT` / `SUPER_ADMIN` refusés (422), nom et prénom obligatoires, mot de passe ≥ 8 caractères s'il est fourni. Garde-fous : on ne modifie pas son propre rôle, on ne se désactive pas, le cabinet garde toujours un expert actif. Un membre d'un autre cabinet est introuvable (404). Audit : `MEMBRE_CABINET_AJOUTE`, `ROLE_MEMBRE_MODIFIE` (avant / après), `MEMBRE_CABINET_DESACTIVE`, `MEMBRE_CABINET_REACTIVE`.

## 8. Affectation, audit, permissions

* Affectation et création de membre : **expert-comptable uniquement**. Échéances : collaborateur et expert.
* Audit (`AuditTrailService`, action humaine) : `DOSSIER_AFFECTE`, `RESPONSABLE_DOSSIER_MODIFIE` (avant / après), `ECHEANCE_DOSSIER_CREEE`, `ECHEANCE_DOSSIER_MODIFIEE`, `MEMBRE_CABINET_AJOUTE`, `ROLE_MEMBRE_MODIFIE`, `MEMBRE_CABINET_DESACTIVE`, `MEMBRE_CABINET_REACTIVE`. Une affectation inchangée n'écrit rien.
* Multi-tenant : toutes les requêtes sont bornées par le tenant ; entreprise, exercice ou échéance d'un autre cabinet → **404** ; un responsable d'un autre cabinet est refusé.

## 9. Performance

Mesure (`CabinetWorkspacePerformanceTest`, 10 puis 100 entreprises avec écriture, constat de pièce manquante et, pour la moitié, une demande envoyée) : le nombre de requêtes est **identique à 10 et à 100 dossiers** (pas de N+1) ; ~12 requêtes SQL groupées pour le portefeuille. Les chiffres JDBC ci-dessous incluent les appels internes de `JdbcTemplate` (un statement compte plusieurs invocations) ; le critère est l'égalité 10 / 100.

| Écran | Requêtes JDBC (invocations) 10 → 100 | Requêtes Hibernate 10 → 100 | Temps indicatif 10 → 100 |
|---|---|---|---|
| Dashboard | 34 → 34 | 5 → 5 | 115 → 170 ms |
| Portefeuille (page de 25) | 29 → 29 | 1 → 1 | 69 → 65 ms |
| File À traiter (page de 25) | 5 → 5 | 0 → 0 | 18 → 115 ms |

Le statut est évalué en Java sur le portefeuille du cabinet, puis filtré, trié et paginé **côté serveur** : Angular ne reçoit qu'une page. Pour 500 dossiers, cela reste quelques millisecondes d'évaluation en mémoire ; au-delà de plusieurs milliers de dossiers par cabinet, il faudrait matérialiser le statut (pas de Redis introduit : PostgreSQL suffit à cette échelle).

## 10. Interface

Design system de la Phase F (`--ui-*`, `app-icon`, `app-status`, `app-empty-state`, `app-section-header`) : accueil du cabinet (`/cabinet`, atterrissage des membres du cabinet après connexion), portefeuille (`/cabinet/dossiers`, filtres en onglets, recherche, sélection et affectation en masse pour l'expert), échéances (`/cabinet/echeances`). Captures : `docs/screenshots/cabinet-workspace/`.

## 11. Tests

* Backend : `DossierOperationalStatusEvaluatorTest` (10), `CabinetWorkspaceIntegrationTest` (11 : statuts réels, file, dashboard, revue, filtres, recherche, pagination, affectation + audit, échéances, droits, tenant), `CabinetWorkspaceHttpTest` (contrat JSON, rôles, 404), `CabinetWorkspacePerformanceTest` (10 vs 100 dossiers).
* Angular (Karma) : 26 tests ajoutés (45 au total) : dashboard, portefeuille, filtres, file, statuts, rôles, états vides, échéances, utilitaires.
* Backend H0.1 : `CabinetEquipeIntegrationTest` (7), `ProactiveFinancialFindingsTest` (6 : compte non mappé, TFT, notes, idempotence, backfill, brouillon, coût) ; Angular `equipe.component.spec.ts` (9) ; Playwright équipe + anomalie proactive.
* Playwright : `cabinet-workspace.spec.ts` — collaborateur (tableau de bord → anomalie → source → correction → compteur à jour), attente client (statut, résolution, recalcul), expert (file À revoir → revue des notes → clôture → finalisation → le dossier quitte la file).

## 12. Limites connues

* Pas de réinitialisation de mot de passe ni d'envoi d'e-mail d'invitation : le mot de passe temporaire est communiqué à la main.
* La synchronisation proactive s'applique aux états (compte de résultat, bilan, TFT, notes) ; les constats bancaires et de pièces viennent de leurs propres contrôles existants. Une modification faite par SQL direct (hors application) ne déclenche rien : utiliser le backfill.
* Chaque écriture validée coûte ~80 requêtes de plus (recalcul des états de son exercice) ; la coalescence par transaction limite ce coût aux imports. Au-delà de quelques milliers d'écritures validées une à une par minute, il faudrait différer ou regrouper la synchronisation.
* « Revue terminée » = toutes les notes applicables prêtes sont revues ; il n'existe pas de revue au niveau du dossier.
* « Dernière activité » d'un dossier = la plus récente entre écriture, constat, demande de pièce et document ; celle d'un membre = sa dernière action tracée à l'audit.
* Un dossier d'un ancien exercice non finalisé n'est pas alimenté par les constats (rattachés au dossier courant).
* Filtres, tri et pagination s'appliquent en mémoire sur le portefeuille évalué (voir Performance).

## Indicateur TVA (G3A)

Une seule colonne compacte « TVA » dans le portefeuille, calculée par le backend (`VatOperationalStatusEvaluator.codePortefeuille`) : **À préparer**, **N à traiter**, **À revoir**, **Prête**, **Cadre réglementaire incomplet**, **Finalisée**, **Finalisée — À payer**, **Finalisée — Partiellement payée**, **Finalisée — Payée**, **Finalisée — Crédit à reporter**. « Prête » n'est jamais affichée quand le cadre réglementaire n'est pas vérifié (elle devient « Cadre réglementaire incomplet »).

File « À traiter » : opérations TVA à revoir, anomalies fiscales (domaine Fiscalité), constat « cadre réglementaire non vérifié », « Déclaration TVA prête pour revue » (à revoir par l'expert) et « TVA à payer : reste X » (paiement à suivre) ; chaque ligne ouvre l'espace TVA. Les échéances de déclaration n'apparaissent que si un cadre VERIFIED fournit une règle d'échéance.

Le backend garde séparés le statut de déclaration (FINAL) et le statut de règlement ; le libellé composite n'est qu'un affichage. Les échéances du cabinet sont classées (en retard, aujourd'hui, cette semaine, à venir) selon l'horloge injectée (`TimeConfig`), ce qui les rend testables indépendamment du jour réel (`CabinetClockTest`).
