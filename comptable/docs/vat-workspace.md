# Espace TVA (Phase G — G1)

La TVA est **calculée à partir de la comptabilité** avec les règles d'un cadre fiscal (voir `fiscal-core.md`). La comptabilité n'est **jamais modifiée** : une correction fiscale est une décision ou un ajustement justifié, tracé.

## 1. Audit initial (existant réutilisé / manquant)

| Existant | Utilisation |
|---|---|
| `AccountingAccountResolver` (purposes `TVA_COLLECTEE`, `TVA_RECUPERABLE`) | comptes de TVA de l'entreprise : jamais un numéro supposé |
| `Facture`, `LigneFacture.tauxTva`, `FactureAvoir` | HT de la facture liée à l'écriture (contrôle `VAT_BASE_MISMATCH`), numéro de document |
| `Tiers`, `Journal`, écritures `VALIDEE` | tiers, journal et pièce de l'opération |
| `Finding` + `ControlResultPublisher` | anomalies fiscales (aucune nouvelle table d'alertes) |
| `DossierEcheance` (Cabinet Workspace) | échéances de déclaration, générées **seulement** par une règle d'échéance d'un cadre VERIFIED |
| `AuditTrailService`, `DossierRoles`, `EntrepriseAccessService` | audit, droits, isolation |
| `TaxRule` (table vide par conception), `TvaRateResolver` (taux d'une facture) | **non utilisés** par ce moteur : le taux vient exclusivement du cadre fiscal |
| `DeclarationFiscale` (suivi d'échéances hérité) | conservé ; l'ancien `EcheancierTvaGenerator` (« 15 du mois suivant », convention non vérifiée) est **supprimé** : les échéances ne viennent plus que d'un cadre VERIFIED (`TaxDeadlineGenerator`) |
| `DeclarationFiscaleService.calculerTva` (mouvement net des comptes) | conservé tel quel (suivi d'échéances) ; l'espace TVA ajoute la classification, les contrôles et le cycle de revue |

Manquait : cadre fiscal versionné, période déclarative, classification, ajustements, contrôles fiscaux, déclaration, revue, snapshot, espace dédié.

## 2. Opérations et calcul (`VatOperationExtractor`, `VatEngine`)

* **Opération** = une écriture validée qui touche un compte de TVA de l'entreprise, sur la période. Une seule requête SQL agrège toutes les écritures (pas de N+1) : TVA collectée (crédit − débit), TVA récupérable (débit − crédit), total de l'écriture, tiers, document, HT de la facture liée.
* **Base** = total de l'écriture − |TVA| (signe repris : les avoirs sont négatifs).
* **Taux implicite** = TVA / base. Avec un cadre **VERIFIED** : le taux du cadre le plus proche est retenu si l'écart est ≤ `rateMatchTolerancePoints`, sinon `TAX_RATE_UNRESOLVED` (jamais une approximation).
* **TVA calculée** = base × taux du cadre (arrondi 2 décimales, `HALF_UP`) ; écart avec la TVA comptabilisée > `toleranceAmount` ⇒ `VAT_AMOUNT_MISMATCH`, sauf s'il est **expliqué** par un ajustement lié à l'opération (ou une décision `EXEMPT` / `OUT_OF_SCOPE`).
* **TVA déductible ≠ TVA comptabilisée** : `NON_DEDUCTIBLE` (0), `PARTIALLY_DEDUCTIBLE` (part saisie et justifiée), `INPUT_TAX` (intégral).
* **Sans cadre vérifié** : aucun montant fiscal n'est calculé ; les montants **comptabilisés** restent affichés, marqués « non déclarables » ; la finalisation est impossible.
* **Opération non déterminable** (touche les deux comptes de TVA) : `TO_REVIEW` + constat ; exclue des totaux tant qu'elle n'est pas décidée ; affichée dans Ventes **et** Achats.
* **Rapprochement** grand livre ↔ opérations (mouvement des deux comptes de TVA) : un écart ⇒ `TAX_PERIOD_INCOMPLETE`. Écart non expliqué = somme des écarts sans décision ni ajustement.
* Tout en `BigDecimal` ; Angular n'effectue aucun calcul fiscal.

## 3. Espace TVA (`/entreprises/:id/fiscalite/tva`)

Onglets : **Vue d'ensemble** (TVA collectée, TVA déductible, ajustements, solde net, opérations à revoir, rapprochement, ce qui bloque la finalisation) · **Ventes** · **Achats** (tableaux paginés côté serveur ; décision fiscale avec justification, origine de l'opération) · **Ajustements** (genre, effet, montant, justification, opération concernée ; annulation, jamais suppression) · **Contrôles** (inbox des anomalies) · **Déclaration** (lignes, base fiscale ou comptabilisée, structure officielle ou générique, revue, snapshot).

Cycle : collaborateur ou expert → *Marquer prête pour revue* (refusé s'il reste une opération à revoir ou une anomalie) ; **expert** → *Marquer comme revue* → *Finaliser* (confirmation d'immuabilité). FINAL exige : cadre **VERIFIED**, période revue, aucun constat, aucun écart inexpliqué, montants fiscaux complets ; il crée un **snapshot immuable** (cadre, version, hash, période, montants, lignes, ajustements, contrôles, relecteur, date) et résout les constats. Une période FINAL est relue depuis son snapshot et refuse toute modification.

## 4. Intégration au Cabinet Workspace

* **File « À traiter »** : les anomalies fiscales (domaine « Fiscalité », impact « À corriger avant la déclaration ») et « Déclaration TVA prête pour revue » (impact « À revoir par l'expert ») ; ouverture directe de l'espace TVA.
* **Portefeuille** : une seule colonne **TVA** (À préparer / À revoir / Prête / Finalisée) ; les constats fiscaux ne changent pas le statut comptable du dossier.
* **Échéances** : une échéance « Déclaration fiscale » est créée à l'ouverture d'une période **uniquement** si un cadre VERIFIED fournit une règle d'échéance ; sinon aucune date n'est inventée. Marquée « faite » à la finalisation.

## 5. API (`/api/entreprises/{id}/fiscalite/tva`, collaborateur et expert ; client 403 ; autre cabinet 404)

`GET /periodes?statut&frameworkVersion` · `POST /periodes` · `GET /periodes/{p}` · `GET /periodes/{p}/operations?cote&statut&traitement&constatActif&dateDebut&dateFin&q&tri&sens&page&taille` (pagination, recherche, filtres et tri **en SQL**) · `GET …/operations/{e}/origine` · `PUT …/operations/{e}/traitement` · `POST …/operations/traitement-groupe` · `GET/POST …/ajustements`, `POST …/ajustements/{a}/annuler` · `GET …/controles` · `POST …/recalculer|preparer|revoir|rouvrir|finaliser` (corps facultatif `{expectedVersion}`) · `GET …/declaration` · `GET …/snapshot` · `GET/POST …/paiements`, `POST …/paiements/{id}/annuler` · `GET/PUT …/credit-ouverture`. Toute mutation utilisateur **exige** `expectedVersion` (422 si absent, 409 si périmé ; `recalculer` reste facultatif).

## 6. Performance (`VatPerformanceTest`)

Le **nombre de requêtes est identique** de 100 à 10 000 puis à 50 000 opérations (aucun N+1).

| Appel | JDBC / Hibernate | 10 000 | 50 000 |
|---|---|---|---|
| Synthèse de période | 35 / 9 | 279 ms | 1 485 ms |
| Page de 50 lignes (SQL `LIMIT/OFFSET`) | 14 / 9 | 167 ms | 497 ms |
| Page profonde (page 99) | 14 / 9 | 171 ms | 494 ms |
| Recherche | 14 / 9 | 158 ms | 1 497 ms |
| Filtre TO_REVIEW | 4 / 9 | 111 ms | 421 ms |
| Recalcul (calcul + constats) | 64 / 16 | 703 ms | 3 077 ms |
| File de travail du cabinet | 5 / 0 | 21 ms | 18 ms |

La liste est paginée **en base** (filtres, recherche, tri, `LIMIT/OFFSET` ; seule la page est lue) ; le recalcul charge encore toute la période pour les totaux. Temps indicatifs.

## 7. Tests

Backend : `VatEngineTest` (13), `VatOperationalStatusEvaluatorTest` (6), `TaxDeadlineGeneratorTest` (5), `VatWorkspaceIntegrationTest` (11), `VatG3AInfrastructureTest` (10), `VatHttpTest`, `VatG3AHttpTest`, `VatPerformanceTest`. Angular : `tva.component.spec.ts` (27). Playwright : `tva.spec.ts` (collaborateur, expert, anomalie), `tva-g3a.spec.ts` (recherche / filtres / validation en masse / cadre non vérifié ; paiement partiel, complet, annulation).

## 8. Ce qui reste non vérifié (bloquants réglementaires)

* **Aucun cadre fiscal réel** : taux, périodicité, échéances légales, cases officielles et exceptions pays ne sont renseignés pour aucune juridiction. Tant qu'aucun cadre VERIFIED (avec sources) n'existe, aucune déclaration ne peut être FINAL.
* Les montants calculés sans cadre vérifié sont **comptabilisés, non déclarables**.
* La structure de déclaration est **générique et non officielle** tant que le cadre ne fournit pas ses lignes.
* Pas de gestion du cadre dans l'interface (API seulement) ; pas de télédéclaration, pas de paiement, pas d'autre taxe (IS, retenues, patente…).

## 9. Gate

| Composant | Verdict |
|---|---|
| **Fiscal Core** | **READY** |
| **VAT Engine** (calcul, classification, ajustements, contrôles, cycle, snapshot) | **READY** (testé avec des cadres fictifs) |
| **VAT Workspace** (interface, Cabinet Workspace, échéances conditionnelles) | **READY** (gestion des cadres par API seulement) |
| **Regulatory Pack** | **BLOCKED** : aucune source officielle vérifiée ; la TVA réglementaire est donc **PARTIAL_READY** |

## 10. Infrastructure G3A

Voir `fiscal/g3a-infrastructure-report.md`. En résumé :

* **Statut opérationnel et progression** calculés par `VatOperationalStatusEvaluator` (A_PREPARER … CREDIT_A_REPORTER ; 8 étapes `readySteps / totalSteps / blockingSteps`) ; l'écran les affiche.
* **Cadre non vérifié** : bandeau sobre « Cadre réglementaire : non vérifié — cette période peut être préparée et contrôlée, mais elle ne peut pas être finalisée tant qu'un cadre réglementaire vérifié n'est pas installé » ; `finalizable = false`, `finalisationReasons = [REGULATORY_PACK_NOT_VERIFIED]`.
* **Listes** (ventes, achats) : pagination serveur, recherche, filtres (traitement, à revoir, constat actif), tri, sélection et **validation en masse** revalidée par le serveur (tout ou rien).
* **Règlement interne** (période FINAL) : solde déclaré, payé, reste à payer, historique ; paiement partiel, clé d'idempotence, annulation tracée ; aucun paiement en ligne.
* **Crédit** : ouverture saisie et justifiée (jamais reprise automatiquement), clôture informative, état « à revoir » sans règle de report vérifiée.
* **Conflit 409** : message « modifiée par un autre utilisateur — rechargez » et bouton de rechargement ; jamais d'écrasement silencieux.
* Gates : VAT Workspace Infrastructure **READY** ; BF Regulatory Pack **BLOCKED**.

## 11. G3A.1 — closeout

* **Version** : `tax_period.version` incrémentée une fois par mutation fonctionnelle ; l'écran envoie toujours la version lue, met son état à jour avec la réponse (N+1) et, après un 409, affiche « Cette période a été modifiée par un autre utilisateur. Rechargez les dernières données avant de poursuivre. » avec un bouton **Recharger** (aucun rechargement ni rejeu silencieux).
* **Déclaration / règlement** : statuts distincts ; après FINAL, seules les opérations de règlement restent possibles ; le snapshot n'est jamais modifié par un paiement.
* **Tests** : `VatG3A1HardeningTest` (11, transactions concurrentes réelles), `VatG3AHttpTest` (422 / 409 / double finalisation), `CabinetClockTest`, Playwright C (conflit 409).

* **Performance 50 000 opérations (après G3A.1)** : synthèse 1,2 s, page 0,7 s, recherche 1,1 s, filtre à revoir 0,9 s, recalcul 2,6 s, file de travail 20 ms, à nombre de requêtes constant ; un plan générique PostgreSQL (BitmapAnd répété) coûtait 29 s sur le mouvement de compte avant l'index composite `idx_ligne_ecriture_compte_ecriture` (V68). Détail : `fiscal/g3a-infrastructure-report.md`.
