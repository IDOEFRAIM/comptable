# Rapport final — Mission P1 « Cabinet Operating System + Financial Intelligence Layer »

Rapport obligatoire (mission, section 67, points A–N). Rédigé après exécution complète des
10 phases, chacune vérifiée par compilation + tests réels contre PostgreSQL réel avant de passer
à la suivante, exactement selon la discipline déjà établie en P0.

---

## A. Audit initial

Cinq audits parallèles ont précédé toute décision d'architecture : modèle tenant/RBAC, modèle
financier (comptes/tiers/factures), contrats exacts du P0 (Finding, DossierComptableService,
etc.), architecture Python (`LLMProvider`/`MockLLMProvider`), et frontend Angular (routing,
shell, conventions de service). Faits déterminants :

- **`Tenant` = cabinet, `Entreprise` = client** déjà modélisé (`Entreprise.tenant`,
  `EntrepriseRepository.findByTenantId`) — mais **aucune vue cabinet-wide n'existait** : tout le
  P0 opère strictement sur un `entrepriseId` de path variable.
- **4 rôles réels** (`SUPER_ADMIN`, `EXPERT_COMPTABLE`, `COLLABORATEUR`, `CLIENT`), pas 3 comme
  supposé initialement par la mission. `EntrepriseAccessService.verifierAcces` est le seul
  point de contrôle d'accès — tout filtrage cabinet-wide doit vérifier le rôle **avant**
  d'agréger, jamais après.
- **`Finding` n'a aucune notion d'assignation** — le Workflow Collaboratif nécessite une
  extension minimale (2 colonnes), pas une nouvelle table `Task`.
- **`DossierComptableDto.scoreGlobal` EST DÉJÀ un Client Health Score déterministe** — le P1 n'a
  aucune raison de recalculer un second score.
- **`soldeTresorerie`, `BalanceAgeeService` (aging 30/60/90j) existent déjà** — fondations
  directes de Cash Position et Collection Risk.
- **Aucune entité Mobile Money n'existe** — la mission interdit de supposer une intégration
  externe ; le canal est dérivé par heuristique sur les comptes classe 5 existants.
- **`agent_orchestrator.py` (agent autonome) n'est pas réutilisable pour le Copilot** : il est
  structurellement capable d'écriture et n'a aucun point d'injection de provider mock,
  contrairement à `matching_engine.analyze(..., llm_provider=...)`.
- **Un export FEC réel et fonctionnel existait déjà** (`ExportFecService`, 18 colonnes
  officielles, endpoint `GET /api/entreprises/{id}/rapports/fec/{exerciceId}`, 5 tests réels) —
  **découvert tardivement en Phase 10**, jamais documenté avant ce chantier. Voir section M pour
  la correction complète de cette erreur d'audit initiale.

---

## B. Plan choisi

Décisions consignées dans [`docs/P1_ARCHITECTURE.md`](P1_ARCHITECTURE.md) avant tout code,
conformément à l'instruction de la mission (« ne demande pas de confirmation après l'audit »).
Dix phases, dans cet ordre :

1. Fondations (`Finding.assignedTo`/`dueDate`, `AuditTrailService` sur `audit_log` existant,
   enums `Notification`).
2. Cabinet Cockpit + Client Health + Portfolio Risk.
3. Work Queue unifiée (Finding + DemandePiece) + assignation + charge de travail.
4. Financial Snapshot + Cash Position multicanal.
5. Evidence-First Copilot niveau comptable.
6. Cash Forecasting (KNOWN/EXPECTED) + Collection Risk.
7. Compliance Center + Tax Health + schéma TaxRule.
8. Cabinet Analytics + Copilot niveau cabinet.
9. UI Angular (Cockpit, widget Copilot, navigation).
10. Tests E2E + dataset synthétique + ce rapport.

Le Copilot (phase 5) est placé après le Cockpit (phase 2) plutôt qu'en position médiane comme
suggéré : son contrat de preuve (`evidence[]` citant des Finding/entreprises réels) suppose que
les agrégations qu'il explique existent déjà et sont stables.

---

## C. Modules livrés

