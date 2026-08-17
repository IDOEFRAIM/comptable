# Audit — Mission P1.9 « Validation Terrain + Product Telemetry + Documentation Complète »

Audit mené par 3 explorations ciblées en parallèle (données Core exploitables pour des KPI,
contradictions de documentation, télémétrie IA existante côté `ai-service`) — délibérément
scopé sur les inconnues réelles de P1.9 plutôt que de ré-auditer P0/P1/P1.5 déjà profondément
connus. Tous les faits ci-dessous sont vérifiés contre le code réel, pas supposés.

## A. Architecture réelle

- **Core (Spring Boot 4.1.0 / Java 26)** : autorité métier. Tous les modules P0/P1/P1.5 sont en
  place et testés (295 tests Java, 0 échec, 0 erreur au 2026-08-13). Aucune nouvelle entité
  métier n'a été ajoutée depuis P1.5 — l'architecture est stable.
- **`ai-service` (FastAPI)** : autorité IA. Provider abstrait (`LLMProvider`) avec
  `MockLLMProvider`/`AnthropicLLMProvider`, **mais utilisé uniquement par le Universal Matching
  Engine et le Copilot** — `extraction_service.py` et `fraude_service.py` appellent directement
  `anthropic_client.call_structured` (contournent l'abstraction), et `agent_orchestrator.py`
  (agent autonome) appelle directement le SDK Anthropic brut. Ceci n'est pas un bug (documenté
  comme volontaire dans les commentaires du code) mais signifie que `AI_PROVIDER=mock`
  **ne couvre pas** extraction/fraude/agent — seulement matching et copilot.
- **Frontend (Angular)** : Workspace unifié (P1.5), aucun composant de télémétrie/KPI
  administrateur n'existe encore.
- **Observabilité** : `spring-boot-starter-actuator` présent mais **zéro configuration**
  (`management.*`) — seul `/actuator/health` par défaut. Deux métriques Micrometer existent
  (`matching.ia.latence`, `matching.ia.decision` dans `RapprochementBancaireService`) —
  en mémoire, non persistées, non par-tenant. C'est le seul point d'observabilité existant.

## B. Fonctionnalités réelles (déjà livrées, vérifiées par tests)

P0 : Dossier Comptable Intelligent, Findings, Missing Evidence, Control Engine, Universal
Matching, Accounting Proposal, boucle WhatsApp, Closing Readiness. P1 : Cabinet Cockpit, Client
Health, Portfolio Risk, Workload, Copilot Evidence-First comptable + cabinet, Financial
Snapshot, Cash Position/Forecast, Collection Risk, Compliance Center, Cabinet Analytics. P1.5 :
Écrans Trésorerie/Recouvrement/Compliance câblés en UI, Evidence Panel (accepter/rejeter
proposition, assigner Finding), Copilot Dirigeant, premiers tests HTTP réels
(`P1_5SecuriteHttpE2ETest`), correctifs de sécurité (fuite tenant, `TaxRuleController`).

**Confirmé absent** : aucune persistance des questions/réponses/feedback Copilot (le service est
strictement `@Transactional(readOnly = true)`, aucune entité `Copilot*` n'existe) ; aucun motif
de rejet sur `Finding`/`AiMatchProposal`/`AccountingProposal` (rejeter ne fait que changer le
statut) ; aucun horodatage de clôture persistée sur `ExerciceComptable` (`ClotureReadinessService`
recalcule à la volée, jamais stocké).

## C. Fonctionnalités documentées (contradictions trouvées avec le code)

L'audit documentation a trouvé des contradictions concrètes, pas de généralités :

1. **`DOCUMENTATION.md` se contredit elle-même** : le corps du texte (module 19, lignes
   648-656) décrit le Copilot Dirigeant et les tests HTTP de permission comme livrés en P1.5,
   mais la checklist "🔄 À Implémenter/Améliorer" plus bas (lignes 1341, 1348-1351) les liste
   encore comme non faits. **Corrigé dans ce chantier** (section H).
2. **Date de dernière mise à jour périmée** : le pied de page de `DOCUMENTATION.md` affiche
   "2026-08-03" alors que le corps documente déjà P1.5 (daté du 2026-08-13). **Corrigé**.
3. **`ai-service/README.md` périmé sur un point précis** : la section "À finaliser avant mise
   en service" affirme encore *"non vérifiable depuis cet environnement, JDK absent"* pour
   `mvn test`, alors que `DOCUMENTATION.md` (et les rapports P0/P1/P1.5) confirment cette
   vérification faite à de multiples reprises avec JDK 26 réel. **Corrigé**.
4. **Incohérence numérique entre rapports** : `P1_RAPPORT_FINAL.md` affiche 272 tests Java en
   fin de P1 ; `P1_5_RAPPORT_FINAL.md` indique "avant ce chantier : 274 tests" — écart de 2 non
   expliqué. Investigation : impossible de reconstituer rétroactivement l'origine exacte de
   l'écart (aucun rapport intermédiaire entre P1 et P1.5 ne documente de tests ajoutés hors
   mission) — **documenté comme incohérence non résolue plutôt que masqué** (voir section H,
   pas de correction inventée).
