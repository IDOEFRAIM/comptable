export interface NotificationDto {
  id: string;
  type: string;
  contenu: string;
  statut: 'NON_LUE' | 'LUE';
  createdAt: string;
}
