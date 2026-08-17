# ai-service

Microservice FastAPI regroupant toute la logique IA du SaaS comptable : extraction VLM
(factures, reçus Mobile Money Wave/Orange Money/MTN Money/Moov Money), ingestion WhatsApp,
analyse visuelle de fraude, rapprochement sémantique. Extrait de l'ancien monolithe Spring
Boot (`../comptable`, package `agent/`) pour isoler l'itération rapide sur les prompts/modèles
du cœur métier (abonnements, comptabilité, base de données), qui reste sous Spring Boot.

## Frontière de responsabilité

- **Ce service** : intégration Groq (extraction VLM, fraude visuelle) et Anthropic (rapprochement
  sémantique / Universal Matching Engine, Copilot), intégration Meta WhatsApp Cloud API (webhook,
  téléchargement média, envoi de templates). Aucune base de données, aucun état persistant.
- **Spring Boot (`../comptable`)** : toute la persistance (documents, écritures, tenants),
  résolution téléphone → entreprise, idempotence des messages WhatsApp, règles métier
  déterministes (contrôles de fraude non-visuels, guard-rails de validation comptable).
  N'appelle plus Anthropic/Groq/Ollama directement : `VlmOcrExtractionService`,
  `MatchingSemantiqueService` et la partie visuelle d'`AnalyseFraudeService` ont été retirées du
  monolithe et remplacées par des appels HTTP vers ce microservice, via un client centralisé
  (`agent/client/AiServiceClient.java`).

La communication est bidirectionnelle, par API REST, chaque sens portant sa propre clé partagée
(rotation indépendante) :

- **Python → Java** (`POST /api/v1/internal/whatsapp/documents`, une fois une pièce WhatsApp
  extraite et analysée) : header `X-Internal-Api-Key` = `CORE_SERVICE_API_KEY` ici =
  `ai-service.api-key` côté Spring Boot.
- **Java → Python** (extraction, fraude visuelle, rapprochement, envoi de templates) : header
  `X-Internal-Api-Key` = `INTERNAL_API_KEY` ici = `ai-service.outbound-api-key` côté Spring Boot.

> Les endpoints `/api/v1/whatsapp/webhook` ne portent pas cette clé : ils sont appelés
> directement par Meta et authentifiés par le token de vérification (GET) et la signature
> HMAC `X-Hub-Signature-256` (POST). C'est désormais le **seul** point d'entrée WhatsApp : le
> controleur webhook Java correspondant a été supprimé, il n'existe plus de chemin parallèle.

## Démarrage

```bash
pip install uv   # si pas deja installe
cd ai-service
uv sync
cp .env.example .env   # puis renseigner GROQ_API_KEY, ANTHROPIC_API_KEY, WHATSAPP_*, INTERNAL_API_KEY
# AI_PROVIDER=mock par defaut : le Universal Matching Engine (et le Copilot) tournent sans
# ANTHROPIC_API_KEY (cf. section "Provider LLM du Universal Matching Engine" plus bas).
# extraction/document et fraude/analyse-visuelle appellent toujours Groq directement (aucun mode
# mock a ce niveau) et necessitent GROQ_API_KEY.
uv run uvicorn app.main:app --reload --port 8000
```

Docs interactives : http://localhost:8000/docs

## Tests

```bash
uv run pytest
```

## Endpoints

| Méthode | Route | Auth | Rôle |
|---|---|---|---|
| GET | `/api/v1/health` | - | Liveness |
| POST | `/api/v1/extraction/document` | API key | OCR/VLM sur une image ou un PDF de pièce (`OcrResult`) |
| POST | `/api/v1/fraude/analyse-visuelle` | API key | Détection d'indices de falsification (`list[AlerteFraude]`) |
| POST | `/api/v1/rapprochement/semantique` | API key | Appariement relevé ↔ écritures (`list[AppariementSemantique]`) — wrapper historique, cf. ci-dessous |
| POST | `/api/v1/matching/analyze` | API key | **Universal Matching Engine** : rapprochement multi-source générique (`MatchingResponse`) |
| GET/POST | `/api/v1/whatsapp/webhook` | Meta (verify token / HMAC) | Réception WhatsApp |
| POST | `/api/v1/whatsapp/send-template` | API key | Envoi de template (CRM de relance) |
| POST | `/api/v1/agent/analyser-piece` | API key | Agent Comptable Autonome : vraie boucle tool-use (`AnalyseAgentResponse`) |

## Universal Matching Engine (`/api/v1/matching/analyze`)

