# Comptable — Plateforme SaaS de Gestion Comptable pour l'Afrique de l'Ouest

Point d'entrée technique unique du dépôt (créé en mission P1.9 — jusqu'ici aucun `README.md`
n'existait ni à la racine, ni dans `comptable/`, un écart confirmé par l'audit
[`comptable/docs/P1_9_AUDIT.md`](comptable/docs/P1_9_AUDIT.md) section C.8). Ce document donne
la vue d'ensemble et pointe vers les documents de référence pour le détail ; il ne duplique pas
[`comptable/DOCUMENTATION.md`](comptable/DOCUMENTATION.md) (référence fonctionnelle/technique
complète, module par module) ni [`ai-service/README.md`](ai-service/README.md) (couche IA).

## 1. Présentation

**Comptable** est une plateforme SaaS destinée aux cabinets d'experts-comptables d'Afrique de
l'Ouest (zone UEMOA, comptabilité SYSCOHADA, Franc CFA). Elle couvre le cycle comptable complet
d'un client — réception des pièces (upload web ou WhatsApp), extraction automatique, contrôle de
fraude, rapprochement bancaire (y compris Mobile Money), comptabilisation, suivi fiscal,
trésorerie, recouvrement et clôture — avec une couche d'intelligence artificielle qui **propose**
mais ne **décide** jamais seule (voir section 12).

## 2. Vision

Le problème adressé n'est pas l'absence d'un logiciel de comptabilité (SYSCOHADA existe depuis
longtemps), mais la charge manuelle qui entoure la tenue : dépouiller des pièces reçues sur
WhatsApp, rapprocher des relevés Mobile Money sans référence claire, relancer des clients pour
des pièces manquantes, savoir si un dossier est prêt pour la clôture sans tout reparcourir à la
main. Le produit construit une couche de **synthèse et d'automatisation** au-dessus d'un cœur
comptable classique, mesurée honnêtement plutôt que promise (voir section 20, Product
Telemetry) : combien de travail humain est réellement retiré, pas combien de fonctionnalités
existent.

## 3. Fonctionnalités

| Domaine | État | Détail |
|---|---|---|
| Comptabilité SYSCOHADA (journaux, écritures, plan comptable, lettrage, clôture) | ✅ Opérationnel | Module 3️⃣ de `DOCUMENTATION.md` |
| Facturation, immobilisations, stock, fiscalité (déclarations) | ✅ Opérationnel | Modules 2️⃣/5️⃣ |
| Extraction automatique de pièces (VLM, upload + WhatsApp) | ✅ Opérationnel (Groq) | Module 9️⃣ |
| Contrôle de fraude déterministe (TVA, montant rond, doublon) | ✅ Opérationnel, gratuit | Module 1️⃣5️⃣ |
| Contrôle de fraude visuel (VLM) | 🟡 Derrière un flag (`agent.fraude.analyse-visuelle.enabled`) | Module 1️⃣5️⃣ |
| Rapprochement bancaire déterministe | ✅ Opérationnel | Module 1️⃣4️⃣ |
| Rapprochement sémantique Mobile Money + Universal Matching Engine | 🟡 Derrière un flag, jamais testé avec une vraie clé Anthropic | Modules 1️⃣4️⃣/1️⃣7️⃣ |
| Dossier Comptable Intelligent (score, Findings, Missing Evidence) | ✅ Opérationnel | Module 1️⃣8️⃣ |
| Proposition de comptabilisation automatique (niveaux 0-3) | 🟡 Derrière un flag | Module 1️⃣8️⃣ |
| Boucle WhatsApp (relance de pièces manquantes) | 🟡 Derrière un flag, nécessite un template Meta approuvé | Module 1️⃣3️⃣ |
| Cabinet Cockpit, Work Queue, Cabinet Analytics | ✅ Opérationnel | Module 1️⃣9️⃣ |
| Trésorerie, Recouvrement, Compliance Center | ✅ Opérationnel | Module 1️⃣9️⃣ |
| Copilot (comptable, cabinet, dirigeant) | ✅ Opérationnel, questions structurées fermées, jamais de chat libre | Module 1️⃣9️⃣ |
| Agent Comptable Autonome (boucle multi-tours) | ⚠️ Expérimental, endpoint isolé, non câblé aux flux d'ingestion | Module 1️⃣6️⃣ |
| Product Telemetry (KPI qualité, feedback) | ✅ Opérationnel | Module 2️⃣0️⃣ |
| Export FEC | 🟡 Fonctionnel mais périmètre réglementaire à clarifier avant usage réel | Section 21 |