| Domaine mission | Livré |
|---|---|
| Cabinet Cockpit (8) | `CabinetCockpitService` : compteurs PRET/A_SURVEILLER/BLOQUE, "à traiter aujourd'hui", top risques — une seule requête batchée `Finding` pour tout le portefeuille |
| Client Health Score (9) | Pure projection de `DossierComptableDto` (P0), aucun second algorithme |
| Portfolio Risk (10) | `CabinetCockpitDto.topRisques`, causes/sévérité/depuis quand/action — sourcé sur `Finding` réels |
| Workflow collaboratif (11) | `Finding.assignedTo`/`dueDate`, endpoint `assigner`, notification automatique |
| Work Queue unifiée (12) | `InboxService` : `Finding` + `DemandePiece` dans une seule liste triée, aucune nouvelle table |
| Workload (13) | `InboxService.chargeParCollaborateur` |
| AI Copilot comptable (14-19) | `CopilotService` + `IntentClassifier` (8 intentions), pipeline classification→faits→LLM |
| Financial Snapshot (20) | `FinancialSnapshotService` : cash/receivables/payables/revenue/expenses/profit/taxes + comparaisons |
| Trésorerie multicanal (21-22) | `CashPositionService` : classe 5 par compte, canal dérivé par heuristique |
| Cash Forecasting (23-25) | `CashForecastService` : KNOWN + EXPECTED (pondéré par ancienneté), **PREDICTED non livré** (voir M) |
| Recouvrement (26-29) | `CollectionRiskService` : LOW/MEDIUM/HIGH sur `BalanceAgeeService`, jamais d'envoi automatique |
| Compliance Center (30) | `ComplianceCenterService` : obligations + anomalies `TAX_REVIEW` |
| Architecture réglementaire (31) | Schéma `TaxRule` versionné, **volontairement vide** (aucune source vérifiée) |
| FEC (32) | **Déjà existant avant ce chantier**, corrigé et documenté en Phase 10 |
| Tax Health (33) | Projection du score de domaine "Fiscalite" du Dossier P0 |
| Audit Trail (34-35) | `AuditTrailService` sur `audit_log` (jamais écrit avant ce chantier), source HUMAIN/IA jamais mélangée |
| Cabinet Analytics (36) | `CabinetAnalyticsService` : dossiers à jour/en retard, temps moyen résolution, taux demandes résolues |
| Copilot cabinet (40) | `CabinetCopilotService` + `CabinetIntentClassifier` (5 intentions) |
| Copilot dirigeant (41-42) | **Non livré** (voir M) |
| Permissions Copilot (43) | RBAC vérifié avant classification, jamais après |
| Routage IA (44) | Classification 100% Java déterministe avant tout appel LLM |
| Agent Exception Resolution (45-46) | **Non intégré** au Copilot (voir M) — `agent_orchestrator.py` inchangé |
| Evidence-First (47) | `EvidenceRefDto` sur toute réponse Copilot, même convention que `Finding` |
| Performance Copilot (48) | Contexte compact (faits Java résumés), jamais de dump de base |

---

## D. Modèles créés

