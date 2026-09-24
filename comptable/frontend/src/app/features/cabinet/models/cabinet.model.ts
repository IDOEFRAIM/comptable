export interface ClientHealthDto {
  entrepriseId: string;
  raisonSociale: string;
  scoreGlobal: number;
  statut: 'PRET' | 'A_SURVEILLER' | 'BLOQUE';
}

export interface RisqueClientDto {
  entrepriseId: string;
  raisonSociale: string;
  severite: 'CRITICAL' | 'IMPORTANT' | 'REVIEW';
  cause: string;
  depuisQuand: string;
  actionRecommandee: string | null;
}

export interface CabinetCockpitDto {
  totalClients: number;
  clientsNormaux: number;
  clientsASurveiller: number;
  clientsBloques: number;
  dossiersATraiter: number;
  demandesEnRetard: number;
  cloturesARisque: number;
  anomaliesFiscales: number;
  rapprochementsCritiques: number;
  clients: ClientHealthDto[];
  topRisques: RisqueClientDto[];
}

export interface InboxItemDto {
  id: string;
  itemType: 'FINDING' | 'DEMANDE_PIECE';
  entrepriseId: string;
  entrepriseNom: string;
  severite: string;
  titre: string;
  dueDate: string | null;
  assignedToId: string | null;
  assignedToNom: string | null;
  createdAt: string;
}

export interface WorkloadDto {
  collaborateurId: string;
  collaborateurNom: string;
  totalTaches: number;
  tachesCritiques: number;
}

export interface CollaborateurDto {
  id: string;
  nomComplet: string;
}

export interface CabinetAnalyticsDto {
  nombreDossiers: number;
  dossiersAJour: number;
  dossiersEnRetard: number;
  tempsMoyenResolutionJours: number | null;
  tauxDemandesResoluesPourcent: number | null;
}

/** Classe de modificateur pour `.point-statut` (styles.scss) - remplace l'ancien mapping vers un
 * caractere emoji (rendu incoherent selon OS/navigateur, pas un vrai indicateur visuel). */
export const LIBELLES_STATUT_CLIENT: Record<string, string> = {
  PRET: 'succes',
  A_SURVEILLER: 'attention',
  BLOQUE: 'danger',
};