Légende : ✅ opérationnel · 🟡 partiel/derrière un flag · ⚠️ expérimental · 🔴 indisponible.
Aucune ligne 🔴 aujourd'hui — la liste complète des écarts et limites est en section 24.

## 4. Architecture

```
┌─────────────────┐      HTTPS/JWT       ┌──────────────────────┐
│  Angular 18      │ ───────────────────▶ │  Spring Boot 4.1.0    │
│  (frontend/)      │ ◀─────────────────── │  Java 26 (comptable/) │
└─────────────────┘                       │  — autorité métier —  │
                                            └───────────┬───────────┘
                                                         │ REST, cle API interne
                                                         │ (2 secrets, rotation
                                                         │  independante)
                                                         ▼
                                            ┌──────────────────────┐
                                            │  ai-service (FastAPI) │
                                            │  — autorité IA —      │
                                            │  Groq + Anthropic     │
                                            └──────────────────────┘
```

Deux autorités séparées et non-interchangeables (voir section 12) : **Spring Boot** décide, valide,
calcule, persiste et autorise ; **ai-service** comprend, classe, prédit, explique et recommande —
sans jamais écrire directement en base de données métier. Le Core reste fonctionnel si
`ai-service` est indisponible (dégradation vers `NO_MATCH`/`AI_UNAVAILABLE`, jamais une
exception qui remonte à l'utilisateur).

## 5. Technologies

**Backend** : Spring Boot 4.1.0, Java 26, PostgreSQL, Spring Data JPA, Flyway (migrations
versionnées), Spring Security (JWT), Spring Batch.
**Frontend** : Angular 18.2, TypeScript, composants standalone, signaux (`signal`/`computed`),
SCSS.
**IA (`ai-service`)** : Python, FastAPI, SDK Groq (extraction VLM, fraude visuelle) et SDK
Anthropic/Claude (Universal Matching Engine, Copilot), `httpx` pour les appels vers Spring Boot.
**Infrastructure** : Docker / Docker Compose (PostgreSQL en développement via
`spring-boot-docker-compose`).

## 6. Modules backend (`comptable/src/main/java/com/example/comptable/`)

| Package | Rôle |
|---|---|
| `identite/` | Authentification, tenants, utilisateurs, rôles |
| `entreprise/` | Entreprises clientes d'un cabinet |
| `comptabilite/` | Journaux, écritures, plan comptable, lettrage, états financiers, FEC |
| `document/` | Ingestion et cycle de vie des pièces |
| `fiscalite/` | Déclarations, obligations, `fiscalite/conformite/` (Compliance Center) |
| `dashboard/` | Tableau de bord entreprise (compteurs) |
| `demande/` | `DemandePiece` — cycle de demande/réponse de pièce manquante |
| `notification/` | File de notifications sortantes |
| `stock/` | Gestion de stock |
| `dossier/` | `DossierComptableService` — score déterministe du dossier (P0) |
| `agent/` | Toute la couche agentique côté Java : `finding/` (Findings), `comptabilisation/` (AccountingProposal), `matching/` (Universal Matching Engine côté Java), `fraude/`, `extraction/`, `rapprochement/`, `relance/`, `pipeline/` (LangGraph4j), `tools/`, `client/` (`AiServiceClient`), `whatsapp/` |
| `cabinet/` | Cabinet Cockpit, Work Queue, Cabinet Analytics (P1) |
| `copilot/` | Copilot comptable/cabinet/dirigeant + `CopilotQuery` (P1.9) |
| `finance/` | Trésorerie, prévisions, Financial Snapshot (P1) |
| `recouvrement/` | Collection Risk (P1) |
| `telemetry/` | Product Telemetry / KPI qualité (P1.9) |
| `audit/` | `AuditTrailService` (traçabilité d'un sous-ensemble d'actions) |
| `security/` | JWT, filtres, `SecurityConfig` |
| `config/` | Configuration Spring |
| `common/` | Utilitaires partagés |

## 7. Modules frontend (`comptable/frontend/src/app/features/`)

`auth`, `entreprises`, `dashboard`, `comptabilite`, `documents`, `demandes`, `notifications`,
`fiscalite`, `conformite`, `finance`, `recouvrement`, `cabinet`, `copilot`, `telemetry` — un
dossier par domaine, chacun avec son service HTTP (`inject(HttpClient)`), ses modèles TS et ses
composants standalone. Pas de state management global (NgRx etc.) — signaux locaux par
composant, cohérent avec la taille actuelle de l'application.

## 8. AI Service (`ai-service/`)

Microservice FastAPI séparé, seule porte d'entrée vers Groq (extraction, fraude visuelle) et
Anthropic Claude (matching, Copilot). Voir
[`ai-service/README.md`](ai-service/README.md) pour le détail complet (Universal Matching
Engine, Agent Comptable Autonome, provider abstrait `LLMProvider`/`MockLLMProvider`, télémétrie
IA). Résumé : `extraction/document` et `fraude/analyse-visuelle` (appels structurés uniques),
`matching/analyze` (Universal Matching Engine, pipeline déterministe + 1 appel LLM au plus),
`agent/analyser-piece` (agent autonome, vraie boucle multi-tours bornée), `whatsapp/webhook`
(point d'entrée Meta Cloud API).

## 9. P0 — Core Intelligent de Production Comptable

Dossier Comptable Intelligent (score déterministe par domaine), `Finding` (inbox d'exceptions
unifiée), Missing Evidence Engine + Control Engine (règles Java sur des signaux déjà calculés,
pas de nouveau code IA), `AccountingProposal` (comptabilisation assistée, niveaux 0-3), boucle
WhatsApp de résolution, Closing Readiness. Détail : [`comptable/docs/P0_RAPPORT_FINAL.md`](comptable/docs/P0_RAPPORT_FINAL.md).

## 10. P1 — Cabinet Operating System + Financial Intelligence

Cabinet Cockpit (vue portefeuille), Work Queue unifiée, Copilot Evidence-First (comptable et
cabinet), Financial Snapshot, Cash Position/Forecast, Collection Risk, Compliance Center,
Cabinet Analytics. Détail : [`comptable/docs/P1_RAPPORT_FINAL.md`](comptable/docs/P1_RAPPORT_FINAL.md).

## 11. P1.5 — Unified Accounting Workspace (hardening + câblage UI)

Écrans Angular Trésorerie/Recouvrement/Compliance (backend déjà là depuis P1, jamais appelé
depuis le frontend avant P1.5), Evidence Panel (accepter/rejeter une proposition, assigner un
Finding), Copilot Dirigeant, premiers tests HTTP réels (`P1_5SecuriteHttpE2ETest`), correctifs
de sécurité (fuite tenant). Détail : [`comptable/docs/P1_5_RAPPORT_FINAL.md`](comptable/docs/P1_5_RAPPORT_FINAL.md).

## 11bis. P1.9 — Validation Terrain + Product Telemetry + Documentation

Chantier en cours au moment de la rédaction : mesurer si le produit crée réellement de la valeur
(Human Touch Rate, Automation Rate, Matching Quality, Evidence Resolution, Finding Resolution
Time, Copilot Usage/Quality, Work Queue), pas seulement vérifier qu'il compile. Tableau de bord
Qualité (`/cabinet/indicateurs`), feedback Copilot (👍/👎), motif de rejet sur les propositions
IA, télémétrie IA (latence/succès côté `ai-service`, sans jamais stocker prompt ou document),
protocole de validation terrain sur 8 profils de cabinet. Détail :
[`comptable/docs/P1_9_AUDIT.md`](comptable/docs/P1_9_AUDIT.md),
[`comptable/docs/P1_9_PILOT_PROTOCOL.md`](comptable/docs/P1_9_PILOT_PROTOCOL.md).

## 12. Flux métier

- **Document** : upload (web ou WhatsApp) → extraction VLM (`ai-service`) → contrôle de fraude
  (déterministe + visuel optionnel) → validation humaine si score `CRITIQUE` → proposition de
  comptabilisation (0-3, auto-appliquée seulement si niveau 3).
- **Banque** : import de relevé → rapprochement déterministe (montant exact) → rapprochement
  sémantique/Universal Matching Engine (filet IA, si activé) → revue humaine si
  `MATCH_REVIEW`/`AMBIGUOUS_MATCH` → pointage.
- **Pièce manquante** : ligne non résolue ou signal de contrôle → `Finding` → `DemandePiece` →
  relance WhatsApp (si activée) → réponse du client → document → nouveau rapprochement ciblé.
- **Clôture** : `ClotureReadinessService` agrège les `Finding` bloquants + statuts (écritures
  brouillon, factures non validées, déclarations en retard) → `READY` / `READY_WITH_REVIEW` /
  `NOT_READY` — jamais de clôture automatique déclenchée par le système.
- **Cabinet** : Cabinet Cockpit (portefeuille) → Work Queue (Finding + DemandePiece triés par
  sévérité) → assignation à un collaborateur → résolution.

## 13. Sécurité

JWT (`security.jwt.secret`, expiration configurable), rôles `EXPERT_COMPTABLE` /
`COLLABORATEUR` / `CLIENT`, permissions déclarées par endpoint dans `SecurityConfig`. Deux
secrets API distincts pour la communication Java ↔ Python (rotation indépendante, voir
`ai-service/README.md`). Premier test HTTP réel du projet (`P1_5SecuriteHttpE2ETest`, MockMvc +
vraie chaîne `SecurityFilterChain`) vérifie l'isolation tenant, les rejets 403/404 et les accès
autorisés avec de vrais jetons JWT.

## 14. Multi-tenant

`Tenant` (le cabinet) → `Utilisateur` (rattaché à un tenant, un rôle) → `Entreprise` (client du
cabinet, rattachée à un tenant). Toute requête est filtrée par `tenantId` au niveau service —
l'isolation est vérifiée par test (accès cross-tenant → 404, jamais 403, pour ne pas confirmer
l'existence d'une ressource à un tenant qui n'y a pas droit).

## 15. Configuration

Toutes les valeurs sensibles/variables sont externalisées en variables d'environnement dans
`comptable/src/main/resources/application.properties` (valeurs par défaut sûres — flags IA à
`false`, secrets vides tant que non configurés). Voir section 17 pour le détail des flags.

## 16. Installation

Prérequis : JDK 26, Node.js + npm (pour Angular 18), Python 3.12+ avec `uv`, Docker (pour
PostgreSQL en développement).

```bash
git clone <repo>
cd saas-comptable/comptable
./mvnw dependency:resolve          # backend
cd frontend && npm install         # frontend
cd ../../ai-service && pip install uv && uv sync   # ai-service
```

## 17. Lancement local

```bash
# 1. Backend (démarre automatiquement PostgreSQL via compose.yaml)
cd comptable && ./mvnw spring-boot:run       # http://localhost:8080

# 2. Frontend
cd comptable/frontend && npm start           # http://localhost:4200

# 3. ai-service (optionnel — nécessaire pour extraction/fraude (Groq, clé requise) et pour
#    matching/copilot en mode réel (Anthropic) ; AI_PROVIDER=mock permet de faire tourner
#    matching/copilot sans clé Anthropic, mais extraction/fraude nécessitent toujours GROQ_API_KEY)
cd ai-service && cp .env.example .env && uv run uvicorn app.main:app --reload --port 8000
```

Sans `ai-service` démarré, le Core reste fonctionnel pour tout ce qui ne dépend pas de l'IA
(comptabilité, dashboard, dossiers) ; les appels sortants dégradent proprement (`AI_UNAVAILABLE`).

## 18. Tests

```bash
cd comptable && ./mvnw test          # tests Java (310 tests au 2026-08-13, voir docs/P1_9_RAPPORT_FINAL.md)
cd ai-service && uv run pytest       # tests Python (74 tests)
cd comptable/frontend && ng build --configuration=production   # build strict Angular (pas de suite de tests unitaires Angular à ce jour)
```

Dépendances honnêtes : les tests Java `@SpringBootTest` nécessitent une vraie connexion
PostgreSQL (Docker via `spring-boot-docker-compose` — attention, `spring.docker.compose.skip.in-tests`
vaut `true` par défaut dans cette version de Spring Boot, chaque test qui a besoin d'une base
réelle doit l'overrider explicitement, voir `docs/P1_9_AUDIT.md`). Les tests `ai-service`
tournent entièrement en mode déterministe (`MockLLMProvider`, jamais d'appel réseau réel).

## 19. Docker

`comptable/compose.yaml` : PostgreSQL uniquement, piloté automatiquement par
`spring-boot-docker-compose` au démarrage du backend. Le bloc `ai-service` est présent mais
commenté (à décommenter pour l'inclure dans le même `docker compose up`, ou à lancer séparément
depuis `ai-service/` — voir les commentaires du fichier pour la configuration exacte des deux
clés API).

## 20. API

Vue d'ensemble par domaine dans `DOCUMENTATION.md` (chaque module y liste ses endpoints). Points
d'entrée principaux : `/api/entreprises/{id}/...` (comptabilité, documents, rapprochement,
trésorerie, conformité), `/api/cabinet/...` (cockpit, work queue, analytics, KPI qualité),
`/api/auth/...` (authentification), `/api/copilot/queries/{id}/feedback` (P1.9).

## 21. WhatsApp

Point d'entrée unique côté `ai-service` (`/api/v1/whatsapp/webhook`, authentifié par token de
vérification + signature HMAC Meta) — le contrôleur Java équivalent a été supprimé, pas de
chemin parallèle. Nécessite un template approuvé par Meta Business Manager pour les relances
sortantes (`relance.template-name`), désactivé par défaut (`relance.enabled=false`).

## 22. IA / Providers

- **Groq** (`groq_client.call_structured`) : extraction VLM et analyse de fraude visuelle
  (`extraction/document`, `fraude/analyse-visuelle`) — appel structuré unique (function calling
  forcé), pas d'abstraction `LLMProvider`, **pas de mode mock** : `GROQ_API_KEY` est requise dès
  qu'une pièce contenant une image doit être traitée. Modèle par défaut : `qwen/qwen3.6-27b`.
  **Vérifié avec une vraie clé** sur une facture réelle (voir section 27).
- **Universal Matching Engine et Copilot** (via l'abstraction `LLMProvider`,
  `AI_PROVIDER` côté `ai-service`) : trois valeurs possibles.
  - `mock` (défaut) : aucune clé requise, réponses scriptées/génériques
    (`MockLLMProvider`) — utile en dev/CI sans dépendre d'un fournisseur externe.
  - `groq` : `GroqLLMProvider` délègue au même client Groq que l'extraction/la fraude (une seule
    clé pour les quatre agents). **Vérifié avec une vraie clé** : le Copilot produit une
    explication réelle et grounded sur les faits fournis (plus le texte générique du mock).
  - `anthropic` : `AnthropicLLMProvider`, nécessite `ANTHROPIC_API_KEY` — **jamais testé avec une
    vraie clé dans ce projet à ce jour** (contrainte budgétaire, voir `docs/P1_9_AUDIT.md`).

L'Agent Comptable Autonome (`agent/analyser-piece`, expérimental, isolé) utilise une boucle
multi-tours distincte, toujours sur Anthropic (SDK appelé directement, hors abstraction
`LLMProvider`) — non concerné par le choix `AI_PROVIDER`.

## 23. Feature flags (`comptable/src/main/resources/application.properties`)

| Flag | Défaut | Effet |
|---|---|---|
| `agent.vlm.enabled` | `false` | Extraction par IA (sinon heuristique locale) |
| `ai-service.base-url` / `ai-service.outbound-api-key` | `http://localhost:8000` / vide | Connexion sortante vers `ai-service` |
| `relance.enabled` | `false` | Relance WhatsApp automatique des pièces en retard |
| `agent.rapprochement-semantique.enabled` | `false` | Filet IA de rapprochement Mobile Money |
| `agent.matching-ia.enabled` | `false` | Universal Matching Engine (filet multi-source) |
| `agent.accounting-proposal.enabled` | `false` | Proposition de comptabilisation automatique |
| `agent.fraude.analyse-visuelle.enabled` | `false` | Analyse visuelle VLM (les contrôles déterministes restent toujours actifs) |

Toutes désactivées par défaut par sécurité — le comportement déterministe existant n'est jamais
changé silencieusement par une mise à jour du code.

## 24. Base de données

PostgreSQL, schéma géré exclusivement par Flyway (`spring.jpa.hibernate.ddl-auto=validate` —
Hibernate ne modifie jamais le schéma, seules les migrations le font). Modèle multi-tenant
(`tenant` → `utilisateur`/`entreprise`), tables métier par domaine (voir `DOCUMENTATION.md`
section "Modèle de Données" pour le détail table par table).

## 25. Migrations

`comptable/src/main/resources/db/migration/`, séquentielles (`V1__...sql` à `V29__...sql` au
2026-08-13). Jamais de modification d'une migration déjà appliquée en développement partagé —
toute correction passe par une nouvelle migration.

## 26. FEC

`ExportFecService` (`GET /api/entreprises/{id}/rapports/fec/{exerciceId}`) génère un fichier FEC
18 colonnes sur les écritures `VALIDEE` uniquement. **Le format suit la structure FEC française
standard, mais ce n'est pas une affirmation de conformité réglementaire générale** — périmètre à
valider avec un expert-comptable local avant tout usage réel (pas de multi-devise, numérotation
recalculée à l'export faute de séquence persistée par journal). Détail : `DOCUMENTATION.md`
module 3️⃣.

## 27. Limitations

- **Universal Matching Engine et Agent Comptable Autonome** : jamais testés avec une vraie clé
  Anthropic (contrainte budgétaire) — fonctionnels et testés uniquement en `AI_PROVIDER=mock`.
- **Double représentation "facture fournisseur"** (`Document.FACTURE_ACHAT` vs
  `facture_fournisseur`) : incohérence connue, refonte non faite (trop lourde pour les chantiers
  passés) — candidate P2, voir `docs/P2_DECISION_MATRIX.md`.
- **Time to Closing et Cash Forecast Quality non instrumentés** : pas d'horodatage de clôture
  persisté, pas d'historique de prévisions suffisant pour mesurer une précision honnête.
- **False Auto-Match Rate non mesurable** : le modèle de données actuel ne permet pas de corriger
  une proposition déjà auto-appliquée, donc pas de suivi d'erreur a posteriori possible.
- **FEC** : périmètre réglementaire non validé par un expert-comptable local (voir section 26).
- **Aucun test Angular unitaire** : le frontend est vérifié par build strict
  (`ng build --configuration=production`, template checking activé) et vérification manuelle
  contre le backend réel — pas de `karma`/`jest` dans ce projet à ce jour.
- **Extraction sur Groq (`qwen/qwen3.6-27b`)** : vérifiée bout en bout via le vrai endpoint
  `POST /api/entreprises/{id}/documents` (pas seulement en appelant `ai-service` directement) sur
  une vraie facture (`comptable/data/documents/.../fact_onea.pdf`, facture d'eau O.N.E.A.) —
  `statut=TRAITE`, tous les champs extraits (fournisseur, HT/TVA/TTC, date, score de fraude)
  corrects. Deux bugs réels trouvés et corrigés pendant cette vérification :
  1. **Côté `ai-service`** : `qwen/qwen3.6-27b` consomme un nombre variable de jetons de
     réflexion interne (~750 à ~1070 observés) avant d'émettre le tool call — `temperature=1` +
     `max_completion_tokens=1024` (repris du script fourni) tronquait la réponse presque
     systématiquement. Corrigé : `temperature=0` + `max_completion_tokens=3000`. **`temperature=0`
     ne garantit pas un résultat identique à chaque appel** sur cette API — deux appels
     strictement identiques ont encore échoué de façon identique à budget de 2048, avant qu'un
     budget de 3000 ne stabilise le résultat en test réel ; à surveiller, pas une garantie
     formelle.
  2. **Côté Java, dans `AiServiceClient`, sans lien avec Groq** : le `HttpClient` JDK tentait
     HTTP/2 par défaut, qu'`uvicorn` (HTTP/1.1 seul) ne gère pas proprement pour une requête
     multipart — la requête arrivait corrompue (`422` côté `ai-service`, masqué en `400`
     générique côté Java). Jamais détecté avant car `agent.vlm.enabled` est désactivé par défaut
     et n'avait jamais tourné en conditions réelles. Corrigé par
     `.version(HttpClient.Version.HTTP_1_1)` dans le constructeur d'`AiServiceClient`.
  **Aucun test automatisé** n'existe encore pour `extraction_service.py`/`fraude_service.py` ni
  pour `AiServiceClient` (vrai avant comme après ces correctifs) — la vérification ci-dessus est
  manuelle, à rejouer si le modèle, le prompt, ou la version de Spring/JDK change. Le compte Groq
  utilisé a une limite de 8000 jetons/minute (palier gratuit/on-demand) : une image encodée en
  base64 consomme une part significative de ce budget à elle seule — à surveiller en cas de
  volume réel (plusieurs pièces par minute).

## 28. Roadmap

- **P0** ✅ — Core Intelligent (Dossier, Findings, Matching, Accounting Proposal, Closing).
- **P1** ✅ — Cabinet Operating System + Financial Intelligence.
- **P1.5** ✅ — Unified Accounting Workspace (hardening + câblage UI).
- **P1.9** 🔄 En cours — Validation Terrain + Product Telemetry + Documentation.
- **P2** À décider après le pilote terrain, sur la base d'un usage réel mesuré (pas
  pré-décidé) — voir [`comptable/docs/P2_DECISION_MATRIX.md`](comptable/docs/P2_DECISION_MATRIX.md).

---

**Dernière mise à jour :** 2026-08-13 (mission P1.9)
