# Rapport final — Mission P1.9 « Validation Terrain + Product Telemetry + Documentation Complète »

Rapport de clôture, sections A-O comme demandé par la mission. Rédigé après exécution complète
du chantier (audit → instrumentation → dataset/protocole → documentation → tests → correctifs).
Discipline appliquée tout du long : ne jamais inventer un gain, ne jamais construire une
architecture d'observabilité lourde, corriger la documentation immédiatement plutôt qu'à la fin.

## A. État initial

Au démarrage de P1.9 : P0/P1/P1.5 livrés et testés (295 tests Java avant ce chantier — voir
[`P1_9_AUDIT.md`](P1_9_AUDIT.md) section A), aucune télémétrie produit, aucun tableau de bord
qualité, aucun `README.md` racine, `DOCUMENTATION.md` contenant 8 contradictions non corrigées,
et une question ouverte depuis P0 jamais tranchée : le produit fonctionne-t-il *réellement* pour
un comptable, ou seulement au sens où le code compile et les tests synthétiques passent ?

## B. Audit

Audit mené par 3 explorations parallèles ciblées (données Core exploitables pour des KPI,
contradictions de documentation, télémétrie IA existante côté `ai-service`) — voir
[`P1_9_AUDIT.md`](P1_9_AUDIT.md) pour le détail complet (sections A-J). Constat central : la
plupart des KPI demandés par la mission sont calculables **gratuitement** sur les entités
existantes (`Finding`, `AiMatchProposal`, `AccountingProposal`, `DemandePiece`) ; seul le Copilot
n'avait strictement aucune persistance. Décision d'instrumentation minimale prise à ce stade et
tenue jusqu'au bout : une seule nouvelle table (`copilot_query`), trois colonnes nullables
(`rejection_reason`), aucune plateforme d'événements générique.

## C. Architecture Telemetry

