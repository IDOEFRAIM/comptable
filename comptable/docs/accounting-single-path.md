# Comptable — Le chemin comptable unique

> Preuve de la Phase A. Toute écriture créée par une opération métier suit le même chemin :
>
> ```
> SOURCE → RULE → ACCOUNT PURPOSE → RESOLVER → ENGINE → PROPOSAL → ENTRY
> ```
>
> Chemins relatifs à `comptable/src/main/java/com/example/comptable`. Les tests de référence sont cités à la fin.

## 1. Les maillons

| Maillon | Classe | Rôle |
|---|---|---|
| SOURCE | un service métier | décide du **fait économique** (montants, dates, quantités, statut) ; ne construit jamais d'`EcritureComptable` |
| RULE | `comptabilite/operation/**/*Rule` | traduit le fait économique en lignes ; ne choisit jamais un compte lui-même |
| ACCOUNT PURPOSE | `comptabilite/parametrage/domain/AccountPurpose` | vocabulaire des rôles comptables (TVA_RECUPERABLE, CLIENT, STOCK_MARCHANDISE, AMORTISSEMENT…) |
| RESOLVER | `comptabilite/parametrage/AccountingAccountResolver` | purpose + contexte → compte de l'entreprise : explicite → mapping contextuel → mapping générique → rôle système → `NOT_CONFIGURED` ; `AMBIGUOUS` / `INVALID` bloquent |
| ENGINE | `comptabilite/operation/common/AccountingOperationEngine` | proposition, contrôles (exercice ouvert, journal par type, compte utilisable, équilibre), validation, numérotation, idempotence par source, audit |
| PROPOSAL | `AccountingOperationProposal` (+ lignes) | trace de la commande, de sa source, de ses explications |
| ENTRY | `EcritureComptable` | créée uniquement par `AccountingOperationEngine.validerInterne` (et la saisie manuelle, cf. §3) |

Un compte non résolu **ne produit jamais d'écriture** : la proposition passe en `NEEDS_REVIEW` avec l'explication
(`ResultatRegle.explications`), le moteur refuse en 422 (`PROPOSITION_COMPTABLE_A_REVOIR` est audité), et la
transaction de l'opération métier est annulée.

## 2. Chemin de chaque opération

