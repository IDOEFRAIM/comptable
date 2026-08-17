export type NiveauRisqueRecouvrement = 'LOW' | 'MEDIUM' | 'HIGH';

export const LIBELLES_RISQUE_RECOUVREMENT: Record<NiveauRisqueRecouvrement, string> = {
  LOW: '🟢 Faible',
  MEDIUM: '🟠 Moyen',
  HIGH: '🔴 Élevé',
};

export interface CollectionRiskDto {
  tiersId: string;
  tiersNom: string;
  soldeDu: number;
  niveauRisque: NiveauRisqueRecouvrement;
  actionRecommandee: string;
}
