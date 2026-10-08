# Comptable — Rapport de fin de Phase 1 (unifier le chemin comptable et les contrôles)

> Suite de l'audit (`docs/comptable-product-architecture.md` et suivants). Méthode appliquée à chaque lot :
> CARACTÉRISER → TESTER → ADAPTER → MIGRER → VÉRIFIER. Chemins relatifs à `comptable/`.

## Résultat

| Vérification | Baseline (avant) | Après Phase 1 |
|---|---|---|
| `./mvnw test` | 503 tests, 0 échec | **571 tests, 0 échec, 0 erreur, 0 ignoré** (+68, aucun test supprimé) |
| `npm run build` (Angular) | OK | **OK** |

## 1. Fichiers modifiés / créés

**Flux Documents (lots 1.1 à 1.4)**
- `comptabilite/service/ComptabilisationService.java` — devenu orchestrateur (ne crée plus d'`EcritureComptable`).
- `comptabilite/operation/common/AccountingOperationEngine.java` — variantes `proposerEtValider` / `proposerEtValiderSysteme` avec statut d'écriture cible ; audit ; délègue à `ExerciceOuvertGuard`.
- `comptabilite/operation/achat/AchatRule.java` — `construireLignesAchatDepuisMontantsPrecalcules` (symétrique de `VenteRule`).
- `comptabilite/service/EcritureComptableService.java` — suppression d'un brouillon : supprime aussi la proposition liée (sinon FK pendante et pièce non recomptabilisable).
- `comptabilite/operation/common/repository/AccountingOperationProposalRepository.java` — `findByEcritureId`.

**Contrôles (lots 1.6 à 1.9)**
- Nouveau package `controle/` : `ControlResult`, `ControlSeverity`, `ControlStatus`, `ControlCategory`, `ControlResultAdapters`, `ControlResultPublisher`.
- `agent/finding/domain/TypeFinding.java` — `ACCOUNT_REVIEW`, `BANK_DIFFERENCE`, `DATA_INCONSISTENCY` (colonne `VARCHAR(50)` sans CHECK : aucune migration).
- `comptabilite/service/ExerciceOuvertGuard.java`, `comptabilite/service/CoherenceMontants.java` (mutualisations).
- `agent/pipeline/ConformiteNode.java` — utilise `CoherenceMontants` (tolérance 0,02 conservée).

**Pré-clôture (lots 1.10/1.11)**
- Nouveau package `dossier/controles/` : `ClotureControle`, `ControleEcrituresCloture`, `ControleDocumentsCloture`, `ControleTresorerieTiersCloture`, `ControleActifsFiscalCloture`.
- `dossier/ClotureReadinessService.java` (composition des contrôles), `dossier/web/dto/ControlItem.java` (champs additifs d'explicabilité).
- `comptabilite/repository/EcritureComptableRepository.java`, `document/repository/DocumentRepository.java` (requêtes de contrôle).

**Audit (lot 1.12)**
- `audit/AuditTrailService.java` (+ `enregistrerEvenementSysteme`, `enregistrerRefus` en transaction indépendante), `agent/finding/FindingService.java` (`FINDING_CREE`), `comptabilite/service/ClotureExerciceService.java`.

**Tests ajoutés** : `ComptabilisationDocumentCaracterisationTest` (17), `ComptabilisationDocumentTvaConfigTest` (6), `ControlResultTest` (11), `RegleCommuneControleTest` (6), `ControlesClotureTest` (18), `Phase1PreClotureAuditIsolationTest` (10). Tests existants **migrés explicitement** (fixtures, pas de comportement supprimé) : `ComptabilisationServiceTest` (test de câblage direct → test d'orchestration), `AccountingOperationEngineTest`, `ClotureExerciceServiceTest` (mock d'`AuditTrailService`).

## 2. Architecture avant / après

```
AVANT   Document → ComptabilisationService ──(construit lui-même)──► EcritureComptable (BROUILLON)
                     ├ comptes TVA "4456"/"443" en dur, journal "ACH"/"VTE" par code
                     └ aucune proposition, aucun audit

APRÈS   Document → ComptabilisationService (orchestrateur : validations + choix utilisateur)
                     ├ TVA : AccountingAccountResolver (TVA_RECUPERABLE / TVA_COLLECTEE)
                     │        └ NOT_CONFIGURED / AMBIGUOUS / INVALID → NEEDS_REVIEW → 422 explicite + audit
                     ├ lignes : AchatRule / VenteRule (montants de la pièce, jamais re-calculés)
                     └ AccountingOperationEngine.proposerEtValider(..., BROUILLON)
                           ├ AccountingOperationProposal (source DOCUMENT/documentId, idempotent)
                           ├ journal par type (JournalResolver), exercice ouvert (ExerciceOuvertGuard)
                           └ EcritureComptable BROUILLON + audit (PROPOSITION_*, ECRITURE_CREEE)
```
Contrat REST inchangé (`POST …/documents/{id}/comptabiliser`, mêmes DTO, mêmes codes HTTP, écriture toujours en BROUILLON) : le front Documents n'a pas bougé.

**Décision de conception notable.** Le moteur existant calcule la TVA depuis `TaxRule`, table vide hors règles de démonstration : l'utiliser tel quel aurait fait régresser la comptabilisation de toute facture avec TVA. La TVA d'une facture fournisseur étant un *fait porté par la pièce*, on réutilise le chemin « montants précalculés » déjà employé par `FacturationService` : `TvaRateResolver`/`TaxRule` restent réservés au calcul fiscal, `AccountingAccountResolver` ne décide que du compte.

## 3. Chemins de création d'écriture encore directs

| Producteur | Classement | Raison |
|---|---|---|
| `EcritureComptableService.creer` (saisie manuelle) | **CAS LÉGITIME** | Écriture libre du comptable, pas une opération métier |
| `MouvementStockService.genererEcriture` | **MIGRER PLUS TARD** | Exige un nouveau type d'opération + une règle de mouvement de stock ; écriture VALIDEE immédiate, comptes lus sur `Article` ; tests mock-lourds |
| `ImmobilisationService.genererDotations` | **MIGRER PLUS TARD** | Une écriture multi-immobilisations ; exige un type « dotation d'amortissement » et une règle ; compte de dotation déjà résolu par `AccountingAccountResolver` |
| `AccountingOperationEngine.validerInterne` | (le chemin normal) | — |

Non migrés dans cette phase car le changement n'était pas *local* ni suffisamment couvert (consigne du lot 1.5).

## 4. Hardcodes comptables encore présents

| Où | Valeur | Statut |
|---|---|---|
| `TiersService` | `"411"`, `"401"` (racines de sous-comptes) | à migrer vers `RACINE_CLIENT/FOURNISSEUR` |
| `DeclarationFiscaleService` | `"443"`, `"4456"` (lecture des soldes TVA) | à migrer vers `TVA_COLLECTEE/RECUPERABLE` |
| `MouvementStockService`, `ImmobilisationService` | journal `"OD"` | lié au §3 |
| `AccountingProposalBuilder` | code de journal | à vérifier |
| `EtatsFinanciersService` | préfixes `66`, `77`, `40`, `41` | dépend d'une source SYSCOHADA vérifiée (phase 8) |
| `EntrepriseService` | création des journaux par défaut | **légitime** (amorçage) |

**Supprimés par cette phase** : `4456`, `443`, journaux `ACH`/`VTE`, contrôles dupliqués dans `ComptabilisationService`.

## 5. Contrôles mutualisés

- **« Exercice existant et ouvert »** : 3 copies identiques (moteur, `EcritureComptableService`, `MouvementStockService`) → `ExerciceOuvertGuard` ; la 4ᵉ (dans `ComptabilisationService`) a disparu avec la migration. Même requête, mêmes messages, même 422 (comparés avant extraction).
- **« HT + TVA = TTC »** : l'**arithmétique et le message** sont mutualisés (`CoherenceMontants`) entre `ComptabilisationService` (voie manuelle et automatique) et `ConformiteNode`. La **tolérance reste une politique de l'appelant** (exacte pour les Documents, 0,02 pour le pipeline) car les deux règles n'étaient pas équivalentes. Le message cite désormais toutes les valeurs (« HT + TVA (100000 + 16500 = 116500) ne correspond pas au TTC du document (118000), écart de 1500 »).

## 6. Contrôles restant dupliqués ou volontairement distincts

- `ECART_TVA` (`AnalyseFraudeService`) : **règle différente** (TVA comparée au taux standard × HT), laissée séparée.
- Équilibre débit/crédit : `EcritureComptable.estEquilibree()` est appelé dans 5 services (c'est déjà une méthode du domaine, pas une copie de logique) ; une vérification explicite subsiste dans le moteur.
- `genererNumeroPiece` : copies dans `EcritureComptableService`, `ImmobilisationService`, `MouvementStockService` à côté de `NumeroPieceGenerator` (déjà documenté comme dette).
- Contrôle de nature de compte de `ComptabilisationService` (404/400) vs `CompteComptableValidationService` (422, actif/détail) : sémantiques HTTP différentes, conservées (caractérisées).

## 7. État des contrôles de pré-clôture

17 points au total (le « 16 » de l'audit comptait approximativement ; réel : 5 contrôles + 12 `NOT_APPLICABLE`). **Maintenant : 22 points ; 5 historiques + 5 nouveaux + 9 anciens N/A remplacés par un contrôle réel ; 3 restent N/A.**

| Point | Statuts possibles | Détail |
|---|---|---|
| exercices, brouillons, regularisations, resultat, findings | (inchangés) | historiques |
| `equilibre_ecritures` | OK / **BLOCKING** | écritures dont débit ≠ crédit |
| `comptes_desactives` | OK / WARNING | comptes désactivés portant des lignes |
| `mappings_parametrage` | OK / WARNING | mapping actif vers un compte désactivé |
| `documents_non_comptabilises` | OK / WARNING | factures traitées sans écriture (total TTC cité) |
| `pieces_manquantes` | OK / WARNING | findings `MISSING_EVIDENCE` ouverts |
| `banque` | **N/A** (aucun relevé) / OK / WARNING | lignes de relevé non pointées, par relevé, avec montant |
| `lettrage` | OK / WARNING | lignes de tiers non lettrées (nombre, totaux, tiers) |
| `fournisseurs`, `clients` | OK / WARNING | fournisseur débiteur / client créditeur (signe, pas de seuil) |
| `stocks` | N/A (aucun article) / OK / WARNING | inventaire valide dans l'exercice |
| `amortissements` | N/A (aucune immo) / OK / WARNING | dotation de l'année prévue au plan mais non générée |
| `depreciations`, `provisions` | N/A (aucune) / OK / WARNING | propositions en attente |
| `fiscalite` | N/A (aucune déclaration) / OK / WARNING | déclarations en retard |
| `caisse`, `rapprochement`, `immobilisations` | **N/A** | pas de données permettant un contrôle certain |

Seul `equilibre_ecritures` est BLOCKING (corruption mathématique) ; les nouveaux contrôles sortent en WARNING pour ne bloquer aucune clôture qui passait hier. **Rien n'est « OK » sans donnée** : un point sans objet à contrôler reste `NOT_APPLICABLE` et le dit.

**Explicabilité (1.11)** : chaque `ControlItem` porte `entityType`, `entityId`, `explanation` (valeurs chiffrées), `relatedEntityIds` (plafonné à 100) et `metadata`. Exemple `banque` : « 1 ligne de relevé non pointée sur 1 relevé. 1 relevé sur 2 entièrement rapproché. Montant non pointé : 124500. Compte 521 : 1/2 ligne(s) non pointée(s), 124500. »

## 8. Couverture AuditTrail

Convention du projet : actions en français. Correspondance avec les noms de la mission :

| Mission | Action implémentée | Source |
|---|---|---|
| ACCOUNT_RESOLUTION_FAILED / AMBIGUOUS | `COMPTE_NON_CONFIGURE` / `COMPTE_AMBIGU` / `COMPTE_INVALIDE` (flux Documents) | transaction indépendante (survit au 422) |
| ACCOUNTING_PROPOSAL_CREATED | `PROPOSITION_COMPTABLE_CREEE` (moteur, tous flux) | SYSTEME |
| — | `PROPOSITION_COMPTABLE_A_REVOIR` (explications) | SYSTEME, indépendante |
| ACCOUNTING_PROPOSAL_VALIDATED / ENTRY_CREATED | `PROPOSITION_COMPTABLE_VALIDEE`, `ECRITURE_CREEE` | HUMAIN ou SYSTEME |
| — | `PROPOSITION_COMPTABLE_REJETEE` | HUMAIN |
| FINDING_CREATED | `FINDING_CREE` | SYSTEME |
| FINDING_RESOLVED / DISMISSED | `FINDING_RESOLU` / `FINDING_REJETE` (déjà en place) | HUMAIN |
| CLOSING_CHECK_EXECUTED | `CLOTURE_CONTROLEE` (consultation utilisateur) | HUMAIN |
| CLOSING_STARTED / COMPLETED | `CLOTURE_REFUSEE` (indépendante) / `CLOTURE_TERMINEE` | HUMAIN |

Le moteur étant le point commun, **tous** les flux qui l'utilisent (facturation, avoirs, régularisations, provisions, dépréciations, clôture, IA) sont désormais audités sans autre modification. Non audités : écriture manuelle, mouvements de stock, dotations (cf. §3), pointage/lettrage.

## 9. Tests multi-tenant ajoutés

`Phase1PreClotureAuditIsolationTest` (PostgreSQL réel) : pré-clôture de A ne liste que les documents de A ; utilisateur d'un autre tenant → 404 sur `evaluer` et `cloturer` (et aucun événement d'audit créé) ; événements d'audit et refus limités au tenant concerné ; `Finding` publié visible uniquement dans son entreprise, `resoudre` croisé → 404 ; flux Documents refuse document/tiers/compte d'une autre entreprise (`ComptabilisationDocumentCaracterisationTest`). Les nouvelles requêtes (`rechercherEcrituresDesequilibrees`, `rechercherComptesDesactivesUtilises`, `rechercherNonComptabilises`) sont filtrées par exercice/entreprise ; aucune ne repose sur un `findById` brut.

## 10-11. Résultats

`./mvnw test` : **571 tests, 0 échec**. `npm run build` : **OK**.

## 12. Points « ❓ » de l'audit (lot 1.14)

| Point | Verdict | Preuves |
|---|---|---|
| Réouverture d'un exercice clôturé | **ABSENT** | Seul `POST /exercices/{id}/cloturer` existe (`ReferentielController:61`) ; `ClotureExerciceService.cloturer` est le seul code qui change le statut ; aucun service/endpoint/test de réouverture. Une clôture erronée n'est annulable qu'en base. |
| Mise en service d'une immobilisation | **ABSENT** | `Immobilisation` n'a pas de date de mise en service (seulement `dateAcquisition`) ; le plan démarre à l'acquisition ; `creer` n'écrit que le registre, **aucune écriture d'acquisition**. |
| Cession d'une immobilisation | **PARTIEL** | `StatutImmobilisation.CEDEE` existe mais **aucun code ne l'affecte** (une seule occurrence : la définition) ; ni endpoint, ni écriture de cession, ni plus/moins-value. `genererDotations` filtre sur `EN_SERVICE`. |
| Doublon de facture par numéro | **ABSENT** pour les factures fournisseur | `Document` n'a **aucun champ numéro** et `OcrResult` n'en extrait pas ; seule détection : même TTC dans une fenêtre de dates (`rechercherDoublonsPotentiels`). Les `UNIQUE (entreprise_id, numero)` de `facture` et `facture_fournisseur` portent sur des numéros **générés en interne**, pas ceux du fournisseur. |

## 13. Décisions volontairement NON prises

- **Tables mortes** (`permission`, `role_permission`, `collaborateur_entreprise`, créées en V1) : aucune référence Java ni migration ultérieure trouvée ; **non supprimées**. Reste à prouver qu'aucun besoin RBAC futur ne les justifie. Note de dette, pas de migration.
- **Deux entités de proposition** — documentées, non supprimées :
  - `AccountingProposal` (`agent/comptabilisation`, V24, une par `Document`, statuts PENDING/ACCEPTED/REJECTED, API `…/comptabilisation/propositions/{id}/accepter|rejeter`, usage front : tableau de bord) = **suggestion d'imputation** (quel tiers, quel compte de charge, niveau de confiance) ;
  - `AccountingOperationProposal` (`comptabilite/operation/common`, V34, polymorphe par source) = **lignes d'écriture proposées**.
  Depuis cette phase un même Document peut avoir les deux : la première décide *quoi imputer*, la seconde produit *les lignes*. Leurs rôles sont **complémentaires, pas identiques** : la fusion n'est pas recommandée en l'état ; `AccountingProposal` est le candidat naturel du futur `HISTORICAL_SUGGESTION`.
- **Pays pilote** : aucune règle fiscale nationale ajoutée ; TVA non configurée ⇒ `NOT_CONFIGURED`.

## 14. Risques restants pour la Phase 2

1. **Rebuild obligatoire** du backend pour le comportement décrit (le conteneur actuel exécute l'ancien code).
2. **Effet de bord à connaître** : les factures dont l'entreprise n'a pas de compte de TVA configuré (ni mapping ni rôle système) étaient comptabilisées avec un compte absent → erreur technique ; elles reçoivent maintenant un **422 explicite**. C'est voulu (lot 1.3), mais visible.
3. `EcritureComptableService.supprimer` supprime la proposition du moteur liée : l'historique de cette proposition n'est conservé que dans `audit_log` (`PROPOSITION_COMPTABLE_CREEE`/`ECRITURE_CREEE`). Pas d'événement `ECRITURE_SUPPRIMEE` encore.
4. **Performance** : les contrôles de pré-clôture filtrent en mémoire sur les listes de dépôts existants (relevés, inventaires, provisions, lignes en instance). Acceptable à l'échelle d'un cabinet PME ; à indexer/paginer avant de grandes volumétries.
5. Les nouveaux `TypeFinding` (`ACCOUNT_REVIEW`, `BANK_DIFFERENCE`, `DATA_INCONSISTENCY`) **n'entrent pas encore dans le score de dossier** (`DossierComptableService` pondère par type) : à décider avec la Phase 2.
6. `ControlResultPublisher` est prêt mais **aucun producteur ne l'appelle encore** hors tests : la bascule de `ControlEngine`/fraude vers la publication de `ControlResult` est le premier lot de la Phase 2.
7. Isolation tenant toujours **applicative** (pas de RLS) ; chaque nouvelle table devra reproduire les tests de ce rapport.
8. Migration Stock/Immobilisations (§3) et hardcodes restants (§4) : prochains lots du socle.
