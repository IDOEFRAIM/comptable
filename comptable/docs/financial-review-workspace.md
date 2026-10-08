# Espace de révision du dossier financier (F9/F10)

Un expert-comptable peut, depuis l'écran **Dossier financier** (route `/entreprises/:id/etats-financiers`), voir ce qui bloque, corriger uniquement les exceptions, compléter les Notes, réviser, finaliser et ouvrir le dossier FINAL immuable, puis l'exporter — sans Swagger, SQL ni Postman.

## Règle absolue

Le frontend reflète exactement la vérité backend. Les statuts `INCOMPLETE`, `PARTIAL`, `PARTIAL_READY`, `READY` ne sont jamais masqués ni adoucis : une ligne incomplète affiche « Incomplet » (jamais `0`), un statut est toujours rendu avec **icône + texte** (pas la couleur seule), et `finalizable` est décidé par le backend. Aucun `forceFinalize`, `ignoreWarnings` ou `skipNotes` n'existe.

## Contrats API

Base : `/api/entreprises/{id}/etats-financiers-syscohada`. Tout est restreint à COLLABORATEUR et EXPERT_COMPTABLE au niveau HTTP (comme l'ancien reporting) ; les droits fins sont dans les services.

| Route | Usage | Droit |
|---|---|---|
| `GET /dossier-financier?exerciceId` | readiness agrégée (`FinancialDossierService`) : statuts CR/Bilan/TFT/Notes, contrôle BZ/DZ, progression des notes, exceptions, checklist, `finalizable`, `finalised`, snapshot, permissions | lecture |
| `GET /compte-resultat`, `/bilan`, `/tft` | états vivants | lecture |
| `GET /{type}/lignes/{code}` | « Pourquoi ? » (comptes, soldes, écritures, lineage ; pour le TFT : écritures et événements) | lecture |
| `GET /notes`, `GET/PATCH …/notes/{code}/…` | Note Workspace (voir `syscohada-notes-workspace.md`) | lecture / préparation / revue |
| `GET /cash-flow-events` | enum réel `CashFlowEvent` avec libellés français | lecture |
| `PUT /ecritures/{id}/cash-flow-event` | qualification d'un flux de trésorerie ; **le backend valide** comptes, sens, présence de trésorerie, montant (422 sinon) ; refusée (409) si le dossier est finalisé | préparation |
| `POST /DOSSIER_COMPLET/finaliser` | finalisation (snapshot immuable) | **EXPERT_COMPTABLE** (403 sinon) |
| `GET /snapshots/{id}` | lecture du snapshot | lecture |
| `GET /dossier-financier/export` | export imprimable **BROUILLON** (filigrane) | lecture |
| `GET /snapshots/{id}/export` | export imprimable **FINAL**, rendu uniquement depuis le snapshot | lecture |

Routes remplacées : l'ancien reporting `/api/entreprises/{id}/etats-financiers/{bilan,compte-resultat}` n'est plus appelé par l'application (voir « Nettoyage »).

## Onglets

Vue d'ensemble · Compte de résultat · Bilan · TFT · Notes annexes · Contrôles · Pourquoi ? · Version finale.

* **Vue d'ensemble** : statuts des états, `n / 46 notes décidées`, contrôles bloquants, warnings, finalisable, actions (Réviser, Voir les anomalies, Voir les Notes, Prévisualiser, Finaliser), puis les 3 premières interventions (travail par exception).
* **Compte de résultat** : N, N-1, variation (en centimes entiers, jamais en flottant), regroupements exploitation / financier / HAO / résultat net, soldes XA…XI distingués.
* **Bilan** : Actif (brut, amortissements, net N, net N-1) et Passif (N, N-1), contrôle BZ/DZ avec écart (`✅ Équilibré` ou `❌ Écart de …`), section « Comptes empêchant la finalisation ».
* **TFT** : sections opérationnel / investissements / capitaux propres / capitaux étrangers / variation, avec la cause précise de chaque ligne incomplète.
* **Notes annexes** : 46 notes, filtres (À décider, À compléter, Prêtes, Revues, Non applicables, Bloquées), parties calculées en lecture seule, formulaires générés depuis `sections[]`/`fields[]` (TEXT, DECIMAL, INTEGER, DATE, BOOLEAN, ENUM), `expectedVersion` envoyé à chaque sauvegarde (conflit → message de rechargement), validation en masse précédée de la liste des notes concernées, revue obsolète signalée.
* **Contrôles** : anomalies du dossier par catégorie ; qualification des flux de trésorerie sur place.
* **Pourquoi ?** : rubrique → comptes → soldes → écritures → lineage.
* **Version finale** : checklist de pré-finalisation, clôture de l'exercice, confirmation d'immuabilité, informations du snapshot (date, utilisateur, version, empreinte), export, et visionneuse du snapshot (jamais les états vivants).

## Rôles

| Rôle | Droits |
|---|---|
| CLIENT | aucun accès HTTP à ces routes ; au niveau service, lecture seule (aucune modification, qualification ni finalisation) |
| COLLABORATEUR | préparation : notes, applicabilité, qualification des flux |
| EXPERT_COMPTABLE | préparation, revue des notes, finalisation |

L'écran masque les actions selon `permissions` fournies par le backend ; le backend refuse (403) dans tous les cas.

## Exports

Modèle imprimable **HTML** (aucun moteur PDF n'existe dans le projet) : identité, exercice, Compte de résultat, Bilan, TFT, Notes applicables, informations de version. Les notes non applicables n'y figurent pas (traçabilité dans le snapshot). Export non final : bandeau et filigrane `BROUILLON`. Export final : issu du snapshot, bandeau « DOSSIER FINANCIER FINAL », empreinte du snapshot.

## Audit

`ECRITURE_FLUX_TRESORERIE_QUALIFIE`, `NOTE_*` (applicabilité, données manuelles, revue, réouverture, validation des candidates), `ETAT_FINANCIER_FINALISE`.

## Tests

### Backend (Maven)
`FinancialDossierTest` (synthèse finalisable vrai/faux, exceptions, qualification validée par le serveur, exports, rôles, tenant), `FinancialDossierHttpTest` (contrat HTTP réel : snapshot en JSON natif, CLIENT 403, COLLABORATEUR 403 à la finalisation, double finalisation 409, 404 autre tenant sur summary / notes / review / reopen / qualification / finalisation / exports), `HardeningFinalTest` (hash du snapshot couvrant tout le contenu, piste d'audit acteur/tenant/exercice/horodatage/avant-après, ZA-FB-FC-FD-FE sur l'exercice précédent réel de 18 mois, Finding de flux non qualifié résolu, chargements simultanés), `ClotureMoteurCanoniqueTest`, `FinancialDossierPerformanceTest`.

### Frontend — tests unitaires
Runner : **Karma + Jasmine** (outillage standard d'Angular CLI, Chrome headless du poste). Commande : `npm test` (variable `CHROME_BIN` si Chrome n'est pas détecté). 19 tests : `OngletNotesComponent` (note MIXED incomplète → saisie avec `expectedVersion`, conflit 409 et message de rechargement, « Revue devenue obsolète », revue réservée à l'expert, CLIENT sans aucune action, validation en masse précédée de la liste, Note 35), `OngletFinalisationComponent` (`finalizable` du backend jamais recalculé, confirmation d'immuabilité, collaborateur / client sans bouton, dossier finalisé), `DossierFinancierComponent` (statuts réels non adoucis, travail par exception, un chargement agrégé), `SnapshotViewerComponent` (lit uniquement le snapshot : aucune requête vers les états vivants), `montant` (variation en centimes).

### Frontend — E2E navigateur automatisé
Runner : **Playwright** avec le Chrome du poste (aucun navigateur téléchargé). Commande : `npm run e2e` (démarre le backend et le frontend s'ils ne tournent pas ; Docker requis pour PostgreSQL). Un test, un seul parcours, avec les éléments réels de l'interface (champs, boutons, onglets, aucune injection JavaScript) : connexion → dossier → qualification d'un flux → Note 17 (saisie, revue, modification, revue obsolète, nouvelle revue) → Note 22 → Note 23 automatique → **Note 2 applicable, saisie, jointe** → Note 35 (251 applicable, 250 non applicable) → validation en masse → décisions restantes (46 / 46) → aperçu BROUILLON → clôture → finalisation → snapshot (Notes 2, 17, 22, 23 jointes) → export FINAL → seconde finalisation refusée (409). Les données (un cabinet jetable, une entreprise, trois écritures) sont créées par l'API.

Bug réel révélé par ce parcours : le chargement parallèle de la synthèse et des notes créait deux fois les 46 lignes de notes (violation d'unicité, 500) ; corrigé par un verrou consultatif PostgreSQL par (entreprise, exercice) dans l'actualisation des notes (test `chargementsSimultanesDuMemeDossier…`).

## Performance de la synthèse

Dossier réaliste de test : 10 000 écritures de vente sur 150 comptes de produits + 5 000 encaissements bancaires (30 000 lignes) ; petit dossier : 20 écritures sur 2 comptes. Critère stable : nombre de requêtes (`Statistics.getPrepareStatementCount`) ; les temps sont indicatifs.

| Mesure | Avant | Après |
|---|---|---|
| Requêtes, synthèse (petit et gros dossier) | 35 (identique : pas de N+1) | **14** (identique) |
| Temps, synthèse, gros dossier | 6,6 s | **2,3 s** |
| Temps, synthèse, petit dossier | 1,5 s | 0,9 s |
| TFT seul, gros dossier | 1,7 s | 1,9 s |

Profil : la synthèse appelait le Compte de résultat 4 fois, rechargeait les soldes de l'exercice (grand livre et activité) à chaque état, et résolvait les flux de trésorerie deux fois (TFT puis écritures non qualifiées). Optimisations : `StatementComputationCache`, **mémoïsation limitée à la transaction** (pas un cache applicatif : rien ne survit à la transaction, aucune invalidation) des soldes, empreintes, Compte de résultat, Bilan, TFT et événements ; le résolveur d'événements ne charge plus que les écritures touchant un compte de trésorerie (identifié par les règles du Bilan) au lieu de toutes les lignes de la période. Le TFT seul n'est pas plus rapide : son coût restant est l'hydratation des écritures de trésorerie (5 000 écritures dans ce dossier).

Rechargements ciblés côté écran : une action sur une note recharge synthèse et notes (pas le Compte de résultat, le Bilan ni le TFT) ; une qualification de flux recharge synthèse et TFT. Côté serveur, la synthèse recalcule néanmoins les états à chaque appel (aucun cache entre requêtes, par choix : pas de cache aveugle).

## Nettoyage de l'ancien reporting (terminé)

| Élément | Statut |
|---|---|
| Écran Angular `etats-financiers`, `section-financiere`, types TS `BilanDto` / `CompteResultatDto`, `ComptabiliteService.bilan` / `compteResultat` | supprimés |
| `EtatsFinanciersController` (`/etats-financiers/{bilan,compte-resultat}`) | supprimé |
| `EtatsFinanciersService`, DTO Java `BilanDto` / `CompteResultatDto` / `SectionFinanciereDto` / `PosteFinancierDto`, `EtatsFinanciersServiceTest` | **supprimés** : la clôture calcule désormais le résultat avec le moteur de balance canonique (`AccountingBalanceEngine`, vue `PERIOD_ACTIVITY`, comptes de gestion des classes 6, 7 et **8**), la même identité que le Compte de résultat (XI) et le Bilan (CJ) |
| Autre usage de l'ancien moteur | aucun (recherche exhaustive dans `src/`) |

Conséquence documentée : la clôture solde aussi les comptes de classe 8 (HAO, impôts), qu'elle ignorait ; le résultat posté en 120/129 est celui du Compte de résultat.

## Limites connues (hors règles réglementaires)

* Exports : HTML imprimable seulement (aucun moteur PDF dans le projet ; aucune dépendance PDF ajoutée).
* La colonne N-1 du TFT n'est pas calculée (la matrice ne l'établit pas) ; ZA, FB, FC, FD, FE utilisent l'exercice précédent réel.
* Les contenus manuels des notes restent un champ texte `contenu` par section.
* Le rôle CLIENT n'a aucun accès HTTP à ces routes (comme l'ancien reporting) ; aucun écran client n'existe.
* Gates réglementaires inchangés : F5 PARTIAL_READY, F7 PARTIAL_READY, F8 PARTIAL_READY.

## Verdict de clôture de la Phase F

**PHASE F — DONE** (livraison produit).

Critères : workflow complet utilisable sans outil technique (E2E Playwright vert) ; backend stable (913 tests, 0 échec) ; frontend stable (build, 19 tests Karma, E2E) ; snapshot FINAL immuable avec hash sur tout le contenu ; exports BROUILLON / FINAL (HTML imprimable) ; sécurité et multi-tenant ; legacy retiré ; performance mesurée.

**Restrictions réglementaires connues (pas des bugs)** : F5 PARTIAL_READY (AK/AL, suffixe « p », comptes réservés, lignes brut seul) ; F7 PARTIAL_READY (5 termes de la CAFG, qualification humaine des flux) ; F8 PARTIAL_READY (7 notes au schéma PARTIAL, notes sans tableau généré). Un dossier qui les touche reste INCOMPLETE et n'est pas finalisable : c'est le comportement attendu. La couverture réglementaire absolue n'est pas atteinte ; la couverture produit des cas supportés l'est.

## Redesign professionnel (UI uniquement)

Aucune règle comptable, API, DTO, permission ni logique de finalisation n'a été modifiée. Plan d'écran : `financial-review-redesign-plan.md`. Captures avant/après : `screenshots/financial-review/{avant,apres}/{1440x900,1920x1080}`.

* **Design system** : tokens `--ui-*` et primitives `.ui-*` (`frontend/src/styles/ui.scss` : typographie, boutons primary/secondary/tertiary/link/danger, onglets, tableaux, champs, notice, surface) ; composants partagés `src/app/shared/ui` : `app-icon` (jeu SVG unique, plus aucun emoji), `app-status` (statut unique, remplace `statut-badge`), `app-empty-state`, `app-section-header`.
* **Shell** : en-tête « Dossier financier / Société · Exercice », actions Recalculer (tertiaire) / Prévisualiser (secondaire) / Finaliser (primaire), un seul statut global, onglets sobres avec compteurs discrets.
* **Vue d'ensemble** : ligne synthétique + section « À traiter » (une barre rouge fine pour les bloquants seulement). **États** : tableaux à filets, montants alignés (`tabular-nums`), totaux appuyés, menu « ⋯ » (détail / écritures / origine). **Notes** : maître/détail. **Contrôles** : inbox Bloquants / Avertissements / Résolus. **Version finale** : checklist compacte, snapshot en document financier.
* Attributs `data-*` conservés : specs Karma (19) et E2E Playwright inchangés sauf microcopy.
* Limites : l'onglet « Résolus » ne liste que ce qui est traité pendant la session (le backend ne fournit pas d'historique) ; styles globaux historiques (`.carte`, `.badge`) conservés pour les autres écrans.
