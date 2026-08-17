# Dataset synthétique — Mission P1.5 « Hardening + Unified Accounting Workspace » (section 75)

Même discipline que [`P0_DATASET_SYNTHETIQUE.md`](P0_DATASET_SYNTHETIQUE.md) et
[`P1_DATASET_SYNTHETIQUE.md`](P1_DATASET_SYNTHETIQUE.md) : ce document **référence** la
couverture de test réelle plutôt que de dupliquer des fixtures dans une base de démonstration
séparée qui divergerait du code avec le temps. Chaque ligne pointe vers un test qui s'exécute
réellement contre une base Postgres réelle (`mvn test`), jamais un mock de la couche
persistance.

## Écart assumé avec la lettre de la mission

La mission décrit 5 profils d'entreprise (A-E : propre / banque problématique / pièces
manquantes / fiscalité problématique / trésorerie-clients problématiques). Ce chantier ne
construit pas 5 nouvelles entreprises synthétiques dédiées : il **réutilise** les profils déjà
prouvés en P0/P1 (`P0CoreIntelligentE2ETest`, `P1CabinetOperatingSystemE2ETest` : un client
sain + un client à risque avec Finding TAX_REVIEW/MISSING_EVIDENCE réels) et **ajoute** une
nouvelle entreprise dédiée à la vérification de sécurité HTTP
(`P1_5SecuriteHttpE2ETest` : 2 tenants, 3 entreprises réelles, un utilisateur EXPERT_COMPTABLE
et un utilisateur CLIENT réels). Reconstruire 5 profils complets aurait dupliqué des mécanismes
déjà prouvés (Missing Evidence, Control Engine, Matching) sans rien démontrer de nouveau côté
P1.5, dont le périmètre réel est le **câblage UI** et le **durcissement sécurité/permissions**,
pas de nouvelles règles métier.

## 1. Sécurité HTTP réelle (mission section 61 — nouveauté P1.5)

