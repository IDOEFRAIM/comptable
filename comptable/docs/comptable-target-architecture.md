# Comptable — Architecture cible

> Livrable 3. Principe directeur : **étendre ou fédérer l'existant avant de créer** (§22 de la mission).
> Aucune réécriture. Chaque abstraction proposée est justifiée contre ce que le code fait déjà.

```
CERTAIN → automatiser · PROBABLE → proposer · AMBIGU → demander validation
ANORMAL → signaler · NON CONFIGURÉ → bloquer proprement · NON VÉRIFIÉ RÉGLEMENTAIREMENT → ne pas inventer
```

## 1. Vue d'ensemble : ce qui existe / ce qui change

```
SOURCES  upload · WhatsApp · CSV relevé            ← existent   (+ e-mail, hash de fichier : à créer)
   ↓
DOCUMENT   Document + pipeline + checkpoint         ← à ÉTENDRE  (statuts Document Hub, hash, liens)
   ↓
CLASSIFICATION / NATURE   ai-service classifiers    ← existe, ne produit pas encore un AccountPurpose explicite
   ↓
AccountPurpose → AccountingAccountResolver          ← existe ; TOUS les flux doivent y passer (à FINIR)
   ↓
AccountingOperationEngine                           ← à CONSERVER ; absorber le flux document (ComptabilisationService)
   ↓
CONTRÔLES   ControlEngine + Fraude + Readiness      ← à FÉDÉRER derrière un contrat commun (ControlResult)
   ↓
RAPPROCHEMENT   Lettrage + Banque + Matching        ← à GÉNÉRALISER par cas (pas de moteur universel)
   ↓
Finding / Inbox                                     ← existe ; devient le point d'atterrissage unique
   ↓
Validation → Écriture → Pré-clôture → Clôture → États
```

## 2. À conserver tel quel
`AccountingOperationEngine` et les `*Rule` ; `AccountingAccountResolver` / `AccountPurpose` ;
`EntrepriseAccessService.verifierAcces` ; idempotence par source ; `AuditTrailService` (HUMAIN/IA) ;
`Finding` (idempotence, assignation) ; `MatchDecisionEngine` ; `ClotureExerciceService` (verrouillage) ;
la discipline « ResultatRegle : un manque = une explication, jamais un compte deviné ».

## 3. À étendre

