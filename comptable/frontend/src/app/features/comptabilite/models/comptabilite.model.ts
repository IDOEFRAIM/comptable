import { LignePropositionDto } from './operations.model';

export type TypeTiers = 'CLIENT' | 'FOURNISSEUR';

export interface TiersDto {
  id: string;
  type: TypeTiers;
  nom: string;
  numeroContribuable: string | null;
  compteComptableId: string;
  compteComptableNumero: string;
}

export interface CreerTiersRequest {
  type: TypeTiers;
  nom: string;
  numeroContribuable?: string;
}

export type NatureCompte = 'ACTIF' | 'PASSIF' | 'CHARGE' | 'PRODUIT';
export type SensNormal = 'DEBIT' | 'CREDIT';

/** Champs parentId/niveau/actif/referenceId/sensNormal/compteCollectif/compteDetail/auxiliaireAutorise
 * ajoutes par la fondation plan comptable (voir plan-comptable.model.ts pour les types dedies). */
export interface CompteComptableDto {
  id: string;
  numero: string;
  intitule: string;
  nature: NatureCompte | null;
  lettrable: boolean;
  parentId: string | null;
  niveau: number;
  actif: boolean;
  referenceId: number | null;
  referenceNumero: string | null;
  sensNormal: SensNormal | null;
  compteCollectif: boolean;
  compteDetail: boolean;
  auxiliaireAutorise: boolean;
  /** Renseigne uniquement juste apres une desactivation (voir modifierCompte) - nombre de
   * parametres comptables actifs qui referencent encore ce compte (mission Etape 6, section 23) ;
   * null hors de ce cas. */
  mappingsActifsCount: number | null;
}

export interface JournalDto {
  id: string;
  code: string;
  libelle: string;
  type: string;
}

export interface LigneEcritureDto {
  id: string;
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  libelle: string | null;
  debit: number;
  credit: number;
  lettrage: string | null;
  numeroLigne: number;
  tiersId: string | null;
  tiersNom: string | null;
}

export type StatutEcriture = 'BROUILLON' | 'VALIDEE';

export interface EcritureComptableDto {
  id: string;
  entrepriseId: string;
  journalCode: string;
  numeroPiece: string;
  dateEcriture: string;
  libelle: string | null;
  statut: StatutEcriture;
  totalDebit: number;
  totalCredit: number;
  lignes: LigneEcritureDto[];
  createdAt: string;
}

export interface ComptabiliserDocumentRequest {
  tiersId: string;
  compteChargeOuProduitId: string;
  libelle?: string;
  dateEcriture?: string;
}

export interface LigneEcritureRequest {
  compteId: string;
  libelle?: string;
  debit: number;
  credit: number;
  tiersId?: string;
}

export interface CreerEcritureRequest {
  journalId: string;
  dateEcriture: string;
  libelle?: string;
  numeroPiece?: string;
  lignes: LigneEcritureRequest[];
}

export interface SoldeDto {
  debit: number;
  credit: number;
}

export interface BalanceLigneDto {
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  soldeOuverture: SoldeDto;
  mouvementDebit: number;
  mouvementCredit: number;
  soldeCloture: SoldeDto;
}

export interface BalanceDto {
  dateDebut: string;
  dateFin: string;
  lignes: BalanceLigneDto[];
  totalSoldeOuvertureDebit: number;
  totalSoldeOuvertureCredit: number;
  totalMouvementDebit: number;
  totalMouvementCredit: number;
  totalSoldeClotureDebit: number;
  totalSoldeClotureCredit: number;
}

export interface GrandLivreLigneDto {
  id: string;
  date: string;
  journalCode: string;
  numeroPiece: string;
  libelle: string | null;
  debit: number;
  credit: number;
  lettrage: string | null;
  soldeProgressif: SoldeDto;
}

export interface LettrerLignesRequest {
  compteId: string;
  ligneIds: string[];
  partiel: boolean;
}

