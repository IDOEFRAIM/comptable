import { NatureCompte, SensNormal } from './comptabilite.model';

/** Referentiel SYSCOHADA global (lecture seule, sans scope entreprise). */
export interface PlanComptableReferenceDto {
  id: number;
  numero: string;
  intitule: string;
  classe: number;
  nature: NatureCompte | null;
  parentId: number | null;
  niveau: number;
  sensNormal: SensNormal | null;
  compteCollectif: boolean;
  compteDetail: boolean;
  lettrable: boolean;
  auxiliaireAutorise: boolean;
  actif: boolean;
  roleSysteme: string | null;
}

export interface CompteComptableArbreDto {
  id: string;
  numero: string;
  intitule: string;
  niveau: number;
  actif: boolean;
  origineReference: boolean;
  enfants: CompteComptableArbreDto[];
}

export type ModeImportReference = 'TOUT' | 'SELECTION';

export interface ImporterReferenceRequest {
  mode: ModeImportReference;
  codes?: string[];
}

export interface CompteIgnoreDto {
  numero: string;
  raison: string;
}

export interface ImporterReferenceResultDto {
  comptesAjoutes: string[];
  comptesIgnores: CompteIgnoreDto[];
}

export interface CreerSubdivisionCompteRequest {
  numero: string;
  intitule: string;
  lettrable: boolean;
  parentId?: string;
}

export interface ModifierCompteRequest {
  intitule?: string;
  actif?: boolean;
}