5. **Propriété non documentée** : `agent.fraude.tva.tolerance-xof` existe dans
   `application.properties` mais absente du bloc `properties` du module 15 de
   `DOCUMENTATION.md`. **Corrigé**.
6. **Formulation FEC à la limite** : `DOCUMENTATION.md` ligne 218 dit *"conforme aux règles
   françaises de FEC"* — techniquement défendable (ça décrit la seule règle d'exclusion des
   brouillons) mais risque d'être lu hors contexte comme une affirmation de conformité
   générale. **Reformulé** pour décrire le mécanisme, pas affirmer une conformité (section H).
7. **Deux items non résolus depuis P0**, jamais actionnés dans aucune des 3 missions
   suivantes : (a) la double représentation "facture fournisseur" (`Document.FACTURE_ACHAT` vs
   `facture_fournisseur`), (b) le Universal Matching Engine et l'agent autonome jamais testés
   avec une vraie clé Anthropic (contrainte budgétaire). **Remontés en tête du P2 Decision
   Matrix** (section Q) comme candidats les plus mûrs — reportés 3 fois sans jamais être
   explicitement refusés ou planifiés.
8. **Aucun `README.md` racine n'existe** — ni à la racine du repo, ni dans `comptable/`. Le
   point d'entrée réel est `DOCUMENTATION.md`. **Corrigé** : un `README.md` racine complet est
   créé dans ce chantier (section H).

## D. Écart réel/documentation — synthèse

Au-delà des 8 contradictions ci-dessus, l'écart dominant n'est **pas** que la documentation
mente, mais qu'elle est **fragmentée** : 3 rapports finaux (`P0_RAPPORT_FINAL.md`,
`P1_RAPPORT_FINAL.md`, `P1_5_RAPPORT_FINAL.md`) + 2 docs d'architecture + 2 datasets +
`DOCUMENTATION.md` (1386 lignes) + `ai-service/README.md`, sans point d'entrée unique. C'est la
raison principale pour laquelle P1.9 crée un vrai `README.md` racine (section 33 de la mission)
plutôt que d'ajouter un 4ème rapport isolé de plus.

## E. Métriques déjà disponibles ("gratuites" — nouvelles requêtes sur données existantes)

Confirmé calculable **aujourd'hui sans nouvelle instrumentation**, juste de nouvelles requêtes
(même pattern que `CabinetAnalyticsService`, déjà en production) :

- **Finding** : temps de résolution (`createdAt`→`resolvedAt`), backlog/retard (`dueDate`),
  comptages par sévérité/type/statut. `FindingRepository` a déjà des méthodes de comptage.
- **AiMatchProposal** : taux d'acceptation/rejet, **auto-appliqué vs revu par un humain**
  (`decision=MATCH_AUTO` ou `validatedBy IS NULL` = automatique ; `validatedBy IS NOT NULL` =
  humain — signal déjà présent, jamais exploité).
- **AccountingProposal** : même logique (`status=APPLIED && validatedBy IS NULL` = niveau 3
  auto-appliqué).
- **DemandePiece** : taux de résolution, temps de résolution (`createdAt`→`resolueAt` — colonne
  confirmée déjà présente et déjà correctement horodatée à la fois sur réception et annulation).
- **Notification** : latence de lecture (`createdAt`→`dateEnvoi` quand `statut=LUE`) — **piège
  de nommage confirmé** : `dateEnvoi` ("date d'envoi") est en réalité utilisée comme "date de
  lecture" dans le code actuel, pas comme un horodatage d'envoi.

## F. Métriques manquantes (nécessitent une nouvelle instrumentation)

1. **Copilot** (usage, qualité, feedback) — zéro persistance existante, confirmé par recherche
   exhaustive. Nécessite une nouvelle table.