export interface LettrageResultDto {
  code: string;
  ligneIds: string[];
  ecart: number;
}

export interface BalanceAgeeLigneDto {
  tiersId: string;
  tiersNom: string;
  typeTiers: TypeTiers;
  tranche0a30: number;
  tranche31a60: number;
  tranche61a90: number;
  tranchePlus90: number;
  total: number;
}

export interface BalanceAgeeDto {
  dateArrete: string;
  lignes: BalanceAgeeLigneDto[];
  totalGeneral: number;
}

export interface LigneReleveDto {
  id: string;
  date: string;
  libelle: string | null;
  montant: number;
  pointee: boolean;
  ligneEcritureId: string | null;
  ecritureNumeroPiece: string | null;
  ecritureLibelle: string | null;
}

export type TypeFacture = 'DEVIS' | 'FACTURE';
export type StatutFacture = 'BROUILLON' | 'ENVOYE' | 'ACCEPTE' | 'REFUSE' | 'CONVERTI' | 'VALIDEE' | 'PAYEE' | 'ANNULEE';

/** articleId est optionnel : ne le renseigner que si la ligne vend un article de stock suivi en
 * inventaire permanent (declenche le destockage automatique a la validation de la facture). */
export interface LigneFactureRequest {
  designation: string;
  quantite: number;
  prixUnitaire: number;
  tauxTva: number;
  articleId?: string;
}

export interface CreerFactureRequest {
  tiersId: string;
  dateEmission: string;
  dateEcheance?: string;
  objet?: string;
  lignes: LigneFactureRequest[];
}

export interface LigneFactureDto {
  id: string;
  designation: string;
  quantite: number;
  prixUnitaire: number;
  tauxTva: number;
  montantHt: number;
  montantTtc: number;
  articleId: string | null;
  mouvementStockId: string | null;
}

export interface FactureDto {
  id: string;
  type: TypeFacture;
  numero: string;
  tiersId: string;
  tiersNom: string;
  dateEmission: string;
  dateEcheance: string | null;
  statut: StatutFacture;
  objet: string | null;
  montantHt: number;
  montantTva: number;
  montantTtc: number;
  devisOrigineId: string | null;
  ecritureId: string | null;
  lignes: LigneFactureDto[];
}

export const LIBELLES_STATUT_FACTURE: Record<StatutFacture, string> = {
  BROUILLON: 'Brouillon',
  ENVOYE: 'Envoyé',
  ACCEPTE: 'Accepté',
  REFUSE: 'Refusé',
  CONVERTI: 'Converti en facture',
  VALIDEE: 'Validée',
  PAYEE: 'Payée',
  ANNULEE: 'Annulée',
};

// ---------------------------------------------------------------------------
// Avoirs commerciaux (Sprint 13)
// ---------------------------------------------------------------------------

export type StatutFactureAvoir = 'BROUILLON' | 'VALIDEE';

export const LIBELLES_STATUT_FACTURE_AVOIR: Record<StatutFactureAvoir, string> = {
  BROUILLON: 'Brouillon',
  VALIDEE: 'Validé',
};

export interface LigneAvoirRequest {
  ligneFactureId: string;
  quantite: number;
}

/** total=true reprend integralement toutes les lignes restant a crediter ; sinon lignes precise
 * la selection partielle (ligne + quantite). retourStock ne s'applique qu'aux lignes liees a un
 * article de stock. */
export interface CreerFactureAvoirRequest {
  motif?: string;
  retourStock: boolean;
  total: boolean;
  lignes?: LigneAvoirRequest[];
}

export interface LigneFactureAvoirDto {
  id: string;
  ligneFactureOrigineId: string;
  designation: string;
  quantite: number;
  prixUnitaire: number;
  tauxTva: number;
  montantHt: number;
  montantTtc: number;
}

