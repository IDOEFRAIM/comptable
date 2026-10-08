# Comptable — Cartographie des workflows actuels

> Livrable 1 (suite). Format demandé : ENTRÉE → SERVICE/RULE → VALIDATION → COMPTE → ENGINE → ÉCRITURE → AUDIT.
> Chemins relatifs à `comptable/src/main/java/com/example/comptable`. Légende : ✅ vérifié dans le code ·
> ⚠️ écart notable · ❓ non vérifié en détail (à confirmer avant de s'y appuyer).

## A. Réception d'une pièce → écriture (le flux réellement utilisé par l'UI Documents)

```
ENTRÉE      Upload web (DocumentController) | WhatsApp (WhatsAppInternalIngestionController)
            | autre canal externe (DocumentService.ingererDepuisCanalExterne)
   ↓
SERVICE     DocumentService.uploader → stockage LocalDocumentStorageService (fichier sur disque)
            → OcrExtractionService (Heuristique PDFBox+regex  OU  AiService/VLM selon agent.vlm.enabled)
   ↓
VALIDATION  AnalyseFraudeService (TVA, montant rond, doublon sur fenêtre 3 j) → Document.rapportFraude
            Statut : EN_ATTENTE → TRAITE  |  EN_ATTENTE_VALIDATION_HUMAINE  |  ERREUR
            (pipeline agent.pipeline : extraction → fraude → conformité → validation humaine → écriture)
   ↓
VALIDATION HUMAINE   Écran « validation » (inbox-validation) : corriger HT/TVA/TTC/date, valider
   ↓
COMPTE      Choix manuel du tiers + compte de charge/produit dans le modal « Comptabiliser »
            ⚠️ TVA : compte lu par numéro en dur ("4456" achat / "443" vente) dans ComptabilisationService
   ↓
ENGINE      ⚠️ AUCUN : ComptabilisationService construit l'EcritureComptable lui-même
            (≠ AccountingOperationEngine). Écriture créée en BROUILLON.
   ↓
ÉCRITURE    Journal ACH/VTE (par code en dur), numéro de pièce = compteur journal + 1
   ↓
AUDIT       ⚠️ Aucun événement audit_log (seulement createdBy sur l'écriture)
```
Contrôles à la comptabilisation (`ComptabilisationService.comptabiliser`) : HT et TTC présents ; HT + TVA = TTC ;
date de pièce présente ; tiers du bon type ; compte de la bonne nature ; exercice existant et OUVERT ;
journal présent ; écriture équilibrée. Tous renvoient un 422 avec message explicite — **le front
n'affichait pas ce message** (corrigé : `core/forms/message-erreur.ts`).

**Variante IA** (flags désactivés par défaut) : `AiAccountingPostingService` → moteur
(`proposerEtValiderImmediatementSysteme`) + `AccountingAccountResolver`. C'est donc le *seul* chemin
document → écriture qui respecte l'architecture cible.

## B. Achat

```
ENTRÉE      (1) Document FACTURE_ACHAT (flux A)   (2) CommandeAchat (stock/CommandeAchatService)
            (3) API moteur POST /operations/achat/proposition
   ↓
RULE        AchatRule (multi-lignes, frais accessoires, réductions, natures MARCHANDISE/MATIÈRE/CONSOMMABLE/SERVICE)
VALIDATION  ResultatRegle.explications (manque → explication, jamais un compte deviné) ; TvaRateResolver
COMPTE      AccountingAccountResolver (mapping entreprise → repli rôle système)
ENGINE      AccountingOperationEngine.proposerAchat → AccountingOperationProposal → valider
STOCK       Si ligne stockable : MouvementStockService (CUMP) ⚠️ écriture OD créée directement (cf. §E)
ÉCRITURE    Journal résolu par JournalResolver (type ACHATS)
AUDIT       createdBy/validatedBy sur la proposition ; ⚠️ pas d'audit_log
RÈGLEMENT   cf. §D
```
⚠️ L'entrée (1) ne passe **pas** par `AchatRule` : seuls (2) et (3) utilisent le moteur.

## C. Vente / facturation

```
ENTRÉE      FacturationService.creer (Facture + lignes)  → émission
RULE        VenteRule (natures, réductions, TVA via TvaRateResolver)
ENGINE      accountingOperationEngine.proposerEtValiderImmediatement(VENTE)   ✅ (FacturationService:188)
COMPTE      AccountingAccountResolver
ÉCRITURE    Journal VENTES ; idempotence par (facture, source)
STOCK       Sortie de stock à l'émission (facturation_stock_integration, migration V20)
AVOIR       FactureAvoirService → engine (AVOIR_VENTE) ✅ ; AvoirFournisseurService → engine ✅
RÈGLEMENT   cf. §D
```

## D. Règlement / rapprochement

```
ENTRÉE      (1) POST /operations/reglement/proposition  (ReglementRule, MoyenPaiement)
            (2) Import de relevé CSV (RapprochementBancaireService.importer — CSV uniquement)
RULE        ReglementRule : refuse montant négatif ; compte de trésorerie via rôle TRESORERIE_BANQUE/CAISSE
ENGINE      proposerReglement ✅
RAPPROCHEMENT
  facture ↔ règlement      LettrageService (lettrage partiel, V17) — EXISTE
  banque ↔ écriture        RapprochementBancaireService.pointer/depointer + rapprocherAutomatiquement — EXISTE
                           + agent.matching (MatchDecisionEngine : AUTO ≥ 0,95 / REVIEW ≥ 0,70 / NO_MATCH) — EXISTE, IA off par défaut
  banque ↔ facture         PARTIEL (via l'écriture, pas de lien direct relevé↔facture)
  banque ↔ tiers           PARTIEL (matching IA et libellés seulement)
  client / fournisseur     LettrageService — EXISTE
  paie ↔ banque, charges sociales ↔ banque   ABSENT
AUDIT       Pas d'audit_log sur pointage/lettrage
```
Un relevé alimente aussi `MissingEvidenceAnalyzer` : `NO_MATCH` → `Finding(MISSING_EVIDENCE)`,
`MATCH_REVIEW` → `Finding(MATCH_AMBIGUOUS)` ; `WhatsAppResolutionService` / `RelancePieceService`
déclenchent une `DemandePiece` (jamais sans décision humaine pour l'envoi de recouvrement).

## E. Stock

```
ENTRÉE      MouvementStockController (entrée, sortie, ajustement), InventaireController
SERVICE     MouvementStockService (620 l.) : CUMP via PumpCalculator ; politique de stock négatif
            (PolitiqueStockNegatif) → StockInsuffisantException (422)
COMPTE      ⚠️ comptes lus sur l'Article (compteStock / compteVariation), pas via AccountingAccountResolver
ENGINE      ⚠️ AUCUN pour les mouvements : MouvementStockService.genererEcriture crée l'écriture
            directement (journal "OD" en dur, statut VALIDEE immédiat, duplication du contrôle « exercice clos »)
INVENTAIRE  InventaireService → InventaireRegularisationRule → engine ✅ (écart d'inventaire = régularisation)
ÉCRITURE    VALIDEE
AUDIT       ⚠️ pas d'audit_log
```

## F. Immobilisation

```
ENTRÉE      ImmobilisationController / ImmobilisationService.creer (acquisition ; pas d'écriture d'achat propre ❓)
PLAN        PlanAmortissementCalculator (linéaire/dégressif selon ModeAmortissement) → DotationAmortissement
DOTATION    ImmobilisationService.genererDotations(année) :
              compte de dotation via AccountingAccountResolver ✅ (DOTATION_AMORTISSEMENT)
              ⚠️ mais écriture construite directement (new EcritureComptable), journal "OD" en dur, VALIDEE
CESSION / MISE EN SERVICE   ❓ non cartographié (hors périmètre de cet audit rapide)
```

## G. Clôture

```
ENTRÉE      ClotureExerciceService.cloturer(exerciceId)
CONTRÔLES   ClotureReadinessService.construireChecklist(entreprise, exercice) — seuls contrôles réels :
              • exercice présent / ouvert           (BLOCKING)
              • écritures BROUILLON restantes       (BLOCKING)
              • régularisations en attente          (WARNING)
              • détermination du résultat           (OK informatif)
              • findings ouverts CRITICAL/IMPORTANT (BLOCKING / WARNING)
            ⚠️ 12 points sont retournés « NOT_APPLICABLE — aucune logique de contrôle approfondie » :
            banque, caisse, rapprochement général, lettrage, fournisseurs, clients, stocks,
            immobilisations, amortissements, dépréciations, provisions, fiscalité.
ENGINE      ClotureRule + engine : écriture de détermination du résultat (classes 6/7 → 120/129 via rôles)
VERROU      Exercice.statut = CLOTURE ; pas d'écriture « à nouveaux » (soldes de bilan cumulés par construction)
RÉOUVERTURE ❓ non trouvée dans le code audité
AUDIT       ⚠️ pas d'audit_log sur la clôture
```
**Différence demandée par la mission** — « sait clôturer » ✅ vs « aide à comprendre pourquoi le dossier ne
tombe pas juste » ⚠️ : la checklist explique *ses 4 contrôles* (liste de titres de findings, nombre de
brouillons) mais ne vérifie ni l'équilibre de la balance, ni les comptes de tiers à solde inhabituel, ni
les amortissements non générés, ni l'inventaire, ni la TVA, ni la cohérence bilan/résultat.

## H. États financiers

`EtatsFinanciersService` : **bilan** (`/etats-financiers/bilan`) et **compte de résultat**
(`/compte-resultat`) ; rapports : grand livre, balance, balance âgée, journaux, **export FEC**
(`ExportFecService`, 18 colonnes, périmètre réglementaire à clarifier — cf. `P1_ARCHITECTURE.md`).
Classement par **préfixe de numéro de compte en dur** (`startsWith("66"/"77"/"40"/"41")`) ; poste « 120/129 »
codé comme libellé. **Absents** : TFT, annexes, tableau de correspondance comptes → lignes d'états
SYSCOHADA, contrôles de cohérence inter-états.

## I. Sources de données (§9 de la mission)

| Source | Format | Destination | Validation | Archivage | Traçabilité | Lien écriture |
|---|---|---|---|---|---|---|
| Upload web | PDF / image | `Document` | OCR + fraude | disque local (`LocalDocumentStorageService`) | `deposePar`, `createdAt` | `Document.ecritureId` |
| WhatsApp | webhook interne | `Document` (+ idempotence `WhatsappEvenementTraite`) | idem | idem | canal WHATSAPP | idem |
| Relevé bancaire | **CSV uniquement** | `ReleveBancaire` / `LigneReleve` | lignes non vides | en base | — | via pointage |
| Plan comptable | import référentiel | `PlanComptableReference` | validation de nature | — | — | — |
| E-mail | **absent** | — | — | — | — | — |
| Excel / OFX / CAMT | **absent** | — | — | — | — | — |
| API bancaire | **absente** (jugée coûteuse/incertaine, cf. `P2_DECISION_MATRIX.md`) | — | — | — | — | — |
| Photo/scan | non géré par l'heuristique ; VLM seulement si activé | — | — | — | — | — |

Gestion documentaire : `Document` porte les métadonnées (nom, type MIME, taille, chemin), **pas de hash de contenu**
(aucun `MessageDigest` dans le code) ; la détection de doublon est **sur le montant**, pas sur le fichier.