Rapprochement multi-source générique : contrairement à `rapprochement/semantique` (banque ↔
écritures uniquement), cet endpoint compare une source (transaction bancaire, Mobile Money,
écriture, facture, document — `SourceType`) à des candidats de **n'importe laquelle** de ces
mêmes catégories (écritures, factures, documents, tiers, paiements, ou une autre transaction
pour détecter un virement interne).

**Pipeline strictement séquentiel, une seule passe LLM au maximum** (jamais de boucle
d'outils/ReAct ici — voir `agent_orchestrator.py` pour ça, réservé aux cas complexes) :

```
normalize (matching_normalization.py)
   -> filter (matching_prefilter.py : fenêtre de dates, devise, dédup)
   -> score (matching_scoring.py : montant/date/tiers/référence/statut, pondéré)
   -> court-circuit déterministe si évident (matching_engine._detecter_match_evident)
   -> LLM structuré, 1 appel (matching_prompts.py + matching_engine._appeler_llm)
   -> validation stricte (candidateId/candidateType doivent exister dans la requête,
      sinon le résultat ENTIER est rejeté — jamais un tri sélectif)
   -> MatchingResponse
```

- **Fichiers** : `app/schemas/matching.py` (contrat), `app/services/matching_normalization.py`,
  `matching_prefilter.py`, `matching_scoring.py`, `matching_prompts.py` (prompt versionné,
  `PROMPT_VERSION = "matching-v1"`), `matching_engine.py` (orchestration),
  `app/api/v1/endpoints/matching.py`.
- **Décisions** : `MATCH_PROPOSED` / `PARTIAL_MATCH` / `AMBIGUOUS_MATCH` / `NO_MATCH` (résultat
  valide, jamais forcé). **Types** : `ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_ONE`,
  `PARTIAL_PAYMENT`, `PAYMENT_WITH_FEES`, `ADVANCE_PAYMENT`, `INTERNAL_TRANSFER`, `NONE`.
- **Seuils** (`app/core/config.py`) : `matching_auto_match_threshold=0.95`,
  `matching_review_threshold=0.70` (en dessous, rétrogradé en `NO_MATCH` — jamais proposé
  automatiquement), `matching_max_candidates_llm=8`, `matching_fenetre_jours=30`,
  `matching_deterministic_skip_score=0.97` + `matching_deterministic_skip_gap=0.20` (court-circuit).
- **Garde-fou identifiants** (mission section 16) : si le modèle référence un `candidateId` ou
  `candidateType` absent des candidats envoyés, **toute la réponse** est rejetée
  (`AMBIGUOUS_MATCH`, `reasons=["invalid_ai_output"]`, `requiresHumanReview=true`) — pas
  seulement l'entrée fautive, par principe de prudence comptable.
- **`/api/v1/rapprochement/semantique` n'a pas été supprimé** : `rapprochement_service.py`
  convertit désormais chaque ligne de relevé + ses candidats compatibles (direction
  débit/crédit) vers le contrat générique, délègue à `matching_engine.analyze`, reconvertit la
  réponse. Comportement externe inchangé (seuil, dédup par priorité de confiance) ; voir
  `tests/test_rapprochement_service.py`.
- **Limitation connue** : `MANY_TO_ONE` (plusieurs sources vers un seul candidat) ne peut pas
  être pleinement détecté avec le contrat actuel (une seule `source` par appel) — l'enum existe
  pour la complétude du schéma et le LLM peut l'utiliser si `context.history` le suggère, mais
  ce n'est pas une capacité activement recherchée par le moteur. Voir le rapport de mission pour
  le détail complet.

## Provider LLM du Universal Matching Engine (`AI_PROVIDER`)

Le moteur ne connaît que l'interface `LLMProvider` (`app/services/llm_provider.py`) — il ne sait
jamais s'il parle à Anthropic ou à un mock. **Seul le Universal Matching Engine (et le Copilot)
passe par cette abstraction** ; `extraction_service.py` et `fraude_service.py` appellent
directement `groq_client.call_structured` (Groq, pas Anthropic — voir section suivante), sans
passer par `LLMProvider` ni par un mode mock.

```
matching_engine.analyze()
      -> LLMProvider (injecté, ou choisi par défaut via _get_default_provider())
            -> AnthropicLLMProvider   (AI_PROVIDER=anthropic, nécessite ANTHROPIC_API_KEY)
            -> MockLLMProvider        (AI_PROVIDER=mock — par défaut, aucune clé requise)
```

- **`AI_PROVIDER=mock`** (valeur par défaut, `app/core/config.py::Settings.ai_provider`) : aucun
  appel réseau, aucune clé Anthropic nécessaire. `app/services/mock_llm_provider.py` reconnaît un
  mot-clé optionnel dans le libellé/référence de la source pour choisir un scénario précis (utile
  en test manuel via `/docs`, un peu comme les numéros de carte de test de Stripe) :

  | Mot-clé (dans `label`/`reference` de la source) | Décision retournée |
  |---|---|
  | *(aucun)* | `MATCH_PROPOSED` sur le premier candidat trouvé dans le prompt, confiance 0.97 |
  | `MOCK_AMBIGUOUS` | `AMBIGUOUS_MATCH` avec les 2 premiers candidats |
  | `MOCK_NO_MATCH` | `NO_MATCH` |
  | `MOCK_PARTIAL` | `PARTIAL_MATCH` sur le premier candidat |
  | `MOCK_INVALID_JSON` | lève `ValueError` (simule une réponse non structurée) |
  | `MOCK_HALLUCINATE` | renvoie un `candidateId` absent des candidats envoyés (teste le garde-fou anti-hallucination) |
  | `MOCK_TIMEOUT` | lève `TimeoutError` (simule une panne du provider) |

  Les tests unitaires n'utilisent généralement pas ces mots-clés : ils injectent directement
  `MockLLMProvider(responses=[...])` (une file de réponses/exceptions scriptées), plus précis et
  explicite qu'un monkeypatch. Voir `tests/test_mock_llm_provider.py` pour les deux usages.
- **`AI_PROVIDER=anthropic`** : `AnthropicLLMProvider` délègue tel quel à
  `anthropic_client.call_structured` (inchangé). Nécessite `ANTHROPIC_API_KEY`. **Non testé avec
  une vraie clé dans ce projet à ce jour** (aucun accès API payant disponible) — voir le rapport
  de mission "finaliser le matching IA sans API Anthropic disponible" pour le détail exact de ce
  qui reste à vérifier le jour où une clé est disponible (rien côté code : uniquement une
  vérification manuelle du premier appel réel).
- **`AI_PROVIDER=groq`** : `GroqLLMProvider` (`app/services/groq_llm_provider.py`) délègue à
  `groq_client.call_structured` — même modèle/mêmes réglages (`temperature=0`,
  `max_completion_tokens=3000`) que l'extraction/la fraude. **Vérifié avec une vraie clé** : le
  Copilot (`POST /api/v1/copilot/explain`) a produit une explication réelle et correcte à partir
  des faits fournis (pas le texte générique du mock), en conditions réelles via le vrai endpoint
  Java `/api/entreprises/{id}/copilot/question`. Contrairement à `anthropic`, cette option
  n'ajoute aucune nouvelle clé à gérer si `GROQ_API_KEY` est déjà configurée pour
  extraction/fraude — un seul secret pour les quatre agents.
- **Aucun garde-fou n'est contourné par le mock ni par un provider en particulier** :
  `matching_engine._valider_et_construire` (rejet des `candidateId`/`candidateType` halluciné,
  seuils de confiance) s'applique identiquement quel que soit le provider — le garde-fou vit dans
  le moteur, pas dans le provider.

## Provider vision de l'extraction et de la fraude (`groq_client.py`)

**Changement de provider** : `extraction/document` et `fraude/analyse-visuelle` appelaient
Anthropic directement jusqu'à ce chantier ; ils appellent désormais **Groq**
(`app/services/groq_client.py::call_structured`), avec exactement la même signature que
`anthropic_client.call_structured` (system prompt, texte, tool forcé, schéma, image optionnelle
en base64) — seul l'import a changé dans `extraction_service.py`/`fraude_service.py`, aucune
logique métier (prompts, garde-fous, taxonomie d'anomalies) n'a été touchée.

- **Function calling forcé** (`tool_choice={"type": "function", "function": {"name": tool_name}}`,
  format OpenAI-compatible exposé par l'API Groq) pour obtenir une réponse structurée fiable —
  même philosophie que le tool-use forcé côté Anthropic, jamais de parsing de texte libre.
- **Image transmise en data URL** (`data:{media_type};base64,{...}`, format vision
  OpenAI-compatible) plutôt qu'en bloc `image` façon Anthropic — c'est le seul point de
  divergence structurel entre les deux clients, invisible pour les appelants.
- **`GROQ_API_KEY`/`GROQ_MODEL`** (`.env`, `app/core/config.py`) : aucun mode mock à ce niveau
  (contrairement au Universal Matching Engine) — une clé Groq réelle est nécessaire dès qu'une
  pièce contenant une image doit être extraite ou analysée. **Ne jamais committer de clé réelle**
  (ni dans `.env`, ni en dur dans le code) — `GROQ_API_KEY` doit rester une variable
  d'environnement.
- **`GROQ_MODEL` par défaut : `qwen/qwen3.6-27b`** — **vérifié avec une vraie clé sur une vraie
  facture** (`comptable/data/documents/.../fac.png`, facture d'eau O.N.E.A.) : les 8 champs
  extraits (type de document, fournisseur, HT/TVA/TTC, date, NIF/RCCM, référence) correspondent
  exactement à la pièce réelle, reproduit sur 3 appels indépendants via `extraction_service.extraire`.
- **`temperature=0` et `max_completion_tokens=3000`, pas les valeurs par défaut naïves (`1` et
  `1024`)** — bug réel trouvé pendant la vérification ci-dessus : `qwen/qwen3.6-27b` est un
  modèle de raisonnement qui consomme des jetons de réflexion interne
  (`usage.completion_tokens_details.reasoning_tokens`, entre ~750 et ~1070 observés selon
  l'appel) avant d'émettre le tool call. Avec `temperature=1` et un budget de 1024 jetons,
  l'appel échouait quasi systématiquement (`400 tool_use_failed`, JSON tronqué en plein milieu)
  alors même que le modèle avait lu les bonnes valeurs (visible dans `failed_generation`) — le
  problème était le format de sortie, pas la lecture de l'image. **`temperature=0` ne rend pas
  Groq parfaitement déterministe pour autant** : à budget de 2048 jetons, deux appels
  strictement identiques (même fichier, même code) ont encore échoué avec une troncature
  identique au même endroit, alors qu'un troisième appel avec les mêmes réglages avait réussi
  quelques minutes plus tôt — un budget de 3000 a résolu la reproduction observée, mais cette
  marge reste empirique, pas une garantie formelle. Voir `app/services/groq_client.py` pour le
  détail.
- **Bug Java découvert en même temps, dans `AiServiceClient`, pas dans ce microservice** :
  le `java.net.http.HttpClient` utilisé par `AiServiceClient` tentait HTTP/2 par défaut
  (upgrade `h2c`), qu'`uvicorn`/`h11` (HTTP/1.1 uniquement) ne gère pas proprement pour une
  requête multipart — la requête arrivait corrompue et `ai-service` répondait `422
  Unprocessable Entity` avant même d'atteindre `extraire_document`. Jamais détecté avant
  puisque `agent.vlm.enabled` est désactivé par défaut et n'avait jamais été testé en conditions
  réelles jusqu'à cette vérification. Corrigé par `.version(HttpClient.Version.HTTP_1_1)` dans
  `AiServiceClient` (voir `comptable/src/main/java/.../agent/client/AiServiceClient.java`).
- **Limite de débit** : le compte utilisé pour cette vérification a un plafond de 8000
  jetons/minute (palier gratuit/on-demand Groq) — une seule image encodée en base64 consomme une
  part significative de ce budget. À surveiller si le volume de pièces traitées par minute
  augmente (upgrade de palier ou traitement par lots à espacer).
- **Télémétrie** : `groq_client._tracer_appel_ia` suit exactement le même contrat que
  `anthropic_client._tracer_appel_ia` (mission P1.9) — `ai_call provider=groq operation=...
  success=... latency_ms=...`, jamais le prompt ni le document.
- **Anthropic reste utilisé** pour le Universal Matching Engine et le Copilot
  (`AI_PROVIDER=anthropic`) — ce changement ne concerne que les deux agents qui traitent des
  images (extraction, fraude visuelle).

## Agent Comptable Autonome (`/api/v1/agent/analyser-piece`)

Contrairement aux autres endpoints (un seul appel structuré), celui-ci implémente une **vraie
boucle multi-tours** (`app/services/agent_orchestrator.py`) où le modèle décide lui-même, tour
après tour, s'il doit appeler un outil avant de statuer — pas un simple JSON forcé en un appel.

- **4 outils** exposés au modèle : trois outils d'information/action qui appellent Spring Boot
  (`verifier_doublon_montant`, `consulter_historique_fournisseur`, `demander_clarification_client`
  — backend Java dans `agent/tools/`, nouveaux endpoints `POST /api/v1/internal/agent-tools/*`),
  plus `repondre_final` dont le schéma d'entrée EST le contrat de sortie
  (`AnalyseAgentResponse`) : c'est la même technique que `call_structured` (forcer une réponse
  typée via un tool) appliquée à la fin d'une boucle au lieu d'un appel unique.
- **Nécessite `entreprise_id`** (form field), contrairement à `extraction/document` et
  `fraude/analyse-visuelle` qui sont stateless : les outils interrogent la base de Spring Boot
  pour CETTE entreprise.
- **Boucle bornée** (`MAX_ITERATIONS=6`) et **jamais bloquante** : un outil qui échoue renvoie une
  erreur au modèle plutôt que de planter la boucle (il adapte son raisonnement, ex. baisse sa
  confiance) ; si la boucle n'atteint jamais `repondre_final`, ou si sa réponse est malformée,
  dégrade vers `ECHEC_SAISIE_MANUELLE` avec `necessiteValidationHumaine=true` plutôt que de
  propager une exception. Couvert par `tests/test_agent_orchestrator.py` (boucle nominale, relance
  sur texte libre, non-convergence, réponse malformée, outil défaillant, type de fichier rejeté).
- **`consulter_historique_fournisseur` est une capacité entièrement nouvelle** côté Java : le
  fournisseur n'étant pas une colonne dédiée (stocké dans le JSON libre `Document.donneesOcr`),
  la requête est native Postgres (`donnees_ocr ->> 'fournisseur' ILIKE ...`,
  `DocumentRepository.rechercherParFournisseur`). `verifier_doublon_montant` réutilise la requête
  déjà existante d'`AnalyseFraudeService.verifierDoublon`. `demander_clarification_client`
  réutilise le module `DemandePiece` existant via une nouvelle variante
  `DemandePieceService.creerDepuisAgent` (sans `UtilisateurPrincipal`, même principe que
  l'ingestion WhatsApp).
- **Endpoint autonome, pas branché sur les flux existants** : `DocumentService.uploader`/
  `ingererAvecExtractionExterne` continuent d'utiliser `extraction/document` +
  `fraude/analyse-visuelle` séparément, inchangés. Câbler cet agent à la place (ou en plus) est
  une décision produit distincte, pas prise ici.

## Raisonnement en 4 phases (typage → extraction → tribunal interne → routage)

> Section précédente, toujours valable pour `extraction/document` et `fraude/analyse-visuelle` :
> ces deux endpoints restent des appels structurés uniques (pas de boucle d'outils). L'agent
> décrit ci-dessus au-dessus est une capacité séparée et plus lourde, à réserver aux cas qui
> justifient le coût/latence d'une vraie boucle de raisonnement.

Les prompts de `extraction_service.py` et `fraude_service.py` sont structurés autour de 4 phases
de raisonnement agentique, mais **réparties entre les deux agents existants plutôt que
consolidées dans un nouvel endpoint** :

1. **Typage** (`extraction_service.py`) — le modèle classe chaque pièce en
   `FACTURE_NORMALISEE` / `RECIPISSE_MOBILE_MONEY` / `TICKET_CAISSE` / `FACTURE_PROFORMA` /
   `INEXPLOITABLE` (champ `typeDocument` sur `OcrResult`) *avant* d'extraire les montants — une
   facture proforma est toujours typée comme telle, même parfaitement lisible, pour que Spring
   Boot puisse la traiter comme non comptabilisable en l'état.
2. **Extraction** (`extraction_service.py`) — en plus des montants/date/fournisseur déjà
   présents, extrait désormais `nifRccm` et `referenceTransaction`.
3. **Tribunal interne** — scindé en deux, selon qui peut faire le contrôle de façon fiable :
   - *Mathématique et temporel* : le prompt demande au modèle de s'auto-corriger (HT+TVA=TTC à
     ±5 FCFA, date pas dans le futur ni antérieure à 2 ans), **doublé d'un garde-fou déterministe
     Python** (`_verifier_coherence_arithmetique`) qui ne fait jamais confiance au seul jugement
     du modèle pour l'arithmétique : il recalcule lui-même et dégrade `confiance` en cas d'écart,
     sans jamais modifier les montants lus.
   - *Visuel* (`fraude_service.py`) — taxonomie élargie (`POLICE_NON_UNIFORME`, `LOGO_ALTERE`,
     `REFERENCE_ABSENTE`, `FORMAT_VISUEL_SUSPECT`) avec un heuristique explicite sur le mode de
     fraude Mobile Money le plus courant en Afrique de l'Ouest : une simple capture d'écran de
     l'application, sans référence de transaction visible, plutôt qu'un montage graphique.
4. **Routage final** (`SUCCES`/`SUCCES_AVEC_RESERVES`/`ECHEC_SAISIE_MANUELLE`) — **volontairement
   pas dupliqué côté Python**. L'équivalent existe déjà côté Spring Boot :
   `Document.statut` (`ERREUR`/`TRAITE`/`EN_ATTENTE_VALIDATION_HUMAINE`, cf.
   `DocumentService.appliquerExtraction(Externe)`) et `RapportFraude.scoreRisque`
   (`FAIBLE`/`MOYEN`/`CRITIQUE`, cf. `RapportFraude.depuisAlertes`) jouent ce rôle en combinant
   les deux agents. Créer un troisième statut en Python aurait introduit une deuxième source de
   vérité pour une décision déjà persistée par Java.

La logique métier de `extraction/document`, `fraude/analyse-visuelle` et
`rapprochement/semantique` (prompts, guard-rails anti-hallucination, seuils) est portée
verbatim depuis `VlmOcrExtractionService`, `AnalyseFraudeService.analyserVisuellement` et
`MatchingSemantiqueService` côté Java — voir les docstrings de
`app/services/{extraction,fraude,rapprochement}_service.py` pour la correspondance exacte.

`fraude/analyse-visuelle` ne retourne que les alertes issues de l'analyse visuelle
(`FORMAT_VISUEL_SUSPECT`, `FOURNISSEUR_NON_IDENTIFIABLE` — ce dernier seulement si
`type_document` ∈ {`FACTURE_ACHAT`, `RECU`}) ; les alertes déterministes (`ECART_TVA`,
`MONTANT_ROND_SUSPECT`, `DOUBLON_POTENTIEL`) nécessitent l'entreprise et la base de données et
restent calculées côté Spring Boot, qui assemble le `RapportFraude` final (score agrégé) à
partir des deux listes.

## Télémétrie IA (mission P1.9)

Pas de nouvelle dépendance (pas de Prometheus/OpenTelemetry/APM) — un wrapper léger autour de
chaque point d'entrée LLM du service, avec `time.perf_counter()` + `logging` structuré, pour
répondre à « quelle latence, quel taux de succès, quelles opérations tombent en fallback » sans
construire un pipeline d'observabilité dédié.

- **`groq_client.call_structured`** (`app/services/groq_client.py`) : couvre
  `extraction_service.py` et `fraude_service.py` (`provider=groq` dans les logs) — voir section
  dédiée plus haut.
- **`anthropic_client.call_structured`** (`app/services/anthropic_client.py`) : couvre
  `AnthropicLLMProvider` uniquement (matching et copilot quand `AI_PROVIDER=anthropic`,
  `provider=anthropic` dans les logs) — n'est plus appelé par extraction/fraude depuis leur
  migration vers Groq.
- **`MockLLMProvider.call_structured`** (`app/services/mock_llm_provider.py`) : même contrat,
  pour que la télémétrie reste visible en dev/CI/démo (`AI_PROVIDER=mock`), pas seulement en
  production avec une vraie clé.

Format de log (un seul niveau `INFO` en succès, `WARNING` en échec — jamais le prompt, le
document, ni le contenu de la réponse) :

```
ai_call provider=anthropic operation=proposer_rapprochement success=true latency_ms=842
ai_call provider=mock operation=expliquer_dossier success=false latency_ms=1 error=TimeoutError
```

`operation` reprend le `tool_name` de l'appel (`proposer_rapprochement`, `expliquer_dossier`,
ou le nom du tool d'extraction/fraude selon l'appelant) — un proxy honnête pour « quelle
capacité IA », pas une taxonomie métier séparée à maintenir. `error` ne contient que le nom de
la classe d'exception (`TimeoutError`, `ValueError`, `RuntimeError`...), jamais son message
complet ni la pile d'appel, pour éviter toute fuite accidentelle de contenu métier dans les logs
génériques.

**Non instrumenté dans ce chantier** : `agent_orchestrator.py` (boucle multi-tours de l'agent
autonome) — priorité plus basse, cet agent n'est câblé sur aucun flux d'ingestion existant (voir
section dédiée plus haut). Coût/tokens (`response.usage.input_tokens`/`output_tokens`, exposés
gratuitement par le SDK Anthropic mais jamais lus aujourd'hui) — instrumentable à coût quasi nul
si un besoin réel de suivi budgétaire apparaît, contrat documenté mais pas construit sans ce
besoin (voir `docs/P1_9_AUDIT.md` section F.5).

## Convention JSON : camelCase partout

Tous les schémas Pydantic exposés en entrée ou sortie d'API (`OcrResult`, `AlerteFraude`,
`AppariementSemantique`, `LigneReleve`, `LigneEcritureCandidate`, `SendTemplateRequest`,
`WhatsAppDocumentPayload`...) déclarent un alias camelCase par champ
(`model_config = {"populate_by_name": True}` + `Field(alias=...)`), pour matcher la convention
Jackson du reste du codebase Java (`montantHt`, `actionRecommandee`, `ligneReleveId`, ...).
FastAPI sérialise les réponses par alias par défaut (`response_model_by_alias=True`), donc
c'est transparent : le code Python interne continue de manipuler des noms snake_case, seul le
JSON sur le fil est camelCase.

## Payload unifié d'ingestion WhatsApp

`_traiter_message_image` (dans `app/api/v1/endpoints/whatsapp.py`) enchaîne extraction VLM
(`extraire`) et analyse de fraude visuelle (`analyser_visuellement`, avec
`type_document=RECU` — le seul type possible sur ce canal) sur le même fichier téléchargé, puis
envoie à Spring Boot un unique payload `WhatsAppDocumentPayload`
(`app/schemas/ingestion.py`), en multipart aux côtés du fichier original (champ `metadata`,
JSON ci-dessous) :

```json
{
  "clientPhone": "2250102030405",
  "messageId": "wamid.HBg...",
  "donnees": {
    "fournisseur": "Wave Money",
    "montantHt": "10000",
    "montantTva": "1800",
    "montantTtc": "11800",
    "devise": "XOF",
    "modePaiement": "WAVE",
    "datePiece": "2026-08-10",
    "confiance": 0.92
  },
  "alertesFraude": [
    {
      "code": "FORMAT_VISUEL_SUSPECT",
      "motif": "Logo pixelise",
      "gravite": "MOYEN",
      "actionRecommandee": "Verifier"
    }
  ]
}
```

`fournisseur`, `devise` et `mode_paiement` sont des champs additionnels à `OcrResult`
(absents du record Java d'origine, cf. `app/schemas/extraction.py`) : utiles spécifiquement
pour pré-remplir un reçu Mobile Money sans ressaisie. Les montants sérialisent en JSON comme
des chaînes (`"10000"`, pas `10000`) : c'est le comportement standard de Pydantic pour
`Decimal` en mode JSON, et Jackson désérialise nativement une chaîne numérique vers
`BigDecimal` sans configuration additionnelle.

Note de fiabilité : si `notifier_document_whatsapp` échoue (Spring Boot indisponible, timeout),
l'exception est actuellement seulement loguée (`_traiter_message_image`) — la pièce est perdue
sans retry, puisque Meta a déjà reçu son 200 OK et ne renverra pas le message. Un mécanisme de
retry ou de file d'attente (Redis, SQS...) est à envisager avant une mise en charge réelle si ce
risque de perte silencieuse n'est pas acceptable.

## Écarts assumés par rapport au code Java d'origine

- **Extraction VLM sur Groq, pas Ollama.** Le Java (Sprint 9) utilisait un modèle Ollama local
  (`llama3.2-vision`) spécifiquement pour l'extraction — gratuit, sans clé API, tourne sur la
  machine du cabinet. `VlmOcrExtractionService` (le client Ollama) a été **supprimé** du
  monolithe, pas seulement contourné. Ce microservice a d'abord fait appel à Anthropic pour les
  trois usages image/texte (extraction, fraude, rapprochement) afin de n'avoir qu'une seule
  intégration à maintenir ; extraction et fraude ont depuis basculé sur Groq (voir la section
  dédiée plus haut) tandis que le rapprochement (Universal Matching Engine, Copilot) reste sur
  Anthropic — deux intégrations distinctes à maintenir désormais, décision assumée pour ce
  changement de provider vision plutôt qu'un choix d'architecture initial. Si le coût par appel
  d'extraction (le plus fréquent des trois, un appel par pièce) redevient un problème, un client
  Ollama (API `/api/chat`, compatible OpenAI) peut être ajouté à `app/services/extraction_service.py`
  derrière le même contrat `OcrResult` — la dépendance `spring-ai-starter-model-ollama` a elle
  aussi été retirée du `pom.xml` de `comptable/`, il faudrait la restaurer côté Python uniquement
  si ce choix est fait.

## Côté Spring Boot

La migration couvre désormais les quatre points d'intégration IA du monolithe, pas seulement
l'ingestion WhatsApp :

| Ancien code Java (supprimé) | Nouveau code Java | Appelle ai-service via |
|---|---|---|
| `VlmOcrExtractionService` (Ollama) | `AiServiceOcrExtractionService` | `AiServiceClient.extraire` |
| `AnalyseFraudeService` (partie visuelle, Anthropic) | `AnalyseFraudeService.analyserVisuellement` (deleguee) | `AiServiceClient.analyserVisuellement` |
| `MatchingSemantiqueService` (Anthropic) | `MatchingSemantiqueService` (fin wrapper) | `AiServiceClient.rapprocherSemantiquement` |
| `WhatsAppCloudApiClient` (appel direct Graph API) | `WhatsAppCloudApiClient` (delegue) | `AiServiceClient.envoyerTemplate` |
| `WhatsAppWebhookController` + `WhatsAppMediaDownloader` + `WhatsAppSignatureVerifier` | *(supprimés, sans remplacement Java)* | webhook reçu directement par ai-service |

`POST /api/v1/internal/whatsapp/documents` (`WhatsAppInternalIngestionController`,
`agent/whatsapp/`) reçoit `fichier` + `metadata` (la partie `metadata` doit porter
`Content-Type: application/json`, cf. `core_client.py`, pour que Spring la bind en
`WhatsAppDocumentPayloadDTO` via `@Valid @RequestPart`), vérifie `X-Internal-Api-Key` contre
`ai-service.api-key`, réutilise la résolution téléphone→entreprise et l'idempotence `messageId`
déjà en place, fusionne les alertes visuelles reçues avec les contrôles déterministes
(`AnalyseFraudeService.alertesDeterministes`), et démarre le pipeline agentique.

Les tests unitaires Java touchés par ce refactor (`AnalyseFraudeServiceTest`,
`MatchingSemantiqueServiceTest`, `WhatsAppIngestionServiceTest`) ont été réécrits en conséquence ;
`WhatsAppSignatureVerifierTest` a été supprimé (classe testée disparue). `DocumentServiceTest` et
`RapprochementBancaireServiceTest` n'ont eu besoin d'aucun changement (ils mockent leurs
collaborateurs par interface/type, dont la signature publique n'a pas changé).

### Outils de l'agent comptable autonome (`agent/tools/`, nouveau)

| Outil (ai-service) | Backend Java | Détail |
|---|---|---|
| `verifier_doublon_montant` | `GET /api/v1/internal/agent-tools/doublons` | Réutilise `DocumentRepository.rechercherDoublonsPotentiels` (même requête que `AnalyseFraudeService.verifierDoublon`) |
| `consulter_historique_fournisseur` | `GET /api/v1/internal/agent-tools/historique-fournisseur` | **Capacité entièrement nouvelle** — requête JSONB native (`donnees_ocr ->> 'fournisseur'`), le fournisseur n'étant pas une colonne dédiée |
| `demander_clarification_client` | `POST /api/v1/internal/agent-tools/demande-clarification` | Réutilise `DemandePiece` via `DemandePieceService.creerDepuisAgent` (nouvelle variante sans `UtilisateurPrincipal`) |

`AgentToolsInternalController` suit exactement le même principe d'authentification que
`WhatsAppInternalIngestionController` (`X-Internal-Api-Key` contre `ai-service.api-key`), et
`AgentToolsServiceTest` couvre les 3 méthodes du service (doublon détecté/absent, agrégats
d'historique avec/sans données, délégation de la clarification).

Le module Java a depuis été recompilé et testé à de multiples reprises dans cet environnement
(JDK 26 réel, `mvn test` — voir `docs/P1_9_RAPPORT_FINAL.md` : 310 tests Java, 0 échec au
2026-08-13).
La phrase précédente de ce document ("aucun JDK disponible") était périmée — corrigée ici
(contradiction relevée par l'audit P1.9).

## À finaliser avant mise en service

- **Basculer `AI_PROVIDER=anthropic` et vérifier un premier appel réel** dès qu'une clé Anthropic
  avec budget est disponible : aucun changement de code n'est nécessaire (`AnthropicLLMProvider`
  existe déjà et délègue à `anthropic_client.call_structured`, inchangé depuis avant cette
  mission) — uniquement renseigner `ANTHROPIC_API_KEY` et `AI_PROVIDER=anthropic` dans `.env`,
  puis lancer une requête réelle sur `/api/v1/matching/analyze` pour confirmer le contrat de
  sortie du modèle en conditions réelles (le mock ne peut garantir que la *forme* de la réponse,
  pas la qualité du jugement de Claude).
- Décider si `POST /api/v1/agent/analyser-piece` (boucle tool-use complète) doit remplacer ou
  compléter le duo `extraction/document` + `fraude/analyse-visuelle` dans les flux d'ingestion
  existants (upload web, WhatsApp) — aujourd'hui les deux coexistent, l'agent autonome n'est
  câblé nulle part encore côté `DocumentService`.
- Repointer l'URL du webhook WhatsApp dans Meta Business Manager vers ai-service
  (`https://<host-ai-service>/api/v1/whatsapp/webhook`) si ce n'est pas déjà fait : le contrôleur
  Java équivalent a été supprimé, un webhook encore configuré vers Spring Boot ne serait plus
  traité du tout.
- Retry / file d'attente si `notifier_document_whatsapp` échoue (cf. note de fiabilité
  ci-dessus) — la pièce est aujourd'hui perdue sans rejeu si Spring Boot est indisponible au
  moment de l'appel.
- Ajouter la vérification `hub.mode`/`hub.verify_token` dans la configuration Meta Business
  Manager avec le même `WHATSAPP_VERIFY_TOKEN` que `.env`.