export interface FactureAvoirDto {
  id: string;
  numero: string;
  factureOrigineId: string;
  factureOrigineNumero: string;
  tiersId: string;
  tiersNom: string;
  dateEmission: string;
  motif: string | null;
  retourStock: boolean;
  statut: StatutFactureAvoir;
  montantHt: number;
  montantTva: number;
  montantTtc: number;
  ecritureId: string | null;
  lignes: LigneFactureAvoirDto[];
}

export type ModeAmortissement = 'LINEAIRE' | 'DEGRESSIF';
export type StatutImmobilisation = 'EN_SERVICE' | 'CEDEE';

export interface LigneAmortissementDto {
  annee: number;
  dotation: number;
  valeurNetteComptableFin: number;
  genere: boolean;
}

export interface ImmobilisationDto {
  id: string;
  compteNumero: string;
  compteIntitule: string;
  compteAmortissementNumero: string;
  libelle: string;
  dateAcquisition: string;
  valeurOrigine: number;
  dureeAnnees: number;
  mode: ModeAmortissement;
  statut: StatutImmobilisation;
  valeurNetteComptableActuelle: number;
  plan: LigneAmortissementDto[];
}

export interface CreerImmobilisationRequest {
  compteId: string;
  compteAmortissementId: string;
  libelle: string;
  dateAcquisition: string;
  valeurOrigine: number;
  dureeAnnees: number;
  mode: ModeAmortissement;
}

export interface GenererDotationsResultDto {
  ecritureId: string | null;
  annee: number;
  totalDotations: number;
  nombreImmobilisations: number;
}

export type SourceTypeIA =
  | 'BANK_TRANSACTION'
  | 'MOBILE_MONEY_TRANSACTION'
  | 'ACCOUNTING_ENTRY'
  | 'CUSTOMER_INVOICE'
  | 'SUPPLIER_INVOICE'
  | 'DOCUMENT';

export type DecisionRapprochementIA = 'MATCH_AUTO' | 'MATCH_REVIEW' | 'NO_MATCH' | 'AI_UNAVAILABLE';

export const LIBELLES_TYPE_CANDIDAT_IA: Record<SourceTypeIA, string> = {
  BANK_TRANSACTION: 'transaction bancaire',
  MOBILE_MONEY_TRANSACTION: 'transaction Mobile Money',
  ACCOUNTING_ENTRY: 'écriture comptable',
  CUSTOMER_INVOICE: 'facture client',
  SUPPLIER_INVOICE: 'facture fournisseur',
  DOCUMENT: 'document',
};

/** Miroir de AiMatchProposalDto (Java) : proposition du Universal Matching Engine pour une ligne
 * de relevé bancaire non encore rapprochée automatiquement. */
export interface AiMatchProposalDto {
  id: string;
  sourceType: SourceTypeIA | null;
  sourceId: string;
  candidateType: SourceTypeIA | null;
  candidateId: string | null;
  confidence: number;
  decision: DecisionRapprochementIA | null;
  matchType: string | null;
  reasons: string[];
  warnings: string[];
  status: 'PROPOSED' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED';
  createdAt: string;
  validatedAt: string | null;
}

export interface ReleveBancaireDto {
  id: string;
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  libelle: string;
  dateDebut: string;
  dateFin: string;
  soldeDebut: number;
  soldeFin: number;
  soldeComptable: number;
  ecart: number;
  lignes: LigneReleveDto[];
}

export interface GrandLivreDto {
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  dateDebut: string;
  dateFin: string;
  soldeOuverture: SoldeDto;
  lignes: GrandLivreLigneDto[];
  soldeCloture: SoldeDto;
}

export type StatutExercice = 'OUVERT' | 'CLOTURE';

export interface ExerciceComptableDto {
  id: string;
  dateDebut: string;
  dateFin: string;
  statut: StatutExercice;
}

export interface PosteFinancierDto {
  numero: string;
  intitule: string;
  montant: number;
}

export interface SectionFinanciereDto {
  libelle: string;
  postes: PosteFinancierDto[];
  total: number;
}

