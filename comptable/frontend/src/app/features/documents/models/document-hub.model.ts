import { CanalSource, TypeDocument } from './document.model';

export type TuileHub = 'RECUS' | 'TRAITES' | 'A_VERIFIER' | 'DOUBLONS' | 'NON_RAPPROCHES' | 'A_CONFIRMER';
export type StatutRapprochement = 'NON_RAPPROCHE' | 'RAPPROCHE' | 'A_CONFIRMER' | 'AMBIGU';
export type QualiteTiers = 'EXACT' | 'PROBABLE' | 'AMBIGU' | 'INCONNU';

export interface SyntheseHubDto {
  periode: string | null;
  recus: number;
  traites: number;
  aVerifier: number;
  doublons: number;
  nonRapproches: number;
  /** Opérations sans justificatif (constats actifs) — pas des documents. */
  piecesManquantes: number;
  aConfirmer: number;
  rapprochesAutomatiquement: number;
}

export interface AssociationDto {
  type: string;
  id: string;
  libelle: string;
}

export interface LigneHubDto {
  id: string;
  type: TypeDocument;
  canal: CanalSource;
  recuLe: string;
  datePiece: string | null;
  tiersId: string | null;
  tiersNom: string | null;
  fournisseurExtrait: string | null;
  numeroFacture: string | null;
  montantTtc: number | null;
  devise: string | null;
  statut: string;
  statutRapprochement: StatutRapprochement;
  association: AssociationDto | null;
  ecritureId: string | null;
  qualiteTiers: QualiteTiers | null;
  confianceTiers: number | null;
  confianceRapprochement: number | null;
  valide: boolean;
  doublonConfirme: boolean;
  constats: string[];
}

export interface PageHubDto {
  contenu: LigneHubDto[];
  page: number;
  taille: number;
  total: number;
  totalPages: number;
}

export interface CandidatFactureDto {
  cibleType: string;
  id: string;
  numero: string;
  numeroFournisseur: string | null;
  date: string | null;
  montantTtc: number;
  ecritureId: string | null;
  niveau: 'EXACT' | 'PROBABLE' | 'POSSIBLE';
  raison: string;
}

export interface DocumentSimpleDto {
  id: string;
  nomFichier: string | null;
  recuLe: string;
  montantTtc: number | null;
  numeroFacture: string | null;
  raison: string;
}

export interface ResultatActionDto {
  id: string;
  statutRapprochement: StatutRapprochement;
  cibleType: string | null;
  cibleId: string | null;
  valide: boolean;
  doublonConfirme: boolean;
}

export interface ResultatLotDto {
  valides: string[];
  ignores: string[];
}

export interface FiltreHub {
  tuile?: TuileHub | '';
  type?: TypeDocument | '';
  q?: string;
  periode?: string;
  page?: number;
  taille?: number;
}

export const LIBELLES_CONSTAT_DOCUMENT: Record<string, string> = {
  EXACT_DUPLICATE: 'Fichier en double',
  DUPLICATE_INVOICE: 'Facture en double',
  POSSIBLE_DUPLICATE: 'Doublon possible',
  DOCUMENT_UNREADABLE: 'Illisible',
  DOCUMENT_AMBIGUOUS: 'Ambigu',
  DOCUMENT_UNMATCHED: 'Non rapprochée',
  DOCUMENT_AMOUNT_MISMATCH: 'Montant différent',
  DOCUMENT_AT_RISK: 'À risque',
};

export interface CandidatDemandeDto {
  demandeId: string;
  description: string | null;
  montant: number | null;
  date: string | null;
  tiers: string | null;
  statut: string;
  niveau: 'EXACT' | 'PROBABLE' | 'AMBIGUOUS' | 'POSSIBLE';
}
