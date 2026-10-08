export type TypeDocument =
  | 'FACTURE_ACHAT'
  | 'FACTURE_VENTE'
  | 'RECU'
  | 'RELEVE_BANCAIRE'
  | 'DOCUMENT_PAIE'
  | 'DOCUMENT_SOCIAL'
  | 'DOCUMENT_FISCAL'
  | 'CONTRAT'
  | 'AUTRE';

export type CanalSource = 'WEB' | 'WHATSAPP' | 'EMAIL' | 'API' | 'SCAN' | 'MANUAL';

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
  canalSource: CanalSource;
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
  DOCUMENT_PAIE: 'Paie',
  DOCUMENT_SOCIAL: 'Social',
  DOCUMENT_FISCAL: 'Fiscal',
  CONTRAT: 'Contrat',
  AUTRE: 'Autre',
};

export const LIBELLES_STATUT: Record<StatutDocument, string> = {
  EN_ATTENTE: 'En attente',
  EN_COURS_TRAITEMENT: 'En cours de traitement',
  TRAITE: 'Traité',
  ERREUR: 'Erreur',
  EN_ATTENTE_VALIDATION_HUMAINE: 'En attente de validation (IA)',
};

export const LIBELLES_CANAL: Record<CanalSource, string> = {
  WEB: 'Dépôt',
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  API: 'API',
  SCAN: 'Scan',
  MANUAL: 'Saisie',
};