export interface BilanDto {
  dateArrete: string;
  actifImmobilise: SectionFinanciereDto;
  actifCirculant: SectionFinanciereDto;
  tresorerieActif: SectionFinanciereDto;
  totalActif: number;
  ressourcesDurables: SectionFinanciereDto;
  passifCirculant: SectionFinanciereDto;
  tresoreriePassif: SectionFinanciereDto;
  totalPassif: number;
}

export interface CompteResultatDto {
  dateDebut: string;
  dateFin: string;
  chargesExploitation: SectionFinanciereDto;
  produitsExploitation: SectionFinanciereDto;
  resultatExploitation: number;
  chargesFinancieres: SectionFinanciereDto;
  produitsFinanciers: SectionFinanciereDto;
  resultatFinancier: number;
  resultatNet: number;
}

// ---------------------------------------------------------------------------
// Stocks & Achats (Sprint 11)
// ---------------------------------------------------------------------------

export type TypeArticle = 'MARCHANDISE' | 'MATIERE_PREMIERE' | 'PRODUIT_FINI' | 'PRODUIT_INTERMEDIAIRE' | 'AUTRE';

export const LIBELLES_TYPE_ARTICLE: Record<TypeArticle, string> = {
  MARCHANDISE: 'Marchandise',
  MATIERE_PREMIERE: 'Matière première',
  PRODUIT_FINI: 'Produit fini',
  PRODUIT_INTERMEDIAIRE: 'Produit intermédiaire',
  AUTRE: 'Autre',
};

export type PolitiqueStockNegatif = 'INTERDIT' | 'AUTORISE_AVEC_ALERTE' | 'AUTORISE';

export type ModeGestionStock = 'PERMANENT' | 'INTERMITTENT';

export interface ArticleDto {
  id: string;
  codeArticle: string;
  designation: string;
  categorie: string | null;
  type: TypeArticle;
  uniteMesure: string;
  prixVenteHt: number;
  tauxTva: number;
  quantiteEnStock: number;
  valeurStockTotale: number;
  pump: number;
  compteStockId: string;
  compteStockNumero: string;
  compteAchatId: string;
  compteAchatNumero: string;
  compteVenteId: string;
  compteVenteNumero: string;
  compteVariationId: string;
  compteVariationNumero: string;
  actif: boolean;
  stockMinimum: number | null;
  politiqueStockNegatif: PolitiqueStockNegatif;
  modeGestionStock: ModeGestionStock;
  createdAt: string;
  updatedAt: string | null;
}

export interface CreerArticleRequest {
  codeArticle: string;
  designation: string;
  categorie?: string;
  type: TypeArticle;
  uniteMesure: string;
  prixVenteHt: number;
  tauxTva: number;
  compteStockId: string;
  compteAchatId: string;
  compteVenteId: string;
  compteVariationId: string;
  stockMinimum?: number;
  politiqueStockNegatif?: PolitiqueStockNegatif;
  modeGestionStock?: ModeGestionStock;
}

export interface ModifierArticleRequest {
  designation?: string;
  categorie?: string;
  prixVenteHt?: number;
  tauxTva?: number;
  actif?: boolean;
  stockMinimum?: number;
  politiqueStockNegatif?: PolitiqueStockNegatif;
  modeGestionStock?: ModeGestionStock;
}

/** ENTREE/SORTIE/AJUSTEMENT/TRANSFERT sont saisissables depuis le formulaire generique ; les
 * autres valeurs (ENTREE_ACHAT, SORTIE_VENTE, *_INVENTAIRE, *_PRODUCTION, SORTIE_CONSOMMATION)
 * sont generees automatiquement par les flux achat/vente/inventaire et affichees en lecture seule
 * dans l'historique des mouvements. */
export type TypeMouvementStock =
  | 'ENTREE'
  | 'SORTIE'
  | 'AJUSTEMENT'
  | 'ENTREE_ACHAT'
  | 'ENTREE_PRODUCTION'
  | 'ENTREE_INVENTAIRE'
  | 'SORTIE_VENTE'
  | 'SORTIE_CONSOMMATION'
  | 'SORTIE_PRODUCTION'
  | 'SORTIE_INVENTAIRE'
  | 'TRANSFERT';

