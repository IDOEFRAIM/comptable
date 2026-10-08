# Comptable — Roadmap progressive

> Livrable 4. Séquence ajustée par rapport à la roadmap de la mission, parce que l'audit montre que
> Document Hub, moteur de contrôle et Review Inbox **existent déjà en partie** : on les étend avant d'en
> construire de nouveaux. Après chaque phase : `./mvnw test` + `ng build` ; aucun test supprimé pour
> « faire passer » une nouvelle architecture (les comportements sont migrés explicitement).

## Critère de décision (§32) appliqué à chaque lot
Douleur résolue · temps économisé · fréquence · déjà couvert ? · automatisable avec certitude ? ·
jugement humain requis ? · risque réglementaire ? · explicable au comptable ? Un lot sans gain concret
sur le workflow est dépriorisé.

## Phase 0 — Audit (FAIT)
5 documents `docs/comptable-*.md`. Baseline des tests consignée dans le document d'architecture.

## Phase 1 — Stabiliser le socle (priorité 1, ~2–3 semaines)
| Lot | Contenu | Risque | Test d'acceptation |
|---|---|---|---|
| 1.1 | **Faire passer `ComptabilisationService` par le moteur + `AccountingAccountResolver`** (supprime `4456`/`443`, journaux en dur, contrôles dupliqués). Contrat HTTP et messages inchangés | Élevé (flux UI principal) | Test E2E document → écriture avant/après identique ; TVA non configurée ⇒ message « non configuré » explicite |
| 1.2 | Garde-fou partagé « exercice ouvert » + règle HT+TVA=TTC unique | Faible | Les 4 / 3 copies appellent la même implémentation |
| 1.3 | Hardcodes résiduels : `TiersService`, `DeclarationFiscaleService` → rôles/purposes existants | Faible | Tests existants inchangés |
| 1.4 | `MouvementStockService` / `ImmobilisationService` : écriture via moteur ou `EcritureFactory` | Moyen | Mêmes écritures sur les jeux de test stock/amortissement |
| 1.5 | Brancher `AuditTrailService` sur création/validation d'écriture, comptabilisation, clôture, pointage, lettrage | Faible | Un événement par action critique, tenant renseigné |
| 1.6 | Extraction : jeu de PDF de non-régression (en-têtes de tableau, « Sous-total HT », montants avec espaces) | Faible | Test paramétré sur les 4 PDF d'exemple de `ai-service/` + facture quincaillerie |

## Phase 2 — Contrôles unifiés + pré-clôture (priorité 1)
Remonte avant le Document Hub : c'est la plus grosse douleur (« pourquoi ça ne tombe pas juste ») et
l'infrastructure (`Finding`, Inbox, `ControlItem`) existe.
| Lot | Contenu |
|---|---|
| 2.1 | Contrat `ControlResult` + registre ; adaptateurs pour `ControlEngine`, fraude, readiness (aucun contrôle supprimé) |
| 2.2 | Explications structurées (valeurs chiffrées) affichées dans l'Inbox et le modal de comptabilisation |
| 2.3 | Remplacer **un par un** les 12 `NOT_APPLICABLE` de la clôture, en commençant par : équilibre de la balance, amortissements générés pour l'exercice, rapprochement bancaire fait, soldes de tiers inhabituels. Chaque nouveau contrôle sort d'abord en `WARNING` |
| 2.4 | Nouveaux contrôles déterministes : compte désactivé utilisé, client créditeur / fournisseur débiteur, date hors exercice, écriture sans pièce |
| 2.5 | Inbox : regroupement par type/sévérité (« 12 bloquantes · 27 pièces manquantes… »), actions en lot |

## Phase 3 — Document Hub (extension de `document/`)
| Lot | Contenu |
|---|---|
| 3.1 | `contentHash` SHA-256 + `DUPLICATE_SUSPECTED` à l'upload (migration V49) |
| 3.2 | Statuts Document Hub (`MATCHED`, `MISSING_INFORMATION`, `ARCHIVED`) mappés sur l'existant |
| 3.3 | « Pièce manquante » depuis une **écriture sans pièce** (complète `MissingEvidenceAnalyzer`, aujourd'hui limité aux lignes de relevé) |
| 3.4 | Connecteur e-mail derrière `ingererDepuisCanalExterne` ; hors périmètre : OCR scan lourd, Excel/OFX |
| 3.5 | Résolution de la double représentation facture fournisseur (priorité déjà posée dans `P2_DECISION_MATRIX.md`) |

## Phase 4 — Rapprochement & lettrage
Généraliser par cas (pas de moteur universel) : 1 virement ↔ N factures (état `AMBIGUOUS`/`PROBABLE`),
lien relevé ↔ facture, import OFX/CAMT/Excel en plus du CSV, contrôle « rapprochement fait » alimentant la
clôture (lot 2.3).

## Phase 5 — Assistance à l'imputation
`AccountPurpose` proposé (nature économique) à partir du fournisseur / libellé / historique ; **toujours**
résolu par le résolveur ; `HISTORICAL_SUGGESTION` stocké à part de la configuration ; mesure du taux de
propositions acceptées. Pas de génération libre d'écriture par LLM.

## Phase 6 — Paie / charges sociales (conditionnée à un signal d'usage)
Réconciliation uniquement (journal de paie ↔ comptes 42x/43x ↔ banque ↔ organismes). Reste **bloquée sur
la confirmation du pays pilote et de règles sociales vérifiées**. Aucun moteur de paie.

## Phase 7 — Assistant de pré-clôture et recherche de cause
`EcartExplainer` (drill-down écart → écritures), diagnostic expliqué dans l'écran de clôture, rejeu des
contrôles après correction, réouverture tracée (❓ à vérifier d'abord : aucune réouverture trouvée dans le code).

## Phase 8 — États financiers SYSCOHADA
Table de correspondance comptes → postes **vérifiée par une source réglementaire**, TFT, annexes, contrôles
de cohérence inter-états, validation finale. Bloqué sur la source ; rien n'est inventé sans elle.

## Phase 9 — Optimisation
Apprentissage des habitudes (propositions fréquemment acceptées), automatisation des cas CERTAINS,
indicateurs de §26 sur le tableau de bord qualité existant.

## Ce qui reste explicitement hors périmètre (§27)
Consolidation, analytique avancée, prévision, chatbot généraliste, écriture libre par LLM, paie complète,
microservices, event sourcing, DSL comptable, réécriture du backend.

## Décisions attendues du produit avant la Phase 1
1. Feu vert pour router `ComptabilisationService` via le moteur (lot 1.1) — c'est le seul changement à risque élevé.
2. Pays pilote réel (conditionne TaxRule, paie, charges sociales, annexes).
3. Une des deux entités de proposition doit-elle disparaître, ou garder des rôles distincts ?
4. Suppression des 3 tables mortes : oui / non.
