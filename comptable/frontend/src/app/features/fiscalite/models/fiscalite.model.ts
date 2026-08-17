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
