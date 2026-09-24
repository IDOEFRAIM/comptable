/** Moteur d'operations courantes SYSCOHADA (achats/ventes/reglements/avoirs) : une operation
 * metier passe toujours par une proposition d'ecriture (jamais une ecriture directement validee) -
 * voir AccountingOperationEngine cote backend. Fichier dedie plutot que d'alourdir
 * comptabilite.model.ts (deja volumineux), meme justification que plan-comptable.model.ts. */

export type AccountingOperationType =
  | 'ACHAT'
  | 'VENTE'
  | 'REGLEMENT_CLIENT'
  | 'REGLEMENT_FOURNISSEUR'
  | 'AVOIR_VENTE'
  | 'AVOIR_ACHAT';

export type StatutOperationProposal = 'PROPOSED' | 'NEEDS_REVIEW' | 'APPROVED' | 'REJECTED';

export type NatureAchat = 'MARCHANDISE' | 'MATIERE_PREMIERE' | 'SERVICE' | 'CHARGE' | 'IMMOBILISATION' | 'AUTRE';

export type NatureVente = 'VENTE_MARCHANDISE' | 'VENTE_PRODUIT' | 'VENTE_SERVICE' | 'AUTRE_VENTE';

export type MoyenPaiement = 'ESPECES' | 'CHEQUE' | 'VIREMENT' | 'CARTE' | 'MOBILE_MONEY' | 'AUTRE';

export type TypeReduction = 'REMISE' | 'RABAIS' | 'RISTOURNE' | 'ESCOMPTE';

export interface FraisAccessoireRequest {
  type: string;
  montantHt: number;
  tauxTvaId?: string;
  compteId: string;
  inclusDansBaseTva: boolean;
}

export interface ReductionRequest {
  typeReduction: TypeReduction;
  montant?: number;
  pourcentage?: number;
  condition?: string;
}

export interface AchatOperationRequest {
  natureAchat: NatureAchat;
  fournisseurId: string;
  date: string;
  reference?: string;
  libelle: string;
  montantHt: number;
  compteChargeId: string;
  tauxTvaId?: string;
  montantTtcAttendu?: number;
  fraisAccessoires?: FraisAccessoireRequest[];
  reductions?: ReductionRequest[];
}

export interface VenteOperationRequest {
  natureVente: NatureVente;
  clientId: string;
  date: string;
  reference?: string;
  libelle: string;
  montantHt: number;
  compteProduitId: string;
  tauxTvaId?: string;
  montantTtcAttendu?: number;
  fraisAccessoires?: FraisAccessoireRequest[];
  reductions?: ReductionRequest[];
}

export interface ReglementOperationRequest {
  type: 'REGLEMENT_CLIENT' | 'REGLEMENT_FOURNISSEUR';
  tiersId: string;
  date: string;
  reference?: string;
  libelle: string;
  montant: number;
  moyenPaiement: MoyenPaiement;
}

export interface AvoirOperationRequest {
  type: 'AVOIR_VENTE' | 'AVOIR_ACHAT';
  tiersId: string;
  date: string;
  reference?: string;
  libelle: string;
  montantHt: number;
  compteId: string;
  tauxTvaId?: string;
  montantTtcAttendu?: number;
}

export interface LignePropositionDto {
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  tiersId: string | null;
  libelle: string;
  debit: number;
  credit: number;
}

export interface AccountingOperationProposalDto {
  id: string;
  entrepriseId: string;
  type: AccountingOperationType;
  statut: StatutOperationProposal;
  journalId: string | null;
  journalCode: string | null;
  tiersId: string;
  dateOperation: string;
  libelle: string;
  reference: string | null;
  lignes: LignePropositionDto[];
  totalDebit: number;
  totalCredit: number;
  equilibree: boolean;
  explications: string[];
  warnings: string[];
  createdAt: string;
  validatedAt: string | null;
  validatedByNom: string | null;
  rejectionReason: string | null;
  ecritureId: string | null;
}

export interface RejeterPropositionRequest {
  motif: string;
}