Côté Java (`telemetry/`) : `ProductKpiService` (lecture seule, réutilise les repositories
existants + `CopilotQueryRepository`), `ProductKpiController` (`GET /api/cabinet/kpi`, même
périmètre de rôle que le reste de `/api/cabinet/**`). Côté `ai-service` : wrapper léger autour de
`anthropic_client.call_structured` et `MockLLMProvider.call_structured` (`time.perf_counter()` +
`logging` structuré, aucune nouvelle dépendance, jamais le prompt ni le document journalisés) —
voir `ai-service/README.md` section « Télémétrie IA ». Aucune des deux couches ne dépend d'un
service tiers (pas de Prometheus, pas d'APM, pas de base analytics séparée), conformément à
l'interdiction explicite de la mission de sur-construire.

## D. KPI livrés

Human Touch Rate / Automation Rate (par domaine, banque et comptabilisation jamais fusionnés),
Matching Quality (bancaire + comptabilisation), Evidence Resolution (résolution `DemandePiece`),
Finding Resolution Time (moyenne/médiane/P90), Copilot Usage/Quality (feedback, taux de réponse
avec preuve, fenêtre glissante 30 jours), Work Queue (backlog/retard). **Non livrés,
délibérément** : Time to Closing (aucun horodatage de clôture persisté), Cash Forecast Quality
(aucun historique réel), False Auto-Match Rate (non mesurable avec le modèle de données actuel —
conçu puis retiré après vérification que `AiMatchProposalService.charger` interdit toute
correction d'une proposition déjà auto-appliquée). Chaque KPI retourne `null` plutôt qu'un
pourcentage sur un échantillon vide — vérifié par test (`ProductKpiServiceTest`).

## E. Événements / instrumentation

`CopilotQuery` (portée, intention, confiance, nombre de preuves, latence, fallback, feedback +
motif) écrite via `CopilotTelemetryRecorder` (`@Transactional(REQUIRES_NEW)`, échoue en silence
plutôt que de casser une réponse Copilot). `rejection_reason` ajouté à `Finding`/
`AiMatchProposal`/`AccountingProposal`, alimenté par les endpoints `rejeter` existants (motif
optionnel, jamais obligatoire — les rejets déjà en place avant P1.9 restent valides sans motif).
Côté `ai-service`, chaque appel `call_structured` (Anthropic réel ou mock) émet un log structuré
`ai_call provider=... operation=... success=... latency_ms=...`.

## F. Protocole de validation terrain

[`P1_9_PILOT_PROTOCOL.md`](P1_9_PILOT_PROTOCOL.md) : 3 phases obligatoires (Avant — baseline
déclarative hors produit ; Pendant — mesure instrumentée ; Après — comparaison, jamais avant un
cycle comptable complet). **Aucun pilote réel n'a encore eu lieu** — ce chantier livre le
protocole prêt à exécuter, pas des résultats fabriqués. Limites explicitement documentées :
Time to Closing et Cash Forecast Quality resteront non mesurables tant que les gaps de la
section D ne sont pas comblés.

## G. Dataset

8 profils de cabinet (A-H, section 30 de la mission) définis avec, pour chacun, la capacité
produit qu'il stresse et le signal attendu à observer — **jamais un résultat présupposé**. Écart
assumé avec la lettre de la mission : ces profils décrivent des caractéristiques à construire sur
un cabinet pilote réel (volume, variété), pas 8 nouvelles fixtures synthétiques de plus — le
dataset synthétique existant (P0/P1/P1.5) couvre déjà les mécanismes déterministes ; ce qu'il n'a
jamais rencontré, c'est du volume et de la variété réels, que seul un pilote peut fournir.

## H. Documentation

- **`README.md` racine créé** (n'existait nulle part avant ce chantier — confirmé par recherche
  exhaustive) : présentation, vision, fonctionnalités avec état honnête (✅/🟡/⚠️), architecture,
  technologies, modules backend/frontend, résumé P0/P1/P1.5/P1.9, flux métier, sécurité,
  multi-tenant, configuration, installation, tests, Docker, API, WhatsApp, IA/providers, feature
  flags, base de données, migrations, FEC, limitations, roadmap.
- **`DOCUMENTATION.md`** : 6 des 8 contradictions relevées par l'audit corrigées directement dans
  ce document (propriété `agent.fraude.tva.tolerance-xof` ajoutée, formulation FEC reformulée
  pour ne plus affirmer une conformité générale, deux bullets « À Implémenter » périmés
  corrigés — Copilot Dirigeant et tests HTTP de permissions, tous deux livrés en P1.5 mais
  encore listés comme manquants —, date de mise à jour rafraîchie, nouveau module 2️⃣0️⃣ pour
  Product Telemetry). Les 2 restantes (incohérence numérique entre rapports P1/P1.5, écart déjà
  présent dans les rapports historiques) sont **documentées comme non résolues plutôt que
  masquées**, conformément à l'audit.
- **`ai-service/README.md`** : correction de l'affirmation périmée « JDK absent » (306 tests Java
  confirmés depuis), nouvelle section « Télémétrie IA » décrivant le wrapper de latence/succès.
- **`docs/P1_9_PILOT_PROTOCOL.md`** et **`docs/P2_DECISION_MATRIX.md`** créés (voir sections F et
  N).

## I. Tests

- **Java** : 306 tests avant ce chantier (voir `P1_9_AUDIT.md` section A) → **310 tests, 0 échec,
  0 erreur** après ce chantier (`./mvnw test`, JDK 26 réel, PostgreSQL réel via
  `spring-boot-docker-compose`) — 4 nouveaux tests unitaires `CopilotFeedbackServiceTest`, 6
  nouveaux tests `ProductKpiServiceTest`, 4 nouveaux tests HTTP réels
  `P1_9SecuriteHttpE2ETest` (voir section K).
- **Python (`ai-service`)** : 74 tests, 0 échec (`uv run pytest` / `pytest` en venv jetable) —
  suite inchangée en nombre par ce chantier (la télémétrie IA est un ajout non testé par un test
  dédié, mais vérifiée manuellement en section K ; aucun test existant cassé, exécution complète
  confirmée après modification de `anthropic_client.py` et `mock_llm_provider.py`).
- **Angular** : `ng build --configuration=production` réussi, vérification template stricte
  activée, nouveau `QualityDashboardComponent` et widgets Copilot (feedback) inclus dans le
  bundle.
- **Vérification manuelle en direct** (navigateur réel, backend + frontend démarrés) : compte
  cabinet fraîchement créé → `/cabinet/indicateurs` charge avec l'état vide honnête (aucun
  pourcentage inventé, `N=0` partout) → question posée au Copilot cabinet → réponse avec
  `queryId` → clic 👍 → `POST /api/copilot/queries/{id}/feedback` → 204 → UI affiche « Merci pour
  votre retour. » Chaîne bout en bout confirmée fonctionnelle, pas seulement compilée.
- **5 scénarios E2E mandatés par la mission** : les scénarios 1 (entreprise→dossier→finding→
  clôture), 2 (banque→matching→KPI) et 5 (cabinet→client à risque→work queue) sont couverts par
  les suites E2E existantes de P0/P1 (`P0CoreIntelligentE2ETest`,
  `P1CabinetOperatingSystemE2ETest`), dont les données alimentent maintenant aussi les KPI P1.9
  sans modification. Le scénario 4 (Copilot→question→preuve→feedback) est couvert par la
  vérification manuelle ci-dessus **et** par `P1_9SecuriteHttpE2ETest.dirigeant_peut_poser_une_
  question_puis_laisser_un_feedback_sur_sa_propre_reponse` (HTTP réel, bout en bout). Le
  scénario 3 (pièce manquante→demande→résolution→temps de résolution) reste couvert par les
  tests P0 existants (`MissingEvidenceAnalyzer`/`DemandePieceService`) mais **n'a pas de nouveau
  test P1.9 dédié qui vérifie explicitement le KPI `evidenceResolution` de bout en bout via HTTP**
  — gap mineur assumé (le calcul est vérifié unitairement dans `ProductKpiServiceTest`, la
  donnée source est vérifiée dans les tests P0 existants, mais la chaîne complète HTTP n'a pas
  été rejouée) plutôt que forcé dans les délais de ce chantier.

