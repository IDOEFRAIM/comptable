export type CanalTresorerie = 'BANQUE' | 'CAISSE' | 'MOBILE_MONEY';

export const LIBELLES_CANAL: Record<CanalTresorerie, string> = {
  BANQUE: 'Banque',
  CAISSE: 'Caisse',
  MOBILE_MONEY: 'Mobile Money',
};

export interface PositionTresorerieDto {
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  canal: CanalTresorerie;
  solde: number;
}

export interface CashPositionDto {
  dateReference: string;
  total: number;
  parCompte: PositionTresorerieDto[];
}

export interface PrevisionTresorerieDto {
  dateReference: string;
  horizonJours: number;
  soldeActuel: number;
  encaissementsConnus: number;
  encaissementsAttendus: number;
  decaissementsConnus: number;
  soldePrevisionnel: number;
}

export interface FinancialSnapshotDto {
  periodeDebut: string;
  periodeFin: string;
  cash: number;
  receivables: number;
  payables: number;
  revenue: number;
  expenses: number;
  profit: number;
  taxes: number;
}

export type TypeComparaison = 'PERIODE_PRECEDENTE' | 'AN_PRECEDENT';

export interface ComparaisonFinancialSnapshotDto {
  periodeActuelle: FinancialSnapshotDto;
  periodeComparaison: FinancialSnapshotDto;
  typeComparaison: TypeComparaison;
}