**Gap explicitement documenté dans `P1_DATASET_SYNTHETIQUE.md` section 7** ("Aucun test MockMvc
n'existait déjà dans ce codebase... Recommandation P2") — fermé dans ce chantier.

[`P1_5SecuriteHttpE2ETest`](../src/test/java/com/example/comptable/security/P1_5SecuriteHttpE2ETest.java)
— premier test du projet à émettre de vraies requêtes HTTP (MockMvc + vrai `DispatcherServlet` +
vraie chaîne `SecurityFilterChain`/`JwtAuthFilter`) avec de vrais jetons JWT mintés par le même
`JwtService` que la production, contre 2 tenants et 3 entreprises réelles :

| Scénario | Résultat attendu | Test |
|---|---|---|
| Requête sans jeton | 403 (rejet Spring Security, vérifié réel plutôt que supposé 401) | `requete_sans_jeton_est_rejetee` |
| EXPERT_COMPTABLE tenant A → entreprise tenant B | 404 (isolation tenant, jamais 403 qui confirmerait l'existence) | `expert_comptable_ne_voit_pas_lentreprise_dun_autre_tenant` |
| CLIENT → `/api/cabinet/cockpit` (portefeuille cross-entreprises) | 403 | `client_ne_peut_pas_appeler_le_cockpit_cabinet_global` |
| CLIENT → tableau de bord (réservé cabinet) | 403 | `client_ne_peut_pas_appeler_le_tableau_de_bord_reserve_au_cabinet` |
| CLIENT → documents de sa propre entreprise | 200 | `client_peut_lister_les_documents_de_sa_propre_entreprise` |
| CLIENT → documents d'une entreprise à laquelle il n'est pas rattaché | 4xx | `client_ne_peut_pas_lister_les_documents_dune_entreprise_a_laquelle_il_nest_pas_rattache` |
| CLIENT → Copilot comptable vs Copilot Dirigeant sur le même endpoint racine | 403 puis 200 | `client_ne_peut_pas_appeler_le_copilot_comptable_mais_peut_appeler_le_copilot_dirigeant` |
| EXPERT_COMPTABLE → tableau de bord de sa propre entreprise | 200 | `expert_comptable_peut_appeler_le_tableau_de_bord_de_sa_propre_entreprise` |

**Trouvaille en construisant ce test** : `spring.docker.compose.skip.in-tests` est vrai par
défaut ; `ComptableApplicationTests` (le flake historique "intermittent" documenté depuis P0)
était en réalité le seul `@SpringBootTest` du projet sans l'override explicite
`properties = "spring.docker.compose.skip.in-tests=false"` — corrigé dans ce chantier (voir
rapport final, point N).

## 2. Écran Trésorerie (mission section 50)

Le backend (`finance/CashPositionService`, `CashForecastService`) est P1, déjà couvert par
`P1_DATASET_SYNTHETIQUE.md` section 3. La nouveauté P1.5 est le câblage Angular
(`frontend/src/app/features/finance/tresorerie.component.ts`), vérifié **manuellement contre le
backend réel** dans le navigateur (pas de test Angular unitaire ajouté — cohérent avec le fait
qu'aucun test Angular n'existe ailleurs dans ce projet) :

| Vérification | Résultat |
|---|---|
| `GET .../tresorerie/position` et `.../tresorerie/prevision` réels, entreprise fraîchement créée | 200, 0 FCFA affiché avec source (compte) et date, jamais un chiffre nu |
| 3 cartes horizon (7j/30j/90j), aucun palier PREDICTED affiché | Confirmé dans le HTML rendu |
| Snapshot financier avec sélecteur de période | 200, valeurs à 0 cohérentes avec une entreprise neuve |

## 3. Écran Recouvrement (mission section 51)

Backend `recouvrement/CollectionRiskService` (P1, déjà testé unitairement). Câblage Angular
vérifié manuellement : `GET .../recouvrement/risques` → 200, état vide correct ("Aucun client à
risque") pour une entreprise neuve — le tri par risque et le texte d'action recommandée sont
couverts côté Java par `recouvrement/CollectionRiskServiceTest` (P1).

## 4. Écran Compliance (mission section 52)

Backend `fiscalite/conformite/ComplianceCenterService` (P1, déjà testé unitairement). Câblage
Angular vérifié manuellement : `GET .../conformite` et `.../conformite/tax-health` → 200 chacun,
score fiscal 100/100 par défaut (aucun Finding TAX_REVIEW pénalisant), listes d'obligations et
d'anomalies vides pour une entreprise neuve — cohérent avec `DossierComptableServiceTest` (P0)
qui prouve déjà le calcul du score pénalisé.

## 5. Evidence Panel — AccountingProposal (mission section 55, gap confirmé par l'audit initial)

**Gap confirmé par `docs/P1_5_ARCHITECTURE.md`** : "AccountingProposalController flagged as the
strongest verify-before-keeping candidate (zero UI, zero test at the service level)" — fermé
dans ce chantier.

[`AccountingProposalServiceTest`](../src/test/java/com/example/comptable/agent/comptabilisation/AccountingProposalServiceTest.java)
(7 tests, nouveau) :

| Cas | Test |
|---|---|
| Accepter une proposition PROPOSED → ACCEPTED, jamais de comptabilisation directe | `accepter_valide_la_proposition_sans_comptabiliser` |
| Rejeter une proposition PROPOSED → REJECTED | `rejeter_marque_la_proposition_rejetee` |
| Accepter une proposition déjà traitée → 409 | `accepter_une_proposition_deja_traitee_echoue_409` |
| Accepter une proposition d'une autre entreprise → 404 | `accepter_une_proposition_dune_autre_entreprise_echoue_404` |
| Accepter une proposition inexistante → 404 | `accepter_une_proposition_inexistante_echoue_404` |
| Obtenir la proposition en attente d'un document | `obtenirPourDocument_ne_retourne_que_la_proposition_en_attente` |
| Aucune proposition en attente | `obtenirPourDocument_retourne_vide_si_aucune_proposition_en_attente` |

Câblage Angular (`tableau-de-bord.component.ts`) vérifié par lecture de code et build strict
(`ng build --configuration=production`, template checking activé) — pas de proposition réelle
disponible dans l'entreprise de test manuelle (aucun document `TRAITE` créé), donc le bloc
accepter/rejeter n'a pas pu être exercé en direct dans le navigateur ; **limite assumée**,
documentée plutôt que masquée.

## 6. Evidence Panel — Assignation de Finding (mission section 55)

**Gap confirmé par l'audit initial** : `FindingService.assigner` existait côté backend (avec le
correctif de sécurité tenant de la Phase 1) mais aucune UI ne l'appelait, et aucun endpoint ne
listait les collaborateurs d'un tenant indépendamment de leurs tâches déjà assignées.

| Cas | Test |
|---|---|
| Lister les collaborateurs du tenant, y compris sans tâche assignée | `InboxServiceTest.liste_collaborateurs_du_tenant_meme_sans_tache_assignee` (nouveau) |
| Sélecteur d'assignation dans le tableau de bord, appelle `POST .../findings/{id}/assigner` | Vérifié manuellement : `GET /api/cabinet/collaborateurs` → 200 avec le collaborateur réel du tenant de test |

## 7. Copilot Dirigeant (mission section 46)

| Cas | Vérification |
|---|---|
| CLIENT autorisé sur `/copilot-dirigeant/question`, jamais sur `/copilot/question` | `P1_5SecuriteHttpE2ETest.client_ne_peut_pas_appeler_le_copilot_comptable_mais_peut_appeler_le_copilot_dirigeant` |
| Sous-ensemble d'intentions "vocabulaire non-comptable" (6 des 8 intentions) | `CopilotServiceTest.dirigeant_obtient_une_reponse_pour_une_intention_du_sous_ensemble_autorise`, `dirigeant_ne_peut_pas_declencher_une_intention_hors_sous_ensemble_meme_par_mots_cles`, `dirigeant_ne_peut_pas_declencher_lintention_anomalies_fournisseurs` |
| Isolation d'accès avant toute classification | `CopilotServiceTest.dirigeant_verifie_lacces_avant_toute_classification` |
| Les 6 libellés de suggestion du widget restent reconnus par le classifieur déterministe | `IntentClassifierTest.suggestions_du_copilot_dirigeant_restent_reconnues_par_le_classifieur` — **bug réel trouvé en testant l'endpoint en direct** (3 des 6 libellés initiaux tombaient dans la réponse de repli faute de correspondre aux mots-clés exacts), corrigé et verrouillé par ce test |
| Bout en bout réel dans le navigateur, 6 questions + 1 question hors périmètre | Vérifié manuellement via `fetch` authentifié contre le backend réel : les 6 réponses sont réelles (`fallback: false`), la question TVA tombe bien dans la réponse de repli |

## 8. `AI_PROVIDER=mock` reste fonctionnel

Aucune modification P1.5 ne touche `ai-service/app/services/llm_provider.py` ni son
comportement — tous les tests ci-dessus (sauf la vérification manuelle Copilot Dirigeant, qui
utilise le vrai fallback textuel de `CopilotExplainer` en environnement de dev sans clé
Anthropic réelle) passent en mode déterministe sans appel réseau externe.
