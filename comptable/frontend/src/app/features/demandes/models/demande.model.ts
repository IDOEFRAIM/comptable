export type StatutDemandePiece =
  | 'A_ENVOYER'
  | 'EN_ATTENTE'
  | 'RECU'
  | 'RAPPROCHEE'
  | 'ECHEC_ENVOI'
  | 'ANNULEE'
  | 'EXPIREE';

/** Dernier message sortant de la demande ; « SENT » = accepté par le fournisseur, jamais « délivré ». */
export interface DernierMessageDto {
  id: string;
  statut: string;
  type: string;
  tentatives: number;
  envoyeLe: string | null;
  derniereErreur: string | null;
  prochaineTentative: string | null;
}

export interface DemandePieceDto {
  id: string;
  libelle: string;
  description: string | null;
  dateLimite: string | null;
  statut: StatutDemandePiece;
  documentId: string | null;
  findingId: string | null;
  demandeeParNom: string | null;
  createdAt: string;
  resolueAt: string | null;
  /** Opération dont la demande réclame le justificatif (null pour une demande générale). */
  ecritureId: string | null;
  motif: string | null;
  canal: string | null;
  montant: number | null;
  dateOperation: string | null;
  tiersNom: string | null;
  periode: string | null;
  descriptionOperation: string | null;
  sentAt: string | null;
  derniereRelanceAt: string | null;
  nbRelances: number;
  prochaineRelanceAt: string | null;
  receivedAt: string | null;
  motifBlocage: string | null;
  resoluPar: string | null;
  dernierMessage: DernierMessageDto | null;
}

export interface CreerDemandePieceRequest {
  libelle: string;
  description?: string;
  dateLimite?: string;
}

/** Demande du justificatif d'une opération signalée par un constat « pièce manquante ». */
export interface DemanderPieceRequest {
  dateLimite?: string;
  message?: string;
  canal?: string;
}

export const LIBELLES_STATUT_DEMANDE: Record<StatutDemandePiece, string> = {
  A_ENVOYER: 'À envoyer',
  EN_ATTENTE: 'Demandée',
  RECU: 'Reçue',
  RAPPROCHEE: 'Rapprochée',
  ECHEC_ENVOI: 'Envoi impossible',
  ANNULEE: 'Annulée',
  EXPIREE: 'Expirée',
};

/** Ce qu'un CLIENT voit d'une demande : de quoi fournir sa pièce, jamais un identifiant ou un état interne. */
export interface DemandePieceClientDto {
  id: string;
  description: string | null;
  montant: number | null;
  date: string | null;
  dateLimite: string | null;
  etat: 'A_FOURNIR' | 'RECUE' | 'CLOTUREE';
  demandeeLe: string;
}

// ------------------------------------------------------------------------------------------------ collecte active

export interface SyntheseCollecteDto {
  piecesManquantes: number;
  aDemander: number;
  aEnvoyer: number;
  demandesEnvoyees: number;
  enAttenteClient: number;
  recues: number;
  relancesNecessaires: number;
  sansContact: number;
  echecsEnvoi: number;
  sansReponse: number;
  piecesAConfirmer: number;
  piecesAmbigues: number;
}

export interface MetriquesCollecteDto {
  piecesManquantes: number;
  demandesCreees: number;
  pourcentageEnvoyeesAutomatiquement: number | null;
  delaiMoyenReceptionHeures: number | null;
  nombreMoyenRelances: number | null;
  pourcentageRapprocheesAutomatiquement: number | null;
  tauxEchecEnvoi: number | null;
  demandesNecessitantUneIntervention: number;
}

/** Une opération sans pièce, candidate à une demande. */
export interface ElementCollecteDto {
  findingId: string;
  libelle: string | null;
  montant: number | null;
  date: string | null;
  tiers: string | null;
  dejaDemande: boolean;
}

export interface MessageApercuDto {
  destinataire: string;
  telephone: string;
  nbDemandes: number;
  texte: string;
}

/** Aperçu calculé par le serveur : rien n'est créé ni envoyé. */
export interface ApercuCollecteDto {
  elements: ElementCollecteDto[];
  messages: MessageApercuDto[];
  blocage: string | null;
  ignores: number;
}

export interface ResultatCollecteDto {
  demandeIds: string[];
  messageIds: string[];
  ignores: number;
  blocage: string | null;
}

export interface MessageCollecteDto {
  id: string;
  type: string;
  canal: string;
  statut: string;
  tentatives: number;
  cree: string;
  envoye: string | null;
  derniereErreur: string | null;
  prochaineTentative: string | null;
}

/** Page publique de dépôt : strictement de quoi comprendre ce qu'on demande. */
export interface InformationsDepotDto {
  entreprise: string;
  description: string | null;
  montant: number | null;
  date: string | null;
  dateLimite: string | null;
}