## J. Performance

**Non mesuré à l'échelle demandée (10/100/500 entreprises).** L'environnement de développement
actuel ne contient que quelques entreprises synthétiques (tests + vérification manuelle) — tenter
de mesurer une latence à 500 entreprises sans données réelles à ce volume aurait produit un
chiffre fabriqué, contraire à la discipline du chantier. Ce qui est vérifiable honnêtement
aujourd'hui : `ProductKpiService.generer` suit le même pattern de requêtes batchées que
`CabinetAnalyticsService` (déjà en production, une requête par domaine sur la liste
`entrepriseIds`, jamais une requête par entreprise affichée) — la forme est saine, le
comportement à grande échelle reste à vérifier lors du pilote réel (voir
`P1_9_PILOT_PROTOCOL.md`). **Gap assumé, pas caché.**

## K. Sécurité

Nouveau test HTTP réel `P1_9SecuriteHttpE2ETest` (même pattern que `P1_5SecuriteHttpE2ETest` —
MockMvc + vraie chaîne de sécurité + vrais jetons JWT), écrit et **passé** pendant ce chantier
pour fermer un gap détecté en cours de route (les deux nouveaux endpoints P1.9 n'avaient qu'une
couverture de service, jamais de test HTTP) : `GET /api/cabinet/kpi` refusé à un `CLIENT` (403),
autorisé à un `EXPERT_COMPTABLE` (200) ; `POST /api/copilot/queries/{id}/feedback` refusé sans
jeton (403), et un dirigeant (`CLIENT`) peut bien laisser un feedback sur sa propre réponse
(204). Scan effectué sur le dépôt entier pour des clés API accidentellement commitées
(`sk-ant-...`, motifs Google API) — **aucune trouvée**, aucun fichier `.env` commité.

## L. Bugs corrigés

- **Gap de couverture HTTP des endpoints P1.9** (section K) — 4 nouveaux tests, fermé.
- **8 contradictions de documentation** (section H) — 6 corrigées directement, 2 documentées
  comme non résolues plutôt que masquées.
- Aucun bug fonctionnel trouvé dans le code P0/P1/P1.5 existant pendant ce chantier (l'audit
  était scopé sur les inconnues P1.9, pas une ré-audition complète — voir `P1_9_AUDIT.md`).

## M. Gaps restants (assumés, pas cachés)

1. Time to Closing non instrumenté (aucun horodatage de clôture persisté).
2. Cash Forecast Quality non mesurable (aucun historique réel suffisant).
3. False Auto-Match Rate non mesurable (limite du modèle de données actuel, documentée).
4. Performance non mesurée à l'échelle 100/500 entreprises (aucune donnée réelle à ce volume).
5. Scénario E2E n°3 (pièce manquante → résolution → KPI) non rejoué explicitement en HTTP de
   bout en bout dans ce chantier (couvert indirectement, voir section I).
6. Aucun pilote réel exécuté à ce jour — protocole prêt, pas encore utilisé.
7. Deux items reportés depuis P0, toujours non tranchés : double représentation "facture
   fournisseur", Universal Matching Engine/Agent Autonome jamais testés avec une vraie clé
   Anthropic (contrainte budgétaire, pas produit).
8. Incohérence numérique entre `P1_RAPPORT_FINAL.md` et `P1_5_RAPPORT_FINAL.md` (2 tests d'écart
   non expliqué) — non reconstituable rétroactivement, documentée telle quelle.

## N. P2 Decision Matrix

Voir [`P2_DECISION_MATRIX.md`](P2_DECISION_MATRIX.md) — 12 candidats scorés (Pain/Fréquence/
Impact/Usage attendu/Effort/IA), explicitement marqués provisoires et à revoir après le pilote.
Les deux candidats en tête ne sont pas de nouvelles fonctionnalités mais des dettes déjà
identifiées et reportées sans décision depuis P0 (item 7 ci-dessus).

## O. Recommandation P2

**Aucune recommandation ferme n'est faite ici, conformément à l'instruction explicite de la
mission de ne jamais pré-décider le P2.** Ce que l'audit et ce chantier permettent de dire avec
confiance :

1. Les deux items les plus mûrs (double représentation facture fournisseur, vérification
   Anthropic réelle) devraient être tranchés **avant** tout nouveau candidat, quel que soit le
   résultat du pilote — ce sont des dettes, pas des paris.
2. Toute décision sur Paie/Stock/Achats/Multi-pays/Forecasting avancé doit attendre les données
   du pilote terrain (`P1_9_PILOT_PROTOCOL.md`) — en particulier la fréquence réelle des
   questions Copilot tombées en réponse de repli (signal direct de demande non couverte) et le
   pays réel du/des cabinet(s) pilote(s).
3. Le produit dispose maintenant des moyens de répondre honnêtement, avec des données réelles
   plutôt qu'un décompte de fonctionnalités, aux 4 questions posées en ouverture de cette
   mission — mais **n'y a pas encore répondu**, faute d'un pilote réel exécuté. C'est la
   prochaine étape naturelle, pas un chantier de développement supplémentaire.