export const LIBELLES_TYPE_MOUVEMENT: Record<TypeMouvementStock, string> = {
  ENTREE: 'Entrée',
  SORTIE: 'Sortie',
  AJUSTEMENT: 'Ajustement',
  ENTREE_ACHAT: 'Entrée (achat)',
  ENTREE_PRODUCTION: 'Entrée (production)',
  ENTREE_INVENTAIRE: 'Entrée (inventaire)',
  SORTIE_VENTE: 'Sortie (vente)',
  SORTIE_CONSOMMATION: 'Sortie (consommation)',
  SORTIE_PRODUCTION: 'Sortie (production)',
  SORTIE_INVENTAIRE: 'Sortie (inventaire)',
  TRANSFERT: 'Transfert',
};

export interface MouvementStockDto {
  id: string;
  articleId: string;
  articleCode: string;
  type: TypeMouvementStock;
  quantite: number;
  prixUnitaireHt: number;
  valeurMouvement: number;
  quantiteApres: number;
  pumpApres: number;
  dateMouvement: string;
  libelle: string | null;
  ecritureId: string | null;
  sourceType: string | null;
  sourceId: string | null;
  depotSourceId: string | null;
  depotDestinationId: string | null;
  avertissement: string | null;
}

export interface CreerMouvementStockRequest {
  articleId: string;
  type: TypeMouvementStock;
  quantite: number;
  prixUnitaireHt?: number;
  dateMouvement?: string;
  libelle?: string;
  depotSourceId?: string;
  depotDestinationId?: string;
}

export interface DepotDto {
  id: string;
  code: string;
  libelle: string;
  actif: boolean;
}

export interface CreerDepotRequest {
  code: string;
  libelle: string;
}

export type StatutCommandeAchat = 'EN_COURS' | 'RECEPTIONNEE' | 'FACTUREE';

export const LIBELLES_STATUT_COMMANDE_ACHAT: Record<StatutCommandeAchat, string> = {
  EN_COURS: 'En cours',
  RECEPTIONNEE: 'Réceptionnée',
  FACTUREE: 'Facturée',
};

export interface LigneCommandeAchatRequest {
  articleId: string;
  quantite: number;
  prixUnitaireHt: number;
}

export interface CreerCommandeAchatRequest {
  fournisseurId: string;
  dateCommande: string;
  lignes: LigneCommandeAchatRequest[];
}

export interface LigneCommandeAchatDto {
  id: string;
  articleId: string;
  articleCode: string;
  articleDesignation: string;
  quantite: number;
  prixUnitaireHt: number;
  montantHt: number;
}

export interface CommandeAchatDto {
  id: string;
  numero: string;
  fournisseurId: string;
  fournisseurNom: string;
  dateCommande: string;
  statut: StatutCommandeAchat;
  dateReception: string | null;
  montantTotalHt: number;
  lignes: LigneCommandeAchatDto[];
}

export interface FactureFournisseurDto {
  id: string;
  commandeId: string;
  commandeNumero: string;
  fournisseurId: string;
  fournisseurNom: string;
  numero: string;
  dateFacture: string;
  montantHt: number;
  montantTva: number;
  montantTtc: number;
  ecritureId: string | null;
  lignesEcriture: LignePropositionDto[];
}

export interface CreerAvoirFournisseurRequest {
  motif?: string;
  retourStock: boolean;
}

export interface AvoirFournisseurDto {
  id: string;
  factureId: string;
  factureNumero: string;
  fournisseurId: string;
  fournisseurNom: string;
  numero: string;
  dateEmission: string;
  montantHt: number;
  montantTva: number;
  montantTtc: number;
  motif: string | null;
  retourStock: boolean;
  ecritureId: string | null;
  lignesEcriture: LignePropositionDto[];
}
