export type StatutDemandePiece = 'EN_ATTENTE' | 'RECU' | 'ANNULEE';

export interface DemandePieceDto {
  id: string;
  libelle: string;
  description: string | null;
  dateLimite: string | null;
  statut: StatutDemandePiece;
  documentId: string | null;
  demandeeParNom: string | null;
  createdAt: string;
  resolueAt: string | null;
}

export interface CreerDemandePieceRequest {
  libelle: string;
  description?: string;
  dateLimite?: string;
}

export const LIBELLES_STATUT_DEMANDE: Record<StatutDemandePiece, string> = {
  EN_ATTENTE: 'En attente',
  RECU: 'Reçue',
  ANNULEE: 'Annulée',
};
