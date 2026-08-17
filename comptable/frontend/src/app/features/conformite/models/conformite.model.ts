import { FindingDto } from '../../dashboard/models/dashboard.model';

export type StatutObligation = 'A_FAIRE' | 'DEPOSEE' | 'PAYEE';

export const LIBELLES_STATUT_OBLIGATION: Record<StatutObligation, string> = {
  A_FAIRE: 'À faire',
  DEPOSEE: 'Déposée',
  PAYEE: 'Payée',
};

export interface ObligationFiscaleDto {
  id: string;
  typeImpot: string;
  periode: string;
  dateEcheance: string;
  statut: StatutObligation;
  montant: number;
  document: string | null;
}

export interface TaxHealthDto {
  score: number;
}

export interface ComplianceCenterDto {
  obligations: ObligationFiscaleDto[];
  anomalies: FindingDto[];
}