Une seule vraie extension de schéma : **deux colonnes sur `Finding`** (`assigned_to_id`,
`due_date`, migration V26). Une nouvelle table `tax_rule` (V27), **volontairement vide**. Aucun
autre nouveau modèle de fait métier — voir la table de décision complète par abstraction
proposée dans [`docs/P1_ARCHITECTURE.md`](P1_ARCHITECTURE.md#abstractions-transversales--décision-une-par-une-section-7)
(`CabinetWorkItem`, `FinancialAlert`, `ComplianceDeadline` rejetés ; `FinancialSnapshot`,
`CashForecast`, `CollectionRisk` acceptés comme DTO calculés à la lecture, jamais persistés).

`AuditEvent` (nouvelle entité) mappe la table `audit_log` **déjà créée par la migration V1**
mais jamais écrite jusqu'ici — pas une nouvelle table, la première utilisation réelle d'une
existante.

---

## E. Endpoints API livrés

```
GET    /api/cabinet/cockpit
GET    /api/cabinet/inbox?assigneAMoi=
GET    /api/cabinet/charge-collaborateurs
GET    /api/cabinet/analytics
POST   /api/cabinet/copilot/question

POST   /api/entreprises/{id}/findings/{findingId}/assigner

GET    /api/entreprises/{id}/tresorerie/position
GET    /api/entreprises/{id}/tresorerie/prevision
GET    /api/entreprises/{id}/financial-snapshot
GET    /api/entreprises/{id}/financial-snapshot/comparaison

GET    /api/entreprises/{id}/recouvrement/risques

GET    /api/entreprises/{id}/conformite
GET    /api/entreprises/{id}/conformite/tax-health
GET    /api/fiscalite/tax-rules?pays=

POST   /api/entreprises/{id}/copilot/question
```

Toutes protégées par `hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")`, jamais `CLIENT` (voir
section G).

---

## F. Répartition Java / Python par capacité

| Capacité | Java | Python |
|---|---|---|
| Agrégation Cabinet Cockpit/Analytics | 100% Java, aucun appel IA | — |
| Classification d'intention (Copilot comptable et cabinet) | 100% Java, mots-clés déterministes | — |
| Collecte des faits (evidence) | 100% Java, réutilise services P0/P1 existants | — |
| Mise en mots de la réponse Copilot | — | `copilot_service.py`, un seul appel `LLMProvider.call_structured`, jamais de calcul |
| Score Client Health / Tax Health | 100% Java (projection P0) | — |
| Cash Forecast KNOWN/EXPECTED | 100% Java, pondération déterministe documentée | — |
| Collection Risk | 100% Java, seuils déterministes sur `BalanceAgeeService` | — |

Aucune nouvelle capacité Python de calcul n'a été nécessaire — confirmation directe que « l'IA
propose déjà tout ce qui est nécessaire » (même conclusion qu'en P0) : le Copilot n'ajoute qu'une
**mise en mots**, jamais un calcul.

---

## G. Sécurité

- **Isolation tenant** : `Cabinet Cockpit`/`Inbox`/`Analytics`/`Copilot cabinet` scopés
  exclusivement sur `principal.getTenantId()`, jamais un `entrepriseId` de path variable
  falsifiable.
- **RBAC** : chaque nouvel endpoint déclaré explicitement dans `SecurityConfig`
  (`hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")`), jamais accessible à `CLIENT`.
- **Fuite corrigée pendant le développement** : `DemandePieceRepository.findEnRetard` est une
  requête globale (tous tenants confondus, réservée au job de relance en arrière-plan) — la
  réutiliser telle quelle pour le Cockpit aurait fuité les demandes en retard d'autres cabinets.
  Une variante tenant-scopée (`countByEntrepriseIdInAndStatutAndDateLimiteBefore`) a été créée
  à la place.
- **Filtrage avant IA** (mission section 43/55) : le Copilot vérifie l'accès
  (`entrepriseAccessService.verifierAcces`) **avant** la classification d'intention, donc avant
  toute collecte de données — jamais de données envoyées au LLM sans ce contrôle préalable.
- **Limite assumée** : aucun test HTTP (`MockMvc`) ne vérifie explicitement qu'un token `CLIENT`
  reçoit un 403 sur ces endpoints — voir section M.

---

## H. Écrans UI modifiés/créés

- **Nouveau** : `/cabinet` (`CockpitCabinetComponent`), guardé (`cabinetGuard`), lien "Cabinet"
  dans la navigation principale (visible uniquement `EXPERT_COMPTABLE`/`COLLABORATEUR`) — score
  du portefeuille, "à traiter aujourd'hui", top risques, liste clients, charge par
  collaborateur, analytics, widget Copilot cabinet.
- **Nouveau, réutilisable** : `CopilotWidgetComponent` (`app-copilot-widget`), embarqué à la fois
  dans le Cockpit cabinet et dans le tableau de bord entreprise existant — suggestions de
  questions fermées, réponse structurée (texte + preuves + actions recommandées + confiance),
  jamais un chat libre.
- **Corrigé en passant** : `LIBELLES_STATUT_DOSSIER` du P0 utilisait la clé `READY` alors que
  Java renvoie `PRET` — le badge de statut du tableau de bord affichait un texte vide en
  production depuis la Phase 7 du P0. Corrigé en Phase 9 (bug réel trouvé, pas cherché).
- **Vérifié dans un vrai navigateur** contre le backend réel (Postgres réel, `ai-service` non
  démarré pour prouver la dégradation gracieuse) : inscription d'un nouveau cabinet, création
  d'un client, navigation Cockpit → Copilot cabinet (réponse dégradée correcte, confiance 95%) →
  tableau de bord entreprise → Copilot comptable (même comportement). Aucune erreur console.

---

## I. Tests — résultats réels

```
Java  : 272 tests, 0 échec, 1 erreur (flake preexistant ComptableApplicationTests, documenté
        depuis le P0, sans rapport avec ce chantier)
Python: 74 tests, 0 échec
```

Progression par phase (chaque phase vérifiée avant de passer à la suivante) :

| Phase | Tests Java après |
|---|---|
| Avant P1 (fin P0) | 230 |
| 1 — Fondations | 236 |
| 2 — Cabinet Cockpit | 238 |
| 3 — Work Queue | 241 |
| 4 — Financial Snapshot/Cash Position | 246 |
| 5 — Copilot comptable | 252 |
| 6 — Cash Forecast/Collection Risk | 255 |
| 7 — Compliance Center | 257 |
| 8 — Cabinet Analytics/Copilot cabinet | 266 |
| 9 — UI (aucun test backend ajouté) | 266 |
| 10 — E2E | 272 |

---

## J. Scénarios E2E réellement exécutés

Un seul fichier ([`P1CabinetOperatingSystemE2ETest`](../src/test/java/com/example/comptable/cabinet/P1CabinetOperatingSystemE2ETest.java)),
6 tests, contre Postgres réel, seul `AiServiceClient` mocké (uniquement pour la mise en mots) :

1. **Cabinet Cockpit** : 2 entreprises réelles (une saine, une avec un `Finding` CRITICAL réel)
   → agrégation correcte (`totalClients=2`, `clientsBloques=1`, top risques trié).
2. **Copilot cabinet** : question réelle → preuve réelle (`entrepriseId` du client bloqué) →
   réponse structurée.
3. **Copilot comptable** : question réelle sur le client à risque → explication + preuve +
   confiance.
4. **Trésorerie** : écriture réelle sur compte 521 → `CashPositionDto.total` exact ; factures
   réelles (une à échéance connue, une en retard) → `PrevisionTresorerieDto` KNOWN/EXPECTED
   corrects (450 000 = 500 000 × 0.90, vérifié au franc près).
5. **Recouvrement** : écriture réelle sur compte 411 vieille de 70 jours, tiers non lettré →
   `CollectionRiskDto` HIGH + "contact humain".
6. **Compliance** : déclaration fiscale réelle + `Finding TAX_REVIEW` réel → les deux apparaissent
   dans `ComplianceCenterDto`.

Complément manuel (Phase 9, section H) : parcours navigateur réel confirmant l'UI de bout en
bout, y compris la dégradation du Copilot sans `ai-service`.

Dataset synthétique et couverture par domaine détaillés dans
[`docs/P1_DATASET_SYNTHETIQUE.md`](P1_DATASET_SYNTHETIQUE.md), avec l'écart assumé sur le
nombre d'entreprises (2 réelles au lieu de 20 suggérées) explicitement justifié.

---

## K. Performance

- **Cabinet Cockpit/Analytics** : une seule requête batchée `Finding` pour tout le portefeuille
  (`findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc`), jamais une requête par
  client affiché — conforme à la mission section 54.
- **Limite assumée et documentée dès l'architecture** : `CabinetCockpitService.generer` appelle
  `DossierComptableService.generer` (qui rafraîchit `ControlEngine`, donc écrit en base) **une
  fois par entreprise du cabinet** à chaque ouverture du Cockpit. Correct et sans duplication de
  logique de score, mais pas optimal à très grande échelle (centaines de clients). Non corrigé
  dans ce chantier — un job de rafraîchissement planifié (déjà recommandé en P0) ou une
  pagination côté UI seraient l'évolution naturelle P2, une fois le volume réel de clients par
  cabinet connu.
- **Aucune requête N+1 introduite** : toutes les nouvelles requêtes cross-entreprise (Cockpit,
  Inbox, Analytics) sont batchées par construction (`findByEntrepriseIdIn...`), jamais une boucle
  de requêtes individuelles.

---

## L. KPIs livrés (mesurables dès maintenant)

Contrairement au P0 (KPIs proposés, non instrumentés), le P1 **livre directement** les 4 premiers
KPIs "Cabinet" de la mission (section 64), via `GET /api/cabinet/analytics` :

- `nombreDossiers`, `dossiersAJour`, `dossiersEnRetard` — comptages réels, pas des estimations.
- `tempsMoyenResolutionJours` — moyenne réelle (`createdAt`→`resolvedAt`) sur les `Finding`
  résolus du portefeuille.
- `tauxDemandesResoluesPourcent` — `RECU / (RECU+EN_ATTENTE+ANNULEE)` réel.

**Non livrés** (documentés section M) : taux de rapprochement, taux de validation IA, KPIs
Copilot (questions/latence/feedback — section 49-50), forecast error, overdue amount agrégé,
obligations à l'heure/en retard agrégées au niveau cabinet.

---

## M. Limites connues et recommandations P1→P2

- **Cabinet Copilot Dirigeant** (mission section 41-42) — **non livré**. Un seul Copilot cabinet
  existe (vocabulaire comptable), pas de variante "orientée décision" pour le dirigeant. Le
  moteur de données est identique (`CabinetCockpitService`/`InboxService`), seule une couche de
  vocabulaire/intentions dirigeant manquerait — extension directe si le besoin se confirme.
- **Cash Forecasting palier PREDICTED** — non livré. Aucune donnée de paiement historique
  (date de règlement réelle) n'est persistée aujourd'hui sur `Facture`, donc aucune "prédiction
  IA/statistique" n'aurait de valeur réelle — mieux vaut l'absence que l'invention.
- **Exception Resolution Agent** (section 45-46) — le Copilot ne route pas encore vers
  `agent_orchestrator.py` pour les cas ambigus ; celui-ci reste inchangé, isolé, non
  mock-testable (limite héritée du P0, non résolue ici).
- **KPIs Copilot** (questions posées, latence, feedback utile/pas utile) — aucune télémétrie
  n'est stockée. Le contrat structuré (`answer`/`confidence`/`evidence`/`recommendedActions`)
  est prêt à être journalisé, mais rien ne le fait encore.
- **Permissions non vérifiées par un test HTTP** — chaque endpoint déclare son rôle requis dans
  `SecurityConfig`, mais aucun test `MockMvc` ne prouve un 403 réel pour un token `CLIENT`.
  Premier test HTTP jamais introduit dans ce codebase serait ce test — actuellement en dette.
- **FEC** — **correction majeure** : ce chantier a d'abord affirmé (Phase 7, avant la découverte
  de Phase 10) qu'aucun export FEC n'existait et que c'était bloqué sur une source réglementaire
  manquante. **C'était faux** : un export FEC réel, fonctionnel et testé (`ExportFecService`,
  18 colonnes officielles) existait déjà dans le code, jamais documenté. Corrigé dans
  `DOCUMENTATION.md` et `docs/P1_ARCHITECTURE.md`. Reste une vraie ambiguïté à lever : le format
  cible le FEC français (DGI) mais les données de test utilisent un identifiant contribuable
  ivoirien — la conformité réelle pour un usage OHADA/Burkina Faso doit être validée avec un
  expert-comptable local avant tout usage réel, exactement l'esprit de prudence de la mission
  section 31.
