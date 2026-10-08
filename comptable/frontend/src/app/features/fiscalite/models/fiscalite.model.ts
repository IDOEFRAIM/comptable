export type TypeImpot = 'TVA' | 'IS' | 'PATENTE' | 'CNPS' | 'AUTRE';

export type StatutDeclaration = 'A_FAIRE' | 'DEPOSEE' | 'PAYEE';

export interface DeclarationFiscaleDto {
  id: string;
  typeImpot: TypeImpot;
  periode: string;
  dateEcheance: string;
  statut: StatutDeclaration;
  enRetard: boolean;
  montant: number | null;
  dateDeclaration: string | null;
  datePaiement: string | null;
}

export interface CreerDeclarationRequest {
  typeImpot: TypeImpot;
  periode: string;
  dateEcheance: string;
  montant?: number;
}

export const LIBELLES_TYPE_IMPOT: Record<TypeImpot, string> = {
  TVA: 'TVA',
  IS: "Impôt sur les sociétés",
  PATENTE: 'Patente',
  CNPS: 'Cotisations sociales (CNPS)',
  AUTRE: 'Autre',
};

export const LIBELLES_STATUT_DECLARATION: Record<StatutDeclaration, string> = {
  A_FAIRE: 'À faire',
  DEPOSEE: 'Déposée',
  PAYEE: 'Payée',
};

export type PaiementFournisseur = 'E_SYNTAXE' | 'FASO_ARZEKA';

export type StatutPaiement = 'INITIE' | 'EN_ATTENTE_CONFIRMATION' | 'REUSSI' | 'ECHEC';

export interface PaiementFiscalDto {
  id: string;
  fournisseur: PaiementFournisseur;
  statut: StatutPaiement;
  montant: number;
  numeroTelephonePayeur: string | null;
  referenceExterne: string | null;
  messageStatut: string | null;
  dateInitiation: string;
  dateConfirmation: string | null;
}

export interface InitierPaiementRequest {
  fournisseur: PaiementFournisseur;
  numeroTelephonePayeur?: string;
}

export const LIBELLES_FOURNISSEUR: Record<PaiementFournisseur, string> = {
  E_SYNTAXE: 'e-Syntaxe (DGI)',
  FASO_ARZEKA: 'Faso Arzeka',
};

export const LIBELLES_STATUT_PAIEMENT: Record<StatutPaiement, string> = {
  INITIE: 'Initié',
  EN_ATTENTE_CONFIRMATION: 'En attente de confirmation',
  REUSSI: 'Réussi',
  ECHEC: 'Échec',
};
