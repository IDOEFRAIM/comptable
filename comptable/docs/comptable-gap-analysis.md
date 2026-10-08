# Comptable — Gap analysis produit

> Livrable 2. Complexité : S (≤ 3 j) · M (≤ 10 j) · L (> 10 j) — ordres de grandeur, pas un chiffrage projet.
> Valeur = gain de temps comptable × fréquence. Priorité 1 = à faire d'abord.
> Ce tableau n'est **pas** une mesure d'usage : aucune donnée terrain n'existe encore (cf. `P1_9_PILOT_PROTOCOL.md`).

## 1. Douleur comptable → capacité → manque

| # | Douleur | Capacité existante | Manque | Complexité | Valeur | Priorité |
|---|---|---|---|---|---|---|
| 1 | Collecte des pièces | Upload web, WhatsApp, `DemandePiece` + relance planifiée | Canal e-mail ; import par lot ; portail client dédié ❓ | M | Élevée | 3 |
| 2 | Classement / archivage | `Document` + métadonnées + statut | **Hash de contenu** (doublon de fichier), classification automatique du type, archivage immuable/rétention | S–M | Élevée | 1 |
| 3 | Pièces manquantes | `MissingEvidenceAnalyzer` (depuis relevés), `DemandePiece`, `Finding(MISSING_EVIDENCE)` | Détecter la pièce manquante **depuis une écriture sans pièce** (aujourd'hui seule la ligne de relevé déclenche) | M | Élevée | 2 |
| 4 | Imputation / choix du compte | `AccountPurpose` + `AccountingAccountResolver` (statuts AMBIGUOUS/NOT_CONFIGURED), `AccountingProposalBuilder` (historique) | Flux document → écriture **hors résolveur** (hardcodes 4456/443) ; pas de nature économique intermédiaire ; historique non séparé de la config | M | Très élevée | 1 |
| 5 | Contrôle de masse | `ControlEngine` (3 contrôles), `AnalyseFraudeService`, `Finding` + Inbox | Pas de moteur unique ni de format de résultat commun ; ni statistique (montant vs historique) ni compte inhabituel / désactivé utilisé / tiers à solde inversé | M–L | Très élevée | 1 |
| 6 | Rapprochement bancaire | `RapprochementBancaireService`, `LettrageService`, `agent.matching` | Import CSV seul ; pas de match **multiple/regroupé** (1 virement = N factures) explicite ; pas de lien relevé↔facture | M | Élevée | 2 |
| 7 | Lettrage clients/fournisseurs | `LettrageService` (manuel + auto, partiel) | Case « lettrage » de la checklist de clôture = NOT_APPLICABLE ; pas d'écran de revue des ambigus au-delà de `LETTRAGE_AMBIGU` | S | Moyenne | 3 |
| 8 | Paie | **Aucune** | Journal de paie, comptes salariés (42x), acomptes, organismes sociaux (43x) | L (réconciliation seule : M) | Élevée si demandée ; **aucun signal d'usage** | 5 |
| 9 | Charges sociales | `TypeImpot.CNPS` + `PaiementFiscal` (paiement), **pas de réconciliation** | Rapprocher dette sociale ↔ règlement bancaire ; règles par pays non vérifiées | M | Moyenne | 5 |
| 10 | Pré-clôture | `ClotureReadinessService` (4 contrôles réels / 16) | 12 contrôles `NOT_APPLICABLE` : équilibre balance, soldes tiers inhabituels, amortissements générés, inventaire fait, TVA, provisions, rapprochement bancaire fait | M | Très élevée | 1 |
| 11 | Recherche d'anomalie / « pourquoi ça ne tombe pas juste » | Grand livre, balance, `Finding.evidence`, Copilot (lecture seule, preuves citées) | **Drill-down** écart → écritures sources ; explication structurée d'un écart (HT/TVA/TTC chiffré) hors fraude | M | Très élevée | 2 |
| 12 | États financiers | Bilan + compte de résultat par préfixes en dur | **TFT**, correspondance comptes → postes SYSCOHADA vérifiée, contrôles de cohérence, validation finale | L | Élevée | 4 |
| 13 | Annexes | **Aucune** | Notes annexes SYSCOHADA (réglementaire, source à vérifier) | L | Moyenne | 4 |

## 2. Contrôles existants — inventaire (§11 de la mission)

| Contrôle | Où | Quand | Sévérité | Bloquant |
|---|---|---|---|---|
| Écriture équilibrée | `EcritureComptable.estEquilibree` appelée dans moteur, `ComptabilisationService`, `EcritureComptableService`, `ImmobilisationService`, `MouvementStockService` | à la création | erreur | oui (exception) |
| Exercice existant et ouvert | **4 copies** : moteur, `ComptabilisationService`, `EcritureComptableService`, `MouvementStockService` | à la création | erreur | oui (422) |
| HT + TVA = TTC | `ComptabilisationService`, `AnalyseFraudeService` (ECART_TVA), `ConformiteNode` | comptabilisation / analyse | erreur / alerte | oui / non |
| Montant rond suspect | `AnalyseFraudeService` | à l'import | alerte | non |
| Doublon (même montant, fenêtre 3 j) | `AnalyseFraudeService` | à l'import | CRITIQUE | non (met en validation humaine) |
| Doublon de facture (numéro/fournisseur) | ❓ non trouvé | — | — | — |
| Compte actif / bonne nature | `CompteComptableValidationService`, `chargerCompteChargeOuProduit`, `AccountingAccountMappingService` | création mapping / comptabilisation | erreur | oui |
| Compte résolu | `AccountingAccountResolver` (4 statuts) | proposition | explication | oui (proposition incomplète) |
| Stock disponible | `MouvementStockService` + `PolitiqueStockNegatif` | mouvement | erreur | selon politique |
| Idempotence | moteur, `FacturationService`, `FindingService`, `MouvementStockRepository` | création | — | oui (retourne l'existant) |
| Déclarations TVA en retard | `ControlEngine` | lecture dossier | Finding `TAX_REVIEW` | non |
| Document à fraude critique / faible confiance | `ControlEngine` | lecture dossier | Finding `DOCUMENT_REVIEW` | non |
| Brouillon ancien (> 30 j) | `ControlEngine` | lecture dossier | Finding `ECRITURE_A_CONTROLER` | non |
| Brouillons / régularisations / findings avant clôture | `ClotureReadinessService` | clôture | BLOCKING / WARNING | oui |
| Régularisation / provision / dépréciation cohérentes | `*Rule` + services | création | erreur | oui |

Constat : les contrôles sont **dispersés mais de bonne qualité**. Trois « familles » coexistent —
contrôles *bloquants à la création* (exceptions HTTP), *constats persistés* (`Finding`), *checklist de
clôture* (`ControlItem`). Elles n'ont **pas de format commun** (message libre / `Finding` / `ControlItem`).

## 3. Duplications et hardcodes

| Sujet | Occurrences | Action proposée (cf. architecture cible) |
|---|---|---|
| « exercice clos / absent » | 4 | extraire un service `ExerciceOuvertGuard` |
| HT + TVA = TTC | 3 | une seule règle exposée, réutilisée |
| `COMPTE_TVA_*` en dur | `ComptabilisationService`, `DeclarationFiscaleService` | passer par `AccountingAccountResolver` (TVA_COLLECTEE / TVA_RECUPERABLE existent déjà) |
| `COMPTE_RACINE_*` | `TiersService` | rôle système existant `RACINE_CLIENT/FOURNISSEUR` |
| Journaux `"OD"`, `"ACH"`, `"VTE"` par code | stock, immobilisation, comptabilisation | `JournalResolver` (existe déjà) |
| Préfixes de classe dans états financiers | `EtatsFinanciersService` | table de correspondance versionnée (source réglementaire à vérifier) |
| 2 entités de proposition | `AccountingProposal` / `AccountingOperationProposal` | décision à prendre (fusion ou rôle clair) |
| 2 factures fournisseur | `Document` vs `facture_fournisseur` | déjà classé priorité 1 dans `P2_DECISION_MATRIX.md` |
| Tables mortes | `permission`, `role_permission`, `collaborateur_entreprise` | suppression sur décision explicite |

## 4. Capacités réutilisables (à ne pas recréer)
`AccountingOperationEngine`, `AccountingAccountResolver`, `AuditTrailService`, `Finding` + `InboxService`,
`ControlEngine`, `AnalyseFraudeService`, `MatchDecisionEngine`, `LettrageService`,
`RapprochementBancaireService`, `Document` + pipeline + checkpoint JDBC, `DemandePiece` + relance,
`ClotureReadinessService`, `Copilot` (+ `EvidenceRefDto`), `CashForecast`/`BalanceAgeeService`.

## 5. Risques identifiés
1. Corriger `ComptabilisationService` sans test E2E du flux UI → régression sur l'écran Documents.
2. Rendre un nouveau contrôle bloquant d'emblée → blocage de dossiers existants (déployer en WARNING d'abord).
3. Extraction heuristique : fragile sur les mises en page non vues (bug réel 2026-10-01).
4. Isolation tenant **applicative** : chaque nouvelle table/requête doit être testée contre la fuite.
5. Réglementaire : TFT, annexes, TaxRule par pays, paie, charges sociales → **ne rien inventer** sans source vérifiée.
