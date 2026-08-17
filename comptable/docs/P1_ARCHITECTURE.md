# P1 — Cabinet Operating System + Financial Intelligence Layer
## Architecture retenue (post-audit, section 70 de la mission)

Ce document consigne les décisions d'architecture prises après l'audit complet (5 explorations
parallèles : modèle tenant/RBAC, modèle financier, contrats exacts du P0, `ai-service` Python,
frontend Angular). Conformément à la consigne de la mission P1 (« ne demande pas de confirmation
après l'audit »), ces décisions sont exécutées directement, sans validation intermédiaire.

## Faits d'audit qui déterminent l'architecture

- **`Tenant` = cabinet, `Entreprise` = client**, relation déjà en place (`Entreprise.tenant`,
  `EntrepriseRepository.findByTenantId`). `EntrepriseService.lister()` est déjà la seule
  agrégation cross-entreprise existante — une simple liste, sans score ni statut.
- **Aucune vue cabinet-wide n'existe** : tout dans `dossier/`, `agent/finding/` opère
  strictement sur un `entrepriseId` de path variable. Le Cabinet Cockpit est donc une couche
  d'agrégation nouvelle, pas une extension d'un existant.
- **RBAC** : 4 rôles réels (`SUPER_ADMIN`, `EXPERT_COMPTABLE`, `COLLABORATEUR`, `CLIENT`, pas 3).
  `EntrepriseAccessService.verifierAcces` est le seul point de contrôle d'accès par entreprise
  aujourd'hui (pas de `@PreAuthorize` malgré `@EnableMethodSecurity` déclaré). Un `CLIENT` n'a
  accès qu'aux entreprises liées via `UtilisateurEntreprise` ; `COLLABORATEUR`/`EXPERT_COMPTABLE`
  ont accès implicite à tout le tenant. **Tout filtrage cabinet-wide doit vérifier le rôle AVANT
  toute agrégation**, jamais après.
- **`Finding` n'a aucune notion d'assignation** (pas de champ `assignedTo`, pas de `dueDate`
  autre que via `DemandePiece.dateLimite`). Le Workflow Collaboratif (section 11-13) nécessite
  une extension minimale de `Finding`, pas une nouvelle table `Task`.
- **`DossierComptableDto.scoreGlobal` + `statut` SONT DÉJÀ un Client Health Score** déterministe
  (pénalités par sévérité de Finding, jamais de LLM). Le P1 n'a **aucune raison de recalculer un
  second score** — Client Health = présentation du score déjà produit par le P0, agrégée sur
  plusieurs entreprises.
- **`LigneEcritureRepository.soldeTresorerie`** calcule déjà un solde classe 5 réel (`numero
  like '5%'`, VALIDEE uniquement). **`BalanceAgeeService`** calcule déjà un aging 30/60/90 jours
  par tiers. Ce sont les fondations directes de Cash Position et Collection Risk — aucune
  nouvelle logique de calcul de solde à écrire.
- **Aucune entité Mobile Money n'existe** — seul `SourceType.MOBILE_MONEY_TRANSACTION` existe
  côté matching IA, sans persistance dédiée. La mission interdit explicitement de supposer une
  intégration externe. Décision : dériver le canal (Banque/Caisse/Mobile Money) par une
  heuristique sur `numero`/`intitulé` du `CompteComptable` classe 5 existant — **pas de nouvelle
  colonne, pas de nouvelle table** — avec un point d'extension documenté pour une vraie
  intégration future (section 21 : « créer les interfaces d'intégration pour extension future »).
- **`agent_orchestrator.py` (agent autonome existant) n'est PAS réutilisable tel quel pour le
  Copilot** : il est structurellement capable d'écriture (un de ses 3 outils crée une
  `DemandePiece`), n'a aucun point d'injection de provider mock (les tests le patchent via
  `unittest.mock.patch`, contrairement à `matching_engine.analyze(..., llm_provider=...)`), et
  boucle jusqu'à 6 itérations. Le Copilot P1 doit être un **chemin de code séparé, plus simple**,
  suivant le pattern déjà éprouvé de `matching_engine` (`LLMProvider.call_structured`,
  injection directe, un seul appel, jamais de boucle multi-tours) — conforme à la consigne
  « ne pas sur-agentiser » (section 62).
- **Deux représentations facture fournisseur** (`Document` FACTURE_ACHAT vs
  `FactureFournisseur`) — confirmé toujours non unifiées, et `FactureFournisseur` n'a **aucun
  champ `dateEcheance`**. Impact direct sur Cash Forecasting/Compliance : les échéances
  fournisseurs ne peuvent être calculées de façon fiable pour ce canal — documenté comme
  limite P1, pas contourné par une invention de donnée.
- **`TypeImpot` ne modélise que TVA/IS/PATENTE/CNPS/AUTRE** — pas d'IR ni de Retenues. Le
  Compliance Center P1 n'affichera que les types réellement modélisés ; ajouter IR/Retenues
  sans règle vérifiée serait fabriquer une obligation fiscale qui n'existe pas dans le système.
- **`audit_log` existe en base** (pattern polymorphe `entite`+`entite_id`, lecture seule,
  découvert lors de l'audit P0) — sa couverture d'écriture réelle sera vérifiée en Phase 1
  avant de décider s'il faut un nouveau writer ou juste l'étendre.

## Abstractions transversales — décision, une par une (section 7)

| Abstraction proposée par la mission | Décision | Raison |
|---|---|---|
| `CabinetWorkItem` | **Rejetée** | `Finding` + `DemandePiece` couvrent déjà 95 % du besoin ; une projection de lecture (`InboxItemDto`) suffit — pas de nouvelle table polymorphe de plus |
| `Task` | **Rejetée comme table** ; acceptée comme **2 colonnes ajoutées à `Finding`** (`assignedTo`, `dueDate`) | Un `Finding` non résolu EST la tâche ; dupliquer l'information dans une table séparée créerait deux sources de vérité à synchroniser |
| `FinancialSnapshot` | **Acceptée comme DTO calculé à la lecture, jamais persisté** | Même raisonnement que `DossierComptable` en P0 : agrégation de requêtes déjà rapides (`soldeTresorerie`, `BalanceAgeeService`) à l'échelle visée, pas de besoin de matérialisation |
| `FinancialAlert` | **Rejetée** | `Finding` couvre déjà toute alerte comptable ; un `FinancialAlert` séparé dupliquerait le concept sans capacité nouvelle |
| `ClientHealth` | **Acceptée comme projection**, pas de nouveau calcul | = `DossierComptableDto.scoreGlobal`/`statut` déjà existants, agrégés par entreprise dans le Cockpit |
| `CashForecast` | **Acceptée comme DTO calculé à la lecture** | Résultat d'un calcul (factures + délais historiques), pas un fait à historiser en P1 — deviendra une table seulement si un besoin de comparer prévision vs réalisé apparaît (P2) |
| `CollectionRisk` | **Acceptée comme valeur dérivée, pas persistée** | Recalculée à chaque lecture depuis `BalanceAgeeService`, exactement comme les scores du Dossier |
| `ComplianceDeadline` | **Rejetée comme nouvelle table** | `DeclarationFiscale.dateEcheance` + `EcheancierTvaGenerator` couvrent déjà ce concept pour la TVA ; le Compliance Center est une vue, pas un nouveau modèle |
| `AuditEvent` | **Réutilise `audit_log` existant** (vérification et extension en Phase 1 si nécessaire) | Ne pas créer une deuxième table d'audit alors qu'une existe déjà en base depuis le P0 |
| `CopilotEvidence` | **Acceptée comme structure de réponse (DTO), pas une table** | C'est un format de citation (`sourceType`+`sourceId`, exactement le pattern déjà utilisé par `Finding`), pas un fait à persister séparément |

**Bilan** : une seule vraie extension de schéma en P1 (deux colonnes sur `Finding`), zéro
nouvelle table de fait métier. Tout le reste est projection/agrégation à la lecture — cohérent
avec le principe P0 déjà validé (« ne pas dupliquer l'état »).

## Ordre d'exécution retenu

Proche de l'ordre suggéré par la mission (section 59), avec un ajustement : le Copilot
(Phase 5) est positionné **après** Cabinet Cockpit + Financial Snapshot plutôt qu'en position
médiane, car son contrat d'évidence (`evidence[]` citant `Finding`/lignes d'écriture/factures)
a besoin que les agrégations qu'il explique existent déjà — expliquer un score de Dossier
suppose que la mécanique de score (déjà en P0) et son contexte cabinet (Phase 2) soient stables
en premier.

1. Fondations (Finding.assignedTo/dueDate, vérification audit_log, enums Notification).
2. Cabinet Cockpit + Client Health + Portfolio Risk.
3. Work Queue unifiée + assignation + charge de travail.
4. Financial Snapshot + Cash Position multicanal.
5. Evidence-First Copilot (niveau comptable, lecture seule).
6. Cash Forecasting (KNOWN/EXPECTED, PREDICTED explicitement différé) + Collection Risk.
7. Compliance Center + Tax Health + schéma TaxRule (vide, dépendance documentée).
8. Cabinet Analytics + extension Copilot niveau cabinet.
9. UI Angular (Cockpit, Inbox, widget Copilot, navigation).
10. Tests E2E + dataset synthétique + rapport final.

## Correction post-audit Phase 10

L'audit du dataset synthétique (Phase 10) a révélé qu'un **export FEC réel et fonctionnel
existait déjà** dans le code (`ExportFecService`, `GET /api/entreprises/{id}/rapports/fec/
{exerciceId}`, 18 colonnes officielles, 5 tests réels) — construit avant ce chantier P0/P1 et
jamais documenté dans `DOCUMENTATION.md`. La section « hors P1 » ci-dessous, écrite pendant la
Phase 7 avant cette découverte, affirmait à tort qu'aucun export FEC n'existait. Corrigé ici et
dans le rapport final (section M) plutôt que de laisser une affirmation fausse dans ce document.

## Explicitement hors P1 (documenté, pas oublié)

- ~~**FEC réel** : aucune règle/format n'est inventée — signalé comme dépendance bloquée sur une
  source réglementaire vérifiée~~ **Correction** : un export FEC reel existe déjà
  (`ExportFecService`), voir ci-dessus. Ce qui reste réellement hors P1 : le clarifier/documenter
  (fait dans `DOCUMENTATION.md` en Phase 10) et lever l'ambiguïté de perimetre reglementaire
  (le format est le FEC français standard, mais le jeu de test utilise un identifiant
  contribuable ivoirien — a clarifier avec un expert-comptable local avant tout usage reel).
- **Règles fiscales Burkina Faso réelles** dans `TaxRule` : seule l'architecture (schéma
  versionné `Country`/`Regime`/`TaxRule`) est construite ; la peupler exige une source
  juridique vérifiée non disponible dans cet environnement.
- **Cash Forecasting niveau PREDICTED (IA/statistique)** : avec un dataset encore petit, une
  « prédiction IA » serait un chiffre fabriqué sans valeur réelle — seuls KNOWN et EXPECTED
  (calculs déterministes) sont livrés ; PREDICTED reste une recommandation P2 explicite.
- **Envoi WhatsApp de recouvrement automatique** : le canal existe déjà pour les demandes de
  pièces, mais l'envoi de messages de relance de paiement nécessite une politique de validation
  humaine que ce chantier ne peut pas définir seul (risque réglementaire/relationnel) — seule la
  recommandation textuelle (LOW/MEDIUM/HIGH + action suggérée) est livrée, jamais l'envoi.
  Réutilisation du canal WhatsApp de recouvrement (l'envoi lui-même) reste P2.
- **Agent autonome généralisé** : `agent_orchestrator.py` n'est pas modifié ni élargi ; le
  Copilot y fait uniquement référence comme option de routage pour les cas ambigus (jamais
  invoqué automatiquement).
- **Allocation de travail assistée par IA** : différée (mission section 38 : l'affectation
  reste humaine en P1 ; recommander sans automatiser serait une fonctionnalité low-value à ce
  stade sans données d'usage réelles).
- **IR / Retenues fiscales** : non ajoutés à `TypeImpot` sans règle vérifiée — seuls les types
  déjà modélisés (TVA/IS/PATENTE/CNPS) apparaissent dans le Compliance Center.