| Objet | Extension | Pourquoi l'existant suffit presque |
|---|---|---|
| `Document` | + `contentHash` (SHA-256), `entreprise_id` déjà présent ; statuts Document Hub ; lien vers `Finding` | l'entité, le stockage, le pipeline et le checkpoint existent |
| `StatutDocument` | ajouter MATCHED, DUPLICATE_SUSPECTED, MISSING_INFORMATION, ARCHIVED (garder les 5 actuels, mapper RECEIVED/PROCESSING/EXTRACTED/NEEDS_REVIEW/VALIDATED sur l'existant) | 5 statuts réels déjà utilisés par le pipeline |
| `ClotureReadinessService` | remplacer les 12 `NOT_APPLICABLE` par de vrais contrôles, un par un | structure `ControlItem` déjà là |
| `AuditTrailService` | brancher sur écriture (création/validation), clôture, comptabilisation, pointage/lettrage | service prêt, juste peu appelé |
| `AccountingAccountMapping` | ajouter une origine `HISTORICAL_SUGGESTION` **séparée** de `CONFIGURED_ACCOUNT_MAPPING` (jamais promue automatiquement) | `AccountResolutionSource` existe |
| `Finding` / `TypeFinding` | nouveaux types (DUPLICATE_DOCUMENT, UNUSUAL_AMOUNT, ACCOUNT_DISABLED_USED, TIERS_BALANCE_INVERSE…) | modèle déjà générique (source, evidence, sévérité) |

## 4. À refactorer (progressif, sous filet de tests)

1. **`ComptabilisationService`** : faire passer le flux document → écriture par
   `AccountingOperationEngine` (`proposerAchat`/`proposerVente`) + `AccountingAccountResolver`
   (supprime `"4456"`/`"443"`, le journal en dur, le doublon de contrôles). Garder le contrat HTTP
   actuel (`POST …/documents/{id}/comptabiliser`) et les messages d'erreur.
2. **`MouvementStockService.genererEcriture`** et **`ImmobilisationService.genererDotations`** :
   écrire via le moteur (ou, si le coût est excessif, via un `EcritureFactory` commun qui porte
   équilibre + exercice ouvert + journal + numéro).
3. **Contrôles dupliqués** : un garde-fou « exercice ouvert » partagé ; une règle HT+TVA=TTC unique.
4. **`EtatsFinanciersService`** : sortir les préfixes en dur vers une table de correspondance
   versionnée — **seulement avec une source SYSCOHADA vérifiée**.
5. **Hardcodes `TiersService`, `DeclarationFiscaleService`** vers les rôles/purposes existants.

## 5. À créer (le strict minimum)

### 5.1 `ControlResult` + `ControlRegistry` (fédération, pas un nouveau moteur)
- **Problème** : trois familles de contrôles sans format commun → impossible d'afficher « 12 bloquantes,
  27 pièces manquantes… » de façon homogène ni d'expliquer un blocage de clôture.
- **Responsabilité** : un contrat `ControlResult {code, severity INFO|WARNING|ERROR|BLOCKING, status,
  entityType, entityId, message, explanation, suggestedAction, metadata}` + un registre d'implémentations
  `Control` exécutées par contexte (à la création, à la lecture dossier, en pré-clôture).
- **Dépendances** : `Finding` (persistance), `ControlEngine` / `AnalyseFraudeService` / `ClotureReadinessService`
  deviennent des **fournisseurs** de `ControlResult` ; ils ne sont pas supprimés.
- **Pourquoi l'existant ne suffit pas** : `Finding` persiste un constat mais pas un résultat éphémère
  (contrôle bloquant à la création, point de checklist) ; `ControlItem` n'a ni entité ni explication structurée.
- **Risque** : sur-abstraction. Mitigation : migrer **un contrôle à la fois**, en gardant l'ancien chemin
  jusqu'à parité de tests. Le mapping vers `Finding` reste l'unique persistance (pas de table `control_result`).
- **Distinction à garder** (mission §19) : `deterministe` (HT+TVA≠TTC) / `statistique` (montant vs historique) /
  `assisté IA` (nature inhabituelle) = trois champs `kind`, jamais mélangés.

### 5.2 Explication structurée
- Étendre `ControlResult.explanation` en objet clé/valeur (ex. HT, TVA, TTC, attendu, écart) pour reproduire
  l'exemple de la mission (« HT 100 000 · TVA 16 500 · TTC 118 000 · écart 1 500 »). Le front l'affiche
  tel quel ; plus de « Erreur comptable. ».

### 5.3 Document Hub (extension, pas module séparé)
- Ajout dans `document/` : `ContentHashService`, déduplication à l'upload (`DUPLICATE_SUSPECTED`),
  connecteur e-mail **derrière l'interface existante** `ingererDepuisCanalExterne`.
- Pas de seconde infrastructure documentaire (règle §10).

### 5.4 Drill-down d'écart (« pourquoi ça ne tombe pas juste »)
- Service de lecture `EcartExplainer` : à partir d'un compte/tiers/poste d'état, remonte aux lignes
  d'écriture sources (réutilise `LigneEcritureRepository`, `BalanceService`, `GrandLivreService`).
  Pas de nouvelle table.

### 5.5 Workspace comptable
- Projection de lecture (pas de table) : agrège `Finding` (par type/sévérité), documents par statut,
  taux de rapprochement, préparation de clôture. Évolution de `DossierComptableService`/`InboxService`,
  consommée par l'écran « Dossier client » de la mission.

## 6. À supprimer progressivement
Tables mortes `permission` / `role_permission` / `collaborateur_entreprise` (après confirmation) ;
hardcodes cités §4 ; contrôles dupliqués ; à terme, l'une des deux entités de proposition (décision séparée).

## 7. Principes transverses
- **Multi-tenant** : toute nouvelle entité porte `entreprise_id` (+ `tenant_id` si le modèle voisin l'a) ;
  accès uniquement via `verifierAcces` ; test de non-fuite par entité ajoutée. Envisager la RLS PostgreSQL
  comme filet de sécurité en phase ultérieure (décision à part, coût non trivial).
- **Audit** : tout nouvel événement passe par `AuditTrailService` (`DOCUMENT_*`, `ACCOUNT_*`, `ANOMALY_*`,
  `RECONCILIATION_*`, `CLOSING_CHECK_*`), source HUMAIN ou IA, jamais mélangées.
- **IA** : suggère une *nature* ; seul `AccountPurpose → AccountingAccountResolver` produit un compte.
  Une validation passée = `HISTORICAL_SUGGESTION`, jamais une vérité réglementaire.
- **Rapprochement** : états `MATCHED / PROBABLE / AMBIGUOUS / UNMATCHED` ; plusieurs candidats équivalents
  ⇒ `AMBIGUOUS` (le `MatchDecisionEngine` actuel a déjà `MATCH_REVIEW` ; ajouter l'état « multiple » sans
  créer de moteur universel).
- **Mesure** (§26) : exposer des compteurs (taux de propositions acceptées, % rapproché, temps de clôture) via
  `telemetry/` déjà présent (`quality-dashboard`), pas de nouveau système.