2. **Motif de rejet** (Finding/AiMatchProposal/AccountingProposal) — aucune colonne existante.
3. **Time to Closing** — `ExerciceComptable` n'a **aucun horodatage** au-delà des bornes de
   période (`date_debut`/`date_fin`, qui ne sont pas des dates d'audit) ; le statut READY n'est
   jamais persisté (recalculé à la volée). Nécessite soit une colonne `closed_at`, soit un
   log d'audit sur la clôture — non construit dans ce chantier (voir section Q, gap assumé,
   la mission section 29 interdit d'inventer un gain sans mesure honnête).
4. **Cash Forecast Quality** (`forecast_accuracy`) — nécessite un historique de paiements
   réalisés vs prévus sur une période significative, qui n'existe pas dans l'environnement de
   développement actuel (0 client réel avec historique). **Non construit** — conforme à
   l'instruction explicite de la mission (section 15) de ne jamais fabriquer une précision sans
   échantillon honnête. Le contrat de données est documenté pour une implémentation future.
5. **Coût/tokens IA** — le SDK Anthropic expose `response.usage.input_tokens`/`output_tokens`
   gratuitement mais le code actuel jette la réponse complète après en avoir extrait
   `block.input`. Instrumentable à coût quasi nul (section G).

## G. Instrumentation nécessaire (décision retenue)

Conformément à la section 25 de la mission ("ne pas construire une plateforme analytics
complète"), la décision est :

- **Aucune nouvelle table d'événements génériques** de type event-sourcing pour tout ce qui est
  déjà calculable depuis les entités existantes (section E) — uniquement de nouvelles requêtes
  de lecture dans des services `*AnalyticsService`/`*KpiService`, même pattern que
  `CabinetAnalyticsService`.
- **Une seule nouvelle table légère, append-only** : `copilot_query` (question, portée,
  intention, confiance, nombre de preuves, latence, feedback) — c'est le seul KPI qui n'a
  strictement aucune donnée existante à réutiliser.
- **Une colonne nullable ajoutée** à `finding`, `ai_match_proposal`, `accounting_proposal` :
  `rejection_reason` — minimal, pas de nouvelle table.
- **Côté `ai-service`** : pas de nouvelle dépendance (pas de Prometheus/OpenTelemetry) —
  wrapper léger autour de `LLMProvider.call_structured` avec `time.perf_counter()` +
  `logging` structuré JSON (latence, provider, opération, succès/échec, fallback), cohérent
  avec la philosophie du projet (dépendances minimales) et avec l'audit confirmant que rien de
  tel n'existe aujourd'hui.
- **Time to Closing et Cash Forecast Quality ne sont pas instrumentés** dans ce chantier — voir
  section F points 3-4, gaps assumés et documentés plutôt que masqués.

## H. Risques

- **Risque de sur-interprétation** : sur un environnement de développement avec une poignée
  d'entreprises synthétiques, tous les KPI ci-dessus auront un échantillon minuscule. Le
  dashboard doit afficher "N=X" à côté de chaque taux, jamais un pourcentage nu sur un petit
  échantillon (cohérent avec la discipline "jamais un chiffre nu" déjà appliquée en P1.5 pour
  la trésorerie).
- **Risque de double-comptage** : `AiMatchProposal` (rapprochement bancaire) et
  `AccountingProposal` (comptabilisation) sont deux domaines différents avec des statuts
  similaires (`ACCEPTED`/`REJECTED`/`APPLIED`) — le Human Touch Rate doit les traiter comme deux
  catégories distinctes, jamais fusionnées en un seul total qui masquerait lequel des deux
  domaines est réellement automatisé.
- **Risque de fuite de données personnelles** : le texte des questions Copilot peut contenir des
  informations sur des tiers (noms de clients/fournisseurs). Stocké quand même (nécessaire pour
  le feedback et l'analyse qualité) mais jamais exposé à un autre tenant, jamais dans les logs
  applicatifs génériques, purge documentée (section 53 de la mission).

## I. Plan de validation terrain

Voir [`P1_9_PILOT_PROTOCOL.md`](P1_9_PILOT_PROTOCOL.md) pour le protocole complet (avant/pendant/
après, 8 profils d'entreprise, baseline avant tout calcul de gain).

## J. Recommandations P2 provisoires

Voir [`P2_DECISION_MATRIX.md`](P2_DECISION_MATRIX.md) — matrice complète. Les deux candidats les
plus mûrs (reportés sans décision depuis P0, voir section C point 7) sont mis en tête plutôt que
d'introduire de nouveaux candidats non demandés par un usage réel.
