export type StatutTransaction = 'RAPPROCHEE' | 'A_CONFIRMER' | 'AMBIGUE' | 'SANS_CORRESPONDANCE' | 'VIREMENT_INTERNE';

export const LIBELLES_STATUT_TRANSACTION: Record<StatutTransaction, string> = {
  RAPPROCHEE: 'Rapprochée',
  A_CONFIRMER: 'À confirmer',
  AMBIGUE: 'Ambiguë',
  SANS_CORRESPONDANCE: 'Sans correspondance',
  VIREMENT_INTERNE: 'Virement interne',
};

export interface CritereDto {
  code: string;
  libelle: string;
  satisfait: boolean;
  points: number;
}

/** Le rapprochement proposé ou confirmé d'une transaction, avec ce qui le justifie. */
export interface PropositionDto {
  reconciliationId: string | null;
  ligneEcritureId: string;
  piece: string | null;
  dateEcriture: string | null;
  libelleEcriture: string | null;
  confiance: number | null;
  explication: string | null;
  criteres: CritereDto[];
  source: string | null;
  statut: 'PROPOSED' | 'CONFIRMED' | 'AMBIGUOUS';
  nbCandidats: number;
  /** ECRITURE (cas courant) ou VIREMENT_INTERNE : les deux jambes d'un transfert entre deux comptes de l'entreprise. */
  type: 'ECRITURE' | 'VIREMENT_INTERNE';
  transfertId: string | null;
  contrepartie: string | null;
}

export interface TransactionDto {
  id: string;
  releveId: string;
  compteNumero: string;
  date: string;
  libelle: string;
  montant: number;
  sens: 'CREDIT' | 'DEBIT';
  statut: StatutTransaction;
  doublonPossible: boolean;
  moyenPaiement: string | null;
  tiersProbable: string | null;
  referenceProbable: string | null;
  proposition: PropositionDto | null;
}

export interface PageTransactions {
  lignes: TransactionDto[];
  total: number;
  page: number;
  taille: number;
  totalPages: number;
}

export interface SyntheseBanqueDto {
  periode: string | null;
  transactions: number;
  rapprochees: number;
  aConfirmer: number;
  ambigues: number;
  sansCorrespondance: number;
  doublonsPossibles: number;
  tauxRapproche: number;
  montantNonRapproche: number;
  virementsInternes: number;
}

export interface CandidatDto {
  ligneEcritureId: string;
  piece: string;
  date: string;
  libelle: string | null;
  montant: number;
  score: number;
  criteres: CritereDto[];
  propose: boolean;
  dejaRapprochee: boolean;
}

export interface PropositionLotDto {
  reconciliationId: string;
  transactionId: string;
  date: string;
  libelle: string;
  montant: number;
  piece: string | null;
  confiance: number;
  explication: string | null;
}

export interface ResultatLotDto {
  confirmees: number;
  ignorees: number;
  motifs: string[];
}

export interface MetriquesBanqueDto {
  transactions: number;
  pctRapprochesAutomatiquement: number;
  pctNecessitantConfirmation: number;
  pctAmbigues: number;
  pctSansCorrespondance: number;
  tempsMoyenRapprochementHeures: number | null;
  facturesOuvertes: number;
  montantNonRapproche: number;
  ancienneteDesEcarts: Record<string, number>;
  plusAncienEcartJours: number | null;
}

export interface RejetImportDto {
  numeroLigne: number;
  raison: string;
  extrait: string;
}

/** Ce que le comptable voit AVANT de valider un import. */
export interface ApercuImportDto {
  lignesTotal: number;
  valides: number;
  invalides: number;
  doublonsExacts: number;
  doublonsPossibles: number;
  aImporter: number;
  devise: string;
  debut: string | null;
  fin: string | null;
  totalCredits: number;
  totalDebits: number;
  soldeOuverture: number | null;
  soldeCloture: number | null;
  soldesCoherents: boolean | null;
  ecartSolde: number | null;
  dejaImporteIntegralement: boolean;
  rejets: RejetImportDto[];
}

export interface ResultatImportDto {
  lot: { id: string; importedRows: number; status: string; exactDuplicates: number };
  apercu: ApercuImportDto;
  releve: { id: string } | null;
}

export interface LotImportDto {
  id: string;
  fichier: string | null;
  importeLe: string;
  lignesLues: number;
  valides: number;
  rejetees: number;
  doublonsExacts: number;
  doublonsPossibles: number;
  importees: number;
  statut: string;
}

export interface ReglementARepartirDto {
  paymentLineId: string;
  piece: string;
  date: string;
  libelle: string | null;
  tiers: string;
  montant: number;
  alloue: number;
  nonAffecte: number;
}

export interface FactureCandidateDto {
  invoiceLineId: string;
  piece: string;
  date: string;
  libelle: string | null;
  tiers: string;
  montant: number;
  alloue: number;
  reste: number;
  statut: 'NON_PAYEE' | 'PARTIELLEMENT_PAYEE' | 'PAYEE';
}

export interface AllocationDto {
  id: string;
  paymentLineId: string;
  invoiceLineId: string;
  piecePaiement: string | null;
  pieceFacture: string | null;
  tiers: string | null;
  montant: number;
  statut: 'ACTIVE' | 'REVERSED';
  source: string;
  creeLe: string;
  annuleLe: string | null;
  motifAnnulation: string | null;
}

export const LIBELLES_STATUT_PAIEMENT: Record<string, string> = {
  NON_PAYEE: 'Non payée',
  PARTIELLEMENT_PAYEE: 'Partiellement payée',
  PAYEE: 'Payée',
};