- **Dataset synthétique** — 2 entreprises réelles au lieu des 20 suggérées (justifié section J).
- **`TaxRule`** — schéma prêt, aucune règle réelle peuplée (aucune source vérifiée disponible).
- **Cabinet Cockpit à très grande échelle** — voir section K, recalcul par entreprise à chaque
  ouverture.

---

## N. Recommandation d'investissement suivante

1. **MockMvc + suite de tests HTTP par rôle** (effort faible, valeur élevée) : c'est la seule
   vraie dette de sécurité non vérifiée de ce chantier — combler avant tout accès `CLIENT`
   élargi aux nouvelles fonctionnalités P1.
2. **Clarifier et documenter le périmètre réglementaire du FEC** (effort faible) : la
   fonctionnalité existe déjà, il ne manque qu'une validation métier (expert-comptable local)
   pour la rendre utilisable en production sur le marché prioritaire (Burkina Faso).
3. **Copilot Dirigeant** (effort faible, réutilise le moteur existant) : complète la promesse de
   la mission section 41-42 à moindre coût, le Cockpit et l'Inbox sont déjà là.
4. **Job planifié pour rafraîchir le Cockpit** (effort faible-moyen) : transforme le recalcul
   synchrone par entreprise en un système proactif, nécessaire avant un vrai déploiement à
   centaines de clients (déjà recommandé en P0 pour `ControlEngine`, le même job couvrirait les
   deux besoins).
5. **KPIs Copilot + feedback utilisateur** (section 49-50, effort moyen) : première brique d'un
   cycle d'amélioration piloté par l'usage réel, mentionné comme objectif P2 explicite par la
   mission elle-même.
