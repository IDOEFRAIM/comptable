# Rapport final — Mission P1.5 « Hardening + Unified Accounting Workspace + Industrialisation du Core Intelligent »

Ce rapport suit la structure imposée par la mission (section 77, points A-Q). Comme pour
[`P0_RAPPORT_FINAL.md`](P0_RAPPORT_FINAL.md) et [`P1_RAPPORT_FINAL.md`](P1_RAPPORT_FINAL.md), il
reflète le code réel — vérifiable par `mvn test`, `ng build --configuration=production`, et les
fichiers cités.

## A. Audit initial

Quatre audits ciblés en parallèle (endpoints/permissions, états UI Angular + duplication CSS,
santé des migrations/index, composition des tests + chasse aux bugs), délibérément scopés sur
les inconnues réelles plutôt que de ré-auditer P0/P1 déjà profondément connus pour les avoir
construits. Faits confirmés (pas supposés) :

- **Fuite de sécurité tenant réelle** dans `FindingService.assigner` : aucune vérification que
  l'utilisateur assigné appartient au même tenant que l'appelant.
- **`TaxRuleController`** tombait sur la règle générique `.authenticated()` au lieu du
  `hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")` de tous ses voisins fiscalité — un CLIENT
  pouvait l'appeler.
- **3 index manquants confirmés** par lecture des requêtes réelles (pas de recommandation
  générique) : `finding(entreprise_id, status, severity)`, `ligne_ecriture(ecriture_id)`,
  `audit_log(tenant_id, created_at)` + `audit_log(entite, entite_id, created_at)`.
- **États UI silencieux confirmés** dans 2 composants (`cockpit-cabinet`, `document-list`) —
  échec réseau indiscernable de "aucune donnée".
- **Duplication CSS confirmée** : `.message-erreur` dans 23 fichiers, `.etat-vide` dans 19,
  `.badge` dans 9+, valeurs identiques partout.
- **`finance`/`recouvrement`/`fiscalite/conformite` backend-complets, UI absente** — confirmé
  par recherche exhaustive (`grep`) côté Angular, zéro résultat.
- **`AccountingProposalController` : zéro UI, zéro test au niveau service/contrôleur** —
  confirmé (`Glob **/AccountingProposal*Test*.java` → 1 seul fichier, couvrant uniquement le
  builder de résolution).
- **Aucun test HTTP réel (MockMvc/TestRestTemplate) n'existait dans tout le projet** — confirmé
  par `grep` sur `src/test`, zéro résultat. Explicitement documenté comme dette P2 dans
  `P1_DATASET_SYNTHETIQUE.md` (section 7).
- **`ComptableApplicationTests.contextLoads`** documenté depuis P0 comme un flake Docker
  Compose "intermittent" — cause racine jamais identifiée avant ce chantier.

Détail complet dans [`P1_5_ARCHITECTURE.md`](P1_5_ARCHITECTURE.md), écrit avant tout code.

## B. Gaps trouvés (résumé, détail au point A)

