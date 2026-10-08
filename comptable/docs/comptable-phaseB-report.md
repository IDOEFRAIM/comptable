# Rapport Phase B — de l'écriture fiable au dossier comptable piloté par exception

## 1. Avant / après

**Avant** (fin de la Phase A) : un cœur comptable unique et fiable, mais un dossier qui ne savait pas dire *ce qui reste à faire*.
* Aucune cause métier sur les écritures : impossible de savoir pourquoi une écriture existe, ni d'empêcher qu'un événement rejoué (ou qu'un
  autre flux) la crée deux fois ; une facture d'achat d'immobilisation et le bien correspondant pouvaient compter l'acquisition deux fois.
* Systèmes de contrôle fragmentés : `ControlEngine` (Findings écrits à chaque lecture), `AnalyseFraudeService` (rapport JSON sur le document),
  `ClotureReadinessService` (checklist maison), `MatchDecisionEngine`/`MissingEvidenceAnalyzer` (banque), `ControlResult` seulement « décoratif ».
* `Finding` dédoublonné sur (objet, OPEN) : deux contrôles différents sur un même objet se masquaient ; un constat dont la cause disparaissait
  restait ouvert ; rien ne distinguait une résolution humaine d'une résolution automatique.
* Rapprochement bancaire qui pointait « le seul candidat par montant » sans regarder la date, et laissait la première ligne gagner un mouvement disputé.

**Après** :

```
source métier ─► proposition ─► écriture ─► contrôle ─► Finding ─► revue
 (AccountingSource) (moteur)   (cause unique) (ControlResult) (unité de travail) (Inbox, pré-clôture)
```

* chaque conséquence comptable porte sa cause `(type, id, event)`, **unique en base** ; navigation écriture ↔ source dans les deux sens ;
* facture ↔ immobilisation : une seule acquisition (relation traçable, garde anti-double-comptage, détection si le doublon passe quand même) ;
* **un contrat** (`ControlResult`), **un réconciliateur** (créer / mettre à jour / résoudre automatiquement), **une unité de travail** (`Finding`, clé de
  déduplication unique en base) ;
* un constat dont la cause disparaît se ferme tout seul (`SYSTEM`) ; un constat écarté exige un motif et n'est jamais supprimé ni recréé ;
* une revue par entreprise (tuiles cliquables, filtres, recherche, pagination, lignes explicables) et une pré-clôture qui consomme les mêmes constats ;
* rapprochement bancaire déterministe : seul le certain est automatisé, le probable est proposé, l'ambigu n'est jamais tranché.

## 2. Livrables par lot

| Lot | Livré |
|---|---|
| **B0** | `AccountingSource`, V50 (cause unique, `acquisition_proposal_id`), moteur + 11 producteurs migrés, lineage (2 endpoints), liaison facture↔immobilisation, garde anti-double, 12 tests PostgreSQL |
| **B1** | `ControlResult` produit par tous les détecteurs ; `ControlCodes`, catégories/gravités alignées ; adaptateurs (Finding, checklist, fraude) ; anti-explosion par agrégation |
| **B2** | V51 (Finding enrichi, index unique actif, `control_run`) ; `FindingService.declarer` (dédup/MAJ/écarté conservé) ; `ControlReconciler` ; `ControlRunService` (+TTL, `REQUIRES_NEW`) ; 7 détecteurs ; `RefusComptablePublisher` ; `DocumentLiaisonService` ; écarter = motif obligatoire |
| **B3** | `RevueComptableService/Controller/Dto` (synthèse, liste filtrée, détail, relance, écarter) ; écran Angular « Révision » ; Inbox cabinet enrichie ; écrans Immobilisations/Tableau de bord adaptés |
| **B4** | `RapprochementClassifier` (EXACT/PROBABLE/AMBIGUOUS/UNMATCHED), `RapprochementAutomatiqueService`, `DetecteurRapprochementBancaire`, import refondu sur le classifieur |
| **B5** | pré-clôture : Findings par `controlSeverity` avec ids/répartition ; point `banque` avec soldes comptable/relevé, écart, lignes non pointées, ids ; passage de contrôles frais à la clôture |

Documents : `accounting-lineage.md`, `control-and-finding-architecture.md`, `accounting-review-inbox.md` (ce rapport).

## 3. Composants historiques supprimés ou remplacés

* `FindingService.signaler` → `declarer` ; `ControlEngine.analyser` → détecteur + `ControlRunService` ;
* convention `evidence.controlSeverity/controlCode`, `ControlResultAdapters.categorieDe` → colonnes dédiées ;
* catégories `FISCAL`/`BANK` ; finder `findByEntrepriseIdAndSourceTypeAndSourceIdAndType` ; constantes `SOURCE_TYPE*` dispersées ;
* `immobilisation.acquisition_ecriture_id` → `acquisition_proposal_id` ;
* heuristique « premier candidat par montant » du rapprochement bancaire.

