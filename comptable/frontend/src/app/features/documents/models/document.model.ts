export type TypeDocument = 'FACTURE_ACHAT' | 'FACTURE_VENTE' | 'RECU' | 'RELEVE_BANCAIRE' | 'AUTRE';

export type StatutDocument =
  | 'EN_ATTENTE'
  | 'EN_COURS_TRAITEMENT'
  | 'TRAITE'
  | 'ERREUR'
  | 'EN_ATTENTE_VALIDATION_HUMAINE';

export interface DocumentDto {
  id: string;
  entrepriseId: string;
  typeDocument: TypeDocument;
  canalSource: 'WEB' | 'WHATSAPP' | 'EMAIL';
  nomFichierOriginal: string;
  contentType: string;
  tailleOctets: number;
  statut: StatutDocument;
  montantHt: number | null;
  montantTva: number | null;
  montantTtc: number | null;
  datePiece: string | null;
  messageOcr: string | null;
  ecritureId: string | null;
  createdAt: string;
}

export const LIBELLES_TYPE_DOCUMENT: Record<TypeDocument, string> = {
  FACTURE_ACHAT: 'Facture d\'achat',
  FACTURE_VENTE: 'Facture de vente',
  RECU: 'Reçu',
  RELEVE_BANCAIRE: 'Relevé bancaire',
  AUTRE: 'Autre',
};

export const LIBELLES_STATUT: Record<StatutDocument, string> = {
  EN_ATTENTE: 'En attente',
  EN_COURS_TRAITEMENT: 'En cours de traitement',
  TRAITE: 'Traité',
  ERREUR: 'Erreur',
  EN_ATTENTE_VALIDATION_HUMAINE: 'En attente de validation (IA)',
};