1. Fuite tenant sur l'assignation de Finding.
2. Trou de permission sur `TaxRuleController`.
3. 3 index manquants sur les requêtes les plus fréquentes du système.
4. 2 composants Angular avalant silencieusement les erreurs réseau.
5. Duplication CSS massive (23/19/9+ fichiers) sans primitive partagée.
6. 3 écrans backend-complets sans UI (Trésorerie, Recouvrement, Compliance).
7. `AccountingProposal` sans UI et sans test de service/contrôleur.
8. Assignation de Finding backend-only (pas de sélecteur UI, pas d'endpoint pour lister tous les
   collaborateurs d'un tenant indépendamment de leurs tâches).
9. Copilot Dirigeant demandé par la mission, absent.
10. Aucun test HTTP réel dans tout le projet.
11. (Découvert en cours de chantier, point N) `ComptableApplicationTests` mal configuré,
    responsable du flake historique.
12. (Découvert en cours de chantier, point N) 3 des 6 libellés de suggestion initiaux du Copilot
    Dirigeant ne correspondaient pas aux mots-clés du classifieur déterministe.

## C. Architecture retenue

Trois niveaux de priorité (documentés en détail dans `P1_5_ARCHITECTURE.md`) :

- **Tier 1 (critique, fait en premier)** : correctifs de sécurité/données — fuite tenant,
  `TaxRuleController`, index.
- **Tier 2 (cœur du chantier)** : câbler l'existant backend-complet dans une UI réelle
  (Trésorerie, Recouvrement, Compliance, Evidence Panel), corriger les états UI silencieux,
  consolider la duplication CSS en primitives globales.
- **Tier 3 (si le temps le permet)** : Copilot Dirigeant, tests `AccountingProposalService`,
  tests HTTP réels.

**Décision explicitement documentée et respectée** : jamais de reconstruction de Matching/
Missing Evidence/Control Engine/Dossier/Closing/Cockpit/Cash/Collection/Compliance/Copilot —
chaque écran ajouté appelle les services P0/P1 existants sans réimplémenter leur logique.

Tous les Tier 1, 2 et 3 ont été livrés (le Tier 3 était conditionnel — "si le temps le permet" —
et a été entièrement réalisé).

## D. Abstractions mutualisées

- **Primitives CSS globales** (`frontend/src/styles.scss`) : `.message-erreur`, `.etat-vide`,
  `.badge` — définies une fois, utilisées par tous les nouveaux écrans P1.5 (Trésorerie,
  Recouvrement, Compliance) sans duplication locale. Les 23/19/9 fichiers pré-existants n'ont
  **pas** été rétrofités (risque de régression sans bénéfice fonctionnel réel sur du CSS déjà
  correct — décision explicite, cohérente avec la philosophie anti-sur-ingénierie de la mission,
  section 79).
- **`CollaborateurDto`/`InboxService.listerCollaborateurs`** : nouvelle capacité minimale
  (lister tous les collaborateurs d'un tenant, pas seulement ceux ayant déjà une tâche) —
  dépendance bloquante réelle pour rendre le bouton "Assigner" fonctionnel, ajoutée en Java
  plutôt que bricolée côté Angular à partir de données incomplètes.
- **`CopilotService.repondrePourDirigeant`** réutilise 100% du pipeline Evidence-First existant
  (`IntentClassifier`, `collecter(...)`, `CopilotExplainer`) — seule différence, un filtre de
  sous-ensemble d'intentions (`INTENTS_DIRIGEANT`). Aucune deuxième implémentation du Copilot.

## E. Modèles créés/modifiés

Aucune nouvelle entité JPA. Seuls ajouts :

- `CollaborateurDto` (record, `cabinet/web/dto/`).
- Migration `V28__index_hardening.sql` (3 index, aucune nouvelle table).
- `FindingDto` (Angular) étendu avec `assignedToId`/`assignedToNom`/`dueDate` (déjà présents côté
  Java, absents côté Angular — corrigé).
- Nouveaux modèles Angular purement des projections des DTO Java existants : `CashPositionDto`,
  `PrevisionTresorerieDto`, `FinancialSnapshotDto` (finance), `CollectionRiskDto`
  (recouvrement), `ObligationFiscaleDto`/`TaxHealthDto`/`ComplianceCenterDto` (conformité),
  `AccountingProposalDto` (comptabilisation).

## F. APIs

Nouveaux endpoints (tous réutilisent des services existants, sauf `copilot-dirigeant` qui ajoute
un filtre d'intentions) :

| Méthode | Chemin | Rôles |
|---|---|---|
| GET | `/api/cabinet/collaborateurs` | COLLABORATEUR, EXPERT_COMPTABLE |
| POST | `/api/entreprises/{id}/copilot-dirigeant/question` | CLIENT, COLLABORATEUR, EXPERT_COMPTABLE |

Aucun autre nouvel endpoint : Trésorerie, Recouvrement, Compliance, AccountingProposal
utilisaient déjà des endpoints P0/P1 complets, jamais appelés depuis l'UI — c'est le câblage qui
manquait, pas l'API.

Correctif de sécurité sur un endpoint existant : `/api/fiscalite/tax-rules/**` restreint à
`COLLABORATEUR`/`EXPERT_COMPTABLE` (était `authenticated()` générique).

## G. Backend (Java)

- `FindingService.assigner` : nouvelle méthode privée `chargerCollaborateurDuTenant` (404 si
  l'assigné n'appartient pas au même tenant — même convention que `EntrepriseAccessService`).
- `InboxService`/`InboxController` : `listerCollaborateurs`.
- `CopilotService` : `repondrePourDirigeant` + `INTENTS_DIRIGEANT` (6 des 8 intentions) +
  `reponseIntentInconnuePourDirigeant`.
- `CopilotDirigeantController` (nouveau, thin wrapper).
- `SecurityConfig` : 2 correctifs (`tax-rules`, ajout du matcher `copilot-dirigeant`).
- `UtilisateurRepository.findByTenantIdAndRoleCodeIn` (nouvelle requête JPQL).
- Migration `V28`.

## H. AI Service (Python)

**Aucune modification.** P1.5 ne touche à aucun fichier sous `ai-service/` — le chantier est
backend Java + frontend Angular. `AI_PROVIDER=mock` reste inchangé et continue de fonctionner
tel quel (vérifié indirectement : tous les tests Java qui passent par `CopilotExplainer` en mode
dégradé/mock continuent de passer, `AccountingProposalServiceTest` et `IntentClassifierTest`
inclus).

## I. Frontend (Angular)

Nouveaux écrans (chacun : `models/*.model.ts`, `*.service.ts`, `*.component.ts/.html/.scss`,
route + onglet dans `entreprise-workspace.component.html`) :

- `features/finance/` — Trésorerie (Position + Prévision + Snapshot).
- `features/recouvrement/` — Recouvrement (risques clients).
- `features/conformite/` — Compliance (obligations + anomalies + tax health).
- `features/copilot/copilot-dirigeant-widget.component.ts` — variante simplifiée du Copilot,
  intégrée dans `documents-page.component.ts` (page d'atterrissage du CLIENT), masquée pour le
  personnel du cabinet.

Composants corrigés (états silencieux → signal `erreur` affiché) :
`cockpit-cabinet.component.ts`, `document-list.component.ts`.

Evidence Panel (`tableau-de-bord.component.ts`/`.html`) étendu : sélecteur d'assignation inline
par Finding (`<select>` + `assignerFinding`), bloc accepter/rejeter conditionnel
(`sourceType === 'DOCUMENT'` → tente de charger une `AccountingProposal`, gère le 404 comme
absence normale).

Primitives CSS globales (`styles.scss`) — voir point D.

## J. Sécurité

Vérifiée **réellement**, pas seulement au niveau service, via
[`P1_5SecuriteHttpE2ETest`](../src/test/java/com/example/comptable/security/P1_5SecuriteHttpE2ETest.java)
(8 tests, MockMvc + vrais jetons JWT + vraie chaîne `SecurityFilterChain`) :

- Requête sans jeton → 403.
- Isolation tenant réelle (entreprise d'un autre tenant → 404, jamais 403).
- CLIENT bloqué sur le portefeuille cabinet et le tableau de bord comptable.
- CLIENT autorisé sur ses propres documents, bloqué sur ceux d'une entreprise à laquelle il
  n'est pas rattaché.
- CLIENT bloqué sur le Copilot comptable, autorisé sur le Copilot Dirigeant — même chemin racine
  `/api/entreprises/{id}/...`, deux endpoints distincts, vérifié en vrai.
- EXPERT_COMPTABLE autorisé sur ses propres ressources.

Plus, vérifié manuellement dans le navigateur (session réelle, JWT réel) : le nouvel endpoint
`GET /api/cabinet/collaborateurs` retourne exactement le collaborateur du tenant courant, rien
d'un autre tenant.

Correctifs de sécurité livrés : fuite tenant sur l'assignation de Finding (avec 2 tests de
régression négatifs dans `FindingServiceTest`), permission manquante sur `TaxRuleController`.

## K. Performance

3 index ajoutés (`V28`), chacun justifié par une requête réelle identifiée dans le code (pas une
supposition) — voir `V28__index_hardening.sql` pour le détail par index. Aucune mesure de charge
formelle (10/100/500/1000 entreprises) réalisée dans ce chantier — voir point P (limites).

## L. Tests — résultats réels

```
mvn test  →  295 tests, 0 échec, 0 erreur (suite Java complète)
ng build --configuration=production  →  succès, template checking strict activé
```

Détail des tests nouveaux/modifiés dans ce chantier :

| Fichier | Tests | Nature |
|---|---|---|
| `FindingServiceTest` | 6 (2 nouveaux) | Correctif fuite tenant + régression |
| `InboxServiceTest` | 4 (1 nouveau) | `listerCollaborateurs` |
| `AccountingProposalServiceTest` | 7 (nouveau fichier) | Accepter/rejeter/idempotence/isolation |
| `CopilotServiceTest` | 8 (4 nouveaux) | Copilot Dirigeant, sous-ensemble d'intentions |
| `IntentClassifierTest` | 3 (1 nouveau) | Verrouille les libellés du widget Dirigeant |
| `P1_5SecuriteHttpE2ETest` | 8 (nouveau fichier) | HTTP réel, voir point J |
| `ComptableApplicationTests` | 1 (corrigé) | Flake historique résolu, voir point N |

Avant ce chantier : 274 tests (`0 échec, 1 erreur` — le flake). Après : 295 tests, **0 échec, 0
erreur** — première suite entièrement verte de tout le projet multi-session.

## M. Scénarios E2E réellement exécutés

Voir [`P1_5_DATASET.md`](P1_5_DATASET.md) pour le détail complet référencé par test. Résumé :
sécurité HTTP (8 scénarios réels), Trésorerie/Recouvrement/Compliance (vérifiés manuellement
dans le navigateur contre le backend réel, requêtes réseau inspectées), AccountingProposal (7
scénarios service), assignation de Finding (bout en bout manuel), Copilot Dirigeant (6 questions
autorisées + 1 hors périmètre, vérifiées via requêtes HTTP réelles authentifiées dans le
navigateur).

## N. Bugs découverts (règle section 78 : confirmé → corrigé → testé → documenté)

1. **Fuite tenant sur l'assignation de Finding** — confirmé, corrigé
   (`chargerCollaborateurDuTenant`), testé (2 tests négatifs), documenté (point J).
2. **`TaxRuleController` sans restriction de rôle** — confirmé, corrigé (`SecurityConfig`),
   documenté (point F).
3. **3 des 6 libellés de suggestion initiaux du Copilot Dirigeant ne déclenchaient pas l'intention
   attendue** ("Où en est mon dossier ?" ne contient pas "prêt", requis par le classifieur) —
   trouvé en testant l'endpoint en direct dans le navigateur (réponses de repli inattendues sur
   des boutons censés fonctionner), corrigé (libellés reformulés pour contenir les mots-clés
   exacts sans perdre en naturel), testé
   (`IntentClassifierTest.suggestions_du_copilot_dirigeant_restent_reconnues_par_le_classifieur`,
   verrouille les 6 libellés définitifs contre toute dérive future).
4. **`ComptableApplicationTests` mal configuré, cause racine du flake historique "intermittent"
   depuis P0`** — en construisant `P1_5SecuriteHttpE2ETest`, le même échec
   ("Failed to determine a suitable driver class") s'est reproduit de façon 100% déterministe en
   exécution isolée. Diagnostic : `spring.docker.compose.skip.in-tests` est vrai par défaut ;
   tous les tests `@SpringBootTest` qui fonctionnaient déjà (`ComptabilisationIntegrationTest`,
   `P0CoreIntelligentE2ETest`, `P1CabinetOperatingSystemE2ETest`) déclarent explicitement
   `properties = "spring.docker.compose.skip.in-tests=false"` — `ComptableApplicationTests`
   était le seul à ne pas le faire. Corrigé (même override ajouté), vérifié (suite complète
   passée deux fois de suite après correctif, 295/295 les deux fois). Ce n'était donc pas un
   flake d'infrastructure comme documenté depuis P0, mais une vraie omission de configuration —
   corrigée ici avec la cause racine identifiée, pas contournée.
5. **Cold-start transitoire sur `/api/notifications/non-lues/compteur`** (500 une seule fois
   juste après démarrage du serveur backend, puis 200 sur tous les appels suivants, observé 2
   fois pendant les sessions de vérification manuelle) — investigué (logs backend, requêtes
   réseau), non reproductible à la demande, disparaît de lui-même en quelques centaines de
   millisecondes. Non corrigé dans ce chantier (pas de code P1.5 dans le chemin de cet endpoint,
   pas de reproduction fiable pour écrire un test de régression valable) — **documenté** plutôt
   que masqué, recommandation P2 : ajouter une temporisation/retry cosmétique côté widget
   notifications si le symptôme persiste en usage réel.

## O. KPI livrés (mesurables dès maintenant)

- Suite de tests Java : 295 tests, 0 échec, 0 erreur (vs 274/1 erreur avant ce chantier).
- 3 nouveaux écrans fonctionnels câblés sur du backend déjà testé (0 nouvelle logique métier
  dupliquée).
- 8 nouveaux tests de sécurité HTTP réels (0 avant ce chantier).
- 7 nouveaux tests de service sur `AccountingProposal` (0 avant ce chantier).
- 2 vulnérabilités de sécurité réelles trouvées et corrigées avec régression.
- 1 bug fonctionnel réel trouvé et corrigé sur le Copilot Dirigeant avant mise à disposition.
- 1 flake historique du projet (présent depuis P0) diagnostiqué à la racine et résolu.

## P. Limites connues et recommandations P1→P2

- **Pas de test HTTP pour les écrans Trésorerie/Recouvrement/Compliance** (vérifiés
  manuellement, pas par un test automatisé dédié) — la mécanique MockMvc existe désormais
  (`P1_5SecuriteHttpE2ETest`), l'étendre à ces écrans est un P2 mécanique, pas une inconnue.
- **`AccountingProposal` accepter/rejeter non exercé en direct dans le navigateur** (aucun
  document `TRAITE` disponible dans l'entreprise de test manuelle) — couvert par 7 tests de
  service réels, mais pas par une capture d'écran bout en bout.
- **CSS dupliqué pré-existant (23/19/9 fichiers) non rétrofité** — décision assumée (point D),
  P2 si un jour un composant partagé (`shared/badge`, `shared/error-message`) est jugé
  rentable au-delà de la simple déduplication CSS déjà faite.
- **Aucune mesure de charge formelle** (10/100/500/1000 entreprises) — les 3 index ajoutés sont
  justifiés par lecture de requête, pas par un profilage sous charge réelle.
- **`GET /api/cabinet/collaborateurs` n'a pas de test HTTP dédié** (seulement testé côté
  service + vérifié manuellement) — même remarque que ci-dessus.

## Q. Ce qui doit rester P2

- Peuplement réel de `TaxRule` (actuellement scaffolding volontairement vide, voir
  `TaxRuleController` javadoc — dépendance P2 déjà documentée avant ce chantier).
- Étendre les tests HTTP réels (`P1_5SecuriteHttpE2ETest` en pose la base) aux écrans P1.5 et à
  l'ensemble des endpoints cabinet/entreprise.
- Rétrofit du CSS dupliqué pré-existant vers les primitives globales ajoutées ici, si un
  changement visuel plus large est un jour entrepris.
- Dataset de démonstration à grande échelle (5 profils d'entreprise complets, mission section
  75) — explicitement non reconstruit ici (voir `P1_5_DATASET.md`), les mécanismes qu'il
  démontrerait sont déjà prouvés par les tests P0/P1 existants.
- Mesure de performance formelle sous charge (10/100/500/1000 entreprises).
- Paie/stock avancé/gestion commerciale/bancaire/multi-pays OHADA/agents spécialisés —
  explicitement hors périmètre depuis la définition du succès de la mission (section 90).
