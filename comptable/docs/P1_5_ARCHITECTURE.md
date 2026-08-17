# P1.5 — Product Hardening + Unified Accounting Workspace
## Architecture retenue (post-audit, section 87 de la mission : exécution directe, sans confirmation)

Quatre audits parallèles (matrice d'endpoints, états UI Angular, santé des migrations/index,
chasse aux bugs ciblée) ont précédé toute décision. Contrairement au P0/P1, ce chantier ne part
pas d'un terrain vierge : le P0/P1 sont déjà construits et connus en détail (documentés dans
`docs/P0_RAPPORT_FINAL.md` et `docs/P1_RAPPORT_FINAL.md`). L'audit P1.5 s'est donc concentré sur
les véritables inconnues : quels endpoints sont réellement consommés, quels états UI manquent,
quels index manquent, et quels bugs réels existent.

## Faits d'audit qui déterminent l'architecture

- **Le plus important** : `finance` (Cash Position/Forecast/Snapshot), `recouvrement`
  (Collection Risk) et `fiscalite/conformite` (Compliance Center) — trois modules backend
  livrés en P1, testés, correctement sécurisés — **n'ont aucun écran Angular**. Le travail de
  P1.5 n'est donc pas principalement de la construction backend, mais du **branchement** : la
  majorité du "Unified Accounting Workspace" consiste à exposer des capacités déjà réelles,
  pas à en inventer de nouvelles.
- **`AccountingProposalController`** (accepter/rejeter une proposition de comptabilisation, P0
  Phase 4) n'a ni consommateur UI ni test au niveau service — le candidat le plus fort à
  « vérifier avant de garder » de tout l'audit. Contrairement à `finance`/`recouvrement`/
  `conformite`, ici il manque aussi les tests, pas seulement l'UI.
- **Bug de sécurité réel confirmé** : `FindingService.assigner` assigne un Finding à
  n'importe quel UUID utilisateur fourni par le client, sans vérifier qu'il appartient au même
  tenant — un collaborateur peut faire fuiter le titre d'un Finding vers la notification d'un
  utilisateur d'un autre cabinet. Introduit en P1 Phase 1, jamais testé pour ce cas. **Corrigé
  en premier**, avant toute nouvelle fonctionnalité (mission section 78).
- **`TaxRuleController`** n'a aucune restriction de rôle dédiée (contrairement à tous ses
  endpoints fiscalité voisins) — un `CLIENT` pourrait l'appeler. Faible sévérité aujourd'hui
  (retourne toujours `[]`), mais incohérent. Corrigé en même temps.
- **Index manquants confirmés** (pas des suppositions) : `finding` n'a que des index
  mono-colonnes (`entreprise_id`, `status`, `severity` séparément) alors que la requête la plus
  fréquente du système (`findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc`, appelée
  par `ClotureReadinessService`, `DossierComptableService`, `InboxService`, `CopilotService`,
  `ComplianceCenterService`) filtre sur les trois. `ligne_ecriture` n'a qu'un index sur
  `compte_id` alors que `BalanceService`/`BalanceAgeeService`/`GrandLivreService` filtrent aussi
  par date. `audit_log` (nouvellement écrit depuis P1) n'a aucun index couvrant ses vrais
  patterns de lecture.
- **États UI incohérents, pas absents** : le pattern `chargement`/`erreur`/`etat-vide` existe
  et est majoritairement suivi, mais 2 vrais cas d'erreurs silencieuses confirmés
  (`cockpit-cabinet.component.ts` avale les erreurs de charge/analytics ; `document-list.
  component.ts` n'a aucun signal `erreur` du tout, un échec ressemble à "aucun document").
- **Duplication CSS désormais justifiée** : `.message-erreur` dupliqué dans 23 fichiers,
  `.badge` dans au moins 9 — le seuil de duplication raisonnable est dépassé, contrairement au
  P0/P1 où ce n'était pas encore le cas. Un `shared/` est justifié maintenant.
- **Seulement 3 tests `@SpringBootTest` réels** contre Postgres (`ComptabilisationIntegrationTest`,
  `P0CoreIntelligentE2ETest`, `P1CabinetOperatingSystemE2ETest`) sur 55 fichiers de test — les
  52 autres sont des tests Mockito purs. Aucun test HTTP (`MockMvc`) n'existe — lacune déjà
  documentée en P1, toujours vraie.
- **3 tables mortes confirmées** (`permission`, `role_permission`, `collaborateur_entreprise`,
  toutes V1, zéro référence Java) — vestiges d'un modèle RBAC plus granulaire jamais utilisé,
  remplacé par le système `Role`/`Utilisateur` réellement en place. Non supprimées dans ce
  chantier (destructif, hors scope sans confirmation explicite).

## Décision de portée (section 88 : valeur maximale, dette minimale)

Face à l'ampleur de la mission (90 sections, 20 phases suggérées), la priorisation retenue :

**Niveau 1 — corrections obligatoires avant toute nouvelle fonctionnalité** (section 78) :
fuite tenant sur l'assignation de Finding, restriction de rôle manquante sur TaxRule, index
manquants, deux erreurs UI silencieuses.

**Niveau 2 — le vrai livrable P1.5** : brancher les capacités déjà construites (Trésorerie,
Recouvrement, Compliance, Propositions de comptabilisation) dans une expérience de travail
cohérente, plutôt que de les laisser orphelines côté backend. Le Dossier Comptable Intelligent
existant devient le point d'entrée ; la Work Queue (déjà unifiée en P1 via `InboxService`)
gagne un vrai panneau de détail (Evidence Panel) avec actions contextuelles réelles.

**Niveau 3 — si le temps le permet** : Copilot Dirigeant, tests `MockMvc` de permissions,
couverture de test d'`AccountingProposalService`.

Ce qui n'est PAS fait dans ce chantier (documenté honnêtement, pas caché) : suppression des
tables mortes, observabilité/métriques complètes (section 58), infrastructure de background
jobs (section 61), tests de performance à 1000 entreprises (section 59), scénarios de
trésorerie hypothétiques (section 33). Chacun nécessiterait soit une décision destructive
(tables), soit une infrastructure nouvelle non justifiée par un besoin immédiat démontré — à
l'opposé de la section 79 qui interdit d'inventer des fonctionnalités « parce qu'elles sont
faciles à coder ».

## Ordre d'exécution retenu

1. Corrections de sécurité/données (fuite tenant, TaxRule, index) + tests de non-régression.
2. Correction des états UI silencieux + extraction des composants partagés (badge,
   message-erreur, etat-vide).
3. Écran Trésorerie (Cash Position + Forecast) — branche `finance/` déjà testé.
4. Écran Recouvrement — branche `recouvrement/` déjà testé.
5. Écran Compliance — branche `fiscalite/conformite/` déjà testé.
6. Evidence Panel + actions contextuelles dans la Work Queue (accepter/rejeter proposition de
   comptabilisation, assigner un Finding avec sélection de collaborateur réelle).
7. Copilot Dirigeant (si le temps le permet).
8. Tests E2E consolidés + dataset + rapport final.

Vérification après chaque étape : `mvn compile && mvn test-compile && mvn test` côté
`comptable/`, `pytest` côté `ai-service/` si touché, `ng build --configuration=production` côté
frontend — même discipline qu'en P0/P1.