| Opération | Source (déclencheur) | Rule | Purposes demandés | Type d'opération du moteur | Statut de l'écriture |
|---|---|---|---|---|---|
| **ACHAT** (document) | `service/ComptabilisationService#comptabiliser` | `operation/achat/AchatRule#construireLignesAchatDepuisMontantsPrecalcules` | `TVA_RECUPERABLE` ; compte de charge et compte du fournisseur **explicites** (choix utilisateur) | `ACHAT` via `AccountingOperationEngine#proposerEtValider` | BROUILLON |
| **ACHAT** (commande, API) | `stock/service/CommandeAchatService`, `operation/common/web/AccountingOperationController` | `AchatRule#construireMultiLigne` / `#construire` | `ACHAT_*` par nature, `TVA_RECUPERABLE` | `ACHAT` | VALIDEE / sur validation |
| **VENTE** | `service/FacturationService#validerFacture`, `ComptabilisationService` | `operation/vente/VenteRule` | `TVA_COLLECTEE` ; produit explicite | `VENTE` | VALIDEE (facture) / BROUILLON (document) |
| **AVOIR** | `service/FactureAvoirService#validerAvoir`, `stock/service/AvoirFournisseurService` | `VenteRule#construireLignesAvoirVente…`, `operation/avoir/AvoirRule` | `TVA_COLLECTEE` / `TVA_RECUPERABLE` ; compte explicite | `AVOIR_VENTE`, `AVOIR_ACHAT` | VALIDEE |
| **REGLEMENT** | `AccountingOperationController` | `operation/reglement/ReglementRule` | `BANQUE` / `CAISSE` selon le moyen de paiement | `REGLEMENT_CLIENT`, `REGLEMENT_FOURNISSEUR` | VALIDEE à la validation de la proposition |
| **STOCK** (mouvement) | `stock/service/MouvementStockService#comptabiliser` (CUMP calculé par le module Stock) | `operation/stock/StockMouvementRule` + `StockAccountResolver` | `STOCK_MARCHANDISE` / `STOCK_MATIERE` / `STOCK_PRODUIT` / `STOCK_AUTRE` (selon `TypeArticle`), `VARIATION_STOCK` ; comptes explicites de l'article en premier palier | `STOCK_ENTREE`, `STOCK_SORTIE`, `STOCK_AJUSTEMENT` | VALIDEE |
| **STOCK** (inventaire) | `stock/inventaire/service/InventaireService#valider` | `operation/inventaire/InventaireRegularisationRule` + `StockAccountResolver` | idem | `REGULARISATION_INVENTAIRE` (idempotent par inventaire) | VALIDEE à la validation de la proposition |
| **IMMOBILISATION** (acquisition) | `service/ImmobilisationService#comptabiliserAcquisition` | `operation/immobilisation/ImmobilisationRule#construireAcquisition` | `IMMOBILISATION_CORPORELLE` / `_INCORPORELLE`, `FOURNISSEUR`, `TVA_RECUPERABLE` | `IMMOBILISATION_ACQUISITION` | VALIDEE |
| **AMORTISSEMENT** | `service/ImmobilisationService#genererDotations` (montant calculé par `PlanAmortissementCalculator`) | `ImmobilisationRule#construireDotation` | `DOTATION_AMORTISSEMENT`, `AMORTISSEMENT` (explicite de l'immobilisation) | `AMORTISSEMENT_DOTATION` (une écriture par immobilisation et par année) | VALIDEE |
| **PROVISION** | `comptabilite/provision/service/ProvisionService` | `operation/regularisation/ProvisionRule` | comptes **explicites** (dotation, provision, reprise) | `PROVISION` | VALIDEE à la validation |
| **DEPRECIATION** | `comptabilite/depreciation/service/DepreciationService` | `operation/regularisation/DepreciationRule` | comptes **explicites** | `DEPRECIATION` | VALIDEE à la validation |
| **REGULARISATION** | `comptabilite/regularisation/service/RegularisationService` | `operation/regularisation/RegularisationRule` | comptes **explicites** | `REGULARISATION`, `REGULARISATION_REPRISE` | VALIDEE à la validation |
| **CLOTURE** | `service/ClotureExerciceService#cloturer` | `operation/cloture/ClotureRule` | `RESULTAT_BENEFICE` / `RESULTAT_PERTE` | `CLOTURE_EXERCICE` (idempotent par exercice) | VALIDEE |

> Provision, dépréciation, régularisation et avoir ne demandent pas de purpose : l'utilisateur **désigne** le compte
> dans la commande. Ce compte passe par `CompteComptableValidationService#chargerCompteUtilisable` — exactement le
> contrôle que le résolveur applique à son premier palier (« compte explicite ») : actif, de l'entreprise, utilisable en
> détail. Il n'y a donc ni choix arbitraire ni repli.

## 3. Les seuls accès directs aux écritures

| Accès | Où | Légitimité |
|---|---|---|
| Création par le moteur | `AccountingOperationEngine#validerInterne` | **le** chemin normal |
| **Saisie manuelle** | `service/EcritureComptableService#creerManuelle` | cas métier assumé (écriture libre du comptable). Mêmes garde-fous : appartenance à l'entreprise (`verifierAcces`), exercice ouvert (`ExerciceOuvertGuard`), compte actif et utilisable en détail, tiers de l'entreprise, équilibre débit/crédit, BROUILLON puis validation explicite ; audit `ECRITURE_MANUELLE_CREEE` / `ECRITURE_VALIDEE` / `ECRITURE_SUPPRIMEE` |
| Lettrage | `service/LettrageService` | met à jour le champ `lettrage` de lignes existantes, ne crée aucune écriture |
| Suppression d'un brouillon | `EcritureComptableService#supprimer` | supprime aussi la proposition du moteur liée (le document peut être recomptabilisé) |
| Lecture / états / export | `*Repository` en lecture, `EtatsFinanciersService`, `GrandLivreService`, `ExportFecService` | consultation uniquement |

## 4. Vérification mécanique

```bash
# Aucun producteur d'écriture hors moteur et saisie manuelle (doit renvoyer 2 fichiers)
grep -rlE "new EcritureComptable\(|ecritureComptableRepository\.save" comptable/src/main/java
#   comptabilite/operation/common/AccountingOperationEngine.java
#   comptabilite/service/EcritureComptableService.java

# Aucun numéro de compte dans le code opérationnel : seuls 2 commentaires de documentation ressortent (les classes SYSCOHADA du reporting sont des préfixes, cf. rapport Phase A)
grep -rnE '"(401|411|443|4456|6031|311|681|281|521|571|120|129)"' comptable/src/main/java
```

Le test `CoeurComptableUniqueTest` (PostgreSQL réel) le prouve à l'exécution : après un scénario achat, vente, règlement,
stock, inventaire, acquisition, mise en service, dotation et déclaration de TVA, **chaque** `EcritureComptable` de l'entreprise
possède exactement une `AccountingOperationProposal` d'origine et est équilibrée.

## 5. Tests de référence

| Garantie | Test |
|---|---|
| chemin unique, audit, isolation, rejeu, NOT_CONFIGURED puis reconfiguration | `comptabilite/CoeurComptableUniqueTest` |
| comportement historique du flux Documents préservé | `comptabilite/ComptabilisationDocumentCaracterisationTest` |
| compte de TVA par résolveur, refus propres | `comptabilite/ComptabilisationDocumentTvaConfigTest`, `AchatRuleTest`, `VenteRuleTest` |
| règles de stock par purpose | `operation/stock/StockRulesTest`, `stock/service/MouvementStockServiceTest` |
| règles d'immobilisation et cycle de vie | `operation/immobilisation/ImmobilisationRuleTest`, `service/ImmobilisationServiceTest` |
| résolveur et ses paliers | `parametrage/AccountingAccountResolverTest` |