## 4. Tests

| Suite | Résultat |
|---|---|
| `./mvnw test` | voir §6 (résultat final de cette session) |
| `npm run build` (Angular) | OK |
| `pytest` (ai-service) | **328 passés** |

Nouveaux tests Phase B : `AccountingLineageTest` (12, B0), **`DossierPiloteParExceptionTest`** (4 : scénario global en 22 étapes sur PostgreSQL, refus
d'imputation qui survit au rollback puis se ferme, relevé de 120 lignes → 1 constat, isolation multi-tenant), `RapprochementClassifierTest` (6),
`RefusComptablePublisherTest` (3), `ControlesPerformanceTest` (1). Tests existants adaptés au nouveau contrat (fixtures `Finding`, catégories,
`DossierComptableServiceTest`, `ControlResultTest`, `CopilotServiceTest`, `ControlesClotureTest`, cabinet E2E) ; deux changements de sens justifiés :
un constat fabriqué *sans cause réelle* est désormais fermé par le système (le test cabinet utilise un code hors périmètre des détecteurs), et le point
`banque` compare aussi les soldes.

### Scénario global (22 étapes, PostgreSQL)
entreprise A + PCE → paramétrage → fournisseur → facture d'immobilisation → bien sans source (constat) → liaison **sans seconde écriture**, constat résolu
(SYSTEM) → facture sans pièce (`MISSING_DOCUMENT`) → relevé de 2 transactions sans écriture (2 `UNMATCHED_PAYMENT`) → pré-clôture avec soldes/écart/ids
→ règlement saisi + relance : ligne pointée automatiquement → deux règlements identiques : `AMBIGUOUS_BANK_MATCH`, ligne **non pointée** → pointage
humain, constat résolu → pièce rattachée, `MISSING_DOCUMENT` résolu (historique conservé) → incohérence TVA ouverte puis corrigée → double acquisition
possible → écart sans motif refusé (400), puis écarté avec motif et **non recréé** → relances répétées sans doublon → tuiles/filtres de la revue →
entreprise B isolée (404) → pré-clôture bloquée par un brouillon puis débloquée → audit (`FINDING_CREE`, `FINDING_RESOLU_AUTO`, `FINDING_REJETE`) →
aucune proposition orpheline de sa cause.

## 5. Performance

Passage complet des détecteurs sur un dossier sain : **22 requêtes SQL, ~110 ms, identique pour 5 ou 40 factures justifiées** (pas de N+1, aucune
écriture chargée une par une). Lecture du dossier/revue : dernier passage réutilisé pendant `controles.ttl-secondes` (15 s). Synthèse : 1 `GROUP BY`.
Liste : pagination en base. Détail par détecteur et durée de chaque passage : table `control_run` (`detail_json`).

## 6. Résultat final de la session

Voir la section « Vérification finale » ajoutée en bas de ce fichier à la fin de l'exécution.

## 7. Limites et suites (honnêtes)

* La classification d'un refus du moteur en `ACCOUNT_*` se fait sur le texte de l'explication (messages de `explicationBlocage` et des règles). Un jour,
  `ResultatRegle` devrait porter des **codes structurés** plutôt que des phrases ; un test (`RefusComptablePublisherTest`) fige le comportement actuel.
* Les écrans ne proposent pas encore de **sélecteur de facture candidate** pour « lier à une facture » : l'utilisateur saisit l'identifiant de la source.
* `IN_PROGRESS` existe dans `StatutFinding` mais le parcours ne l'utilise pas (OPEN → RESOLVED / DISMISSED).
* Fenêtre de date de l'EXACT bancaire : 5 jours ; c'est une règle de gestion à valider avec un comptable pilote.
* Détecteurs non faits (hors périmètre, aucune donnée fiable pour les contrôler) : `DUPLICATE_INVOICE` (le doublon de facture reste signalé par l'analyse de
  fraude à l'extraction) ; code réservé dans `ControlCodes`, pas de détecteur.
* La tolérance TVA (0,02) est celle du pipeline agentique ; le flux Documents manuel exige l'égalité exacte.

## Vérification finale

* `./mvnw test` : 632 tests ; un seul passage complet a relevé 2 erreurs de fixture (`InboxServiceTest`, catégorie du Finding), corrigées puis relancées (`InboxServiceTest` vert). Aucune autre erreur ni échec.
* `npm run build` : OK. `pytest` : 328 passés.
