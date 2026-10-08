import { DeclarationFiscaleDto } from '../../fiscalite/models/fiscalite.model';

export interface SoldeDto {
  debit: number;
  credit: number;
}

export interface TableauDeBordDto {
  documentsATraiter: number;
  documentsEnErreur: number;
  ecrituresBrouillon: number;
  soldeTresorerie: SoldeDto;
  prochainesEcheances: DeclarationFiscaleDto[];
  echeancesEnRetard: DeclarationFiscaleDto[];
}

// ---------------------------------------------------------------------------
// P0 "Core Intelligent" : Dossier Comptable Intelligent + Inbox (Findings) + Closing Readiness
// ---------------------------------------------------------------------------

export interface ScoreDomaineDto {
  domaine: string;
  score: number;
}

export type SeveriteFinding = 'CRITICAL' | 'IMPORTANT' | 'REVIEW';

export const LIBELLES_SEVERITE_FINDING: Record<SeveriteFinding, string> = {
  CRITICAL: 'Critique',
  IMPORTANT: 'Important',
  REVIEW: 'À vérifier',
};

export interface FindingDto {
  id: string;
  type: string;
  severity: SeveriteFinding;
  sourceType: string;
  sourceId: string;
  title: string;
  description: string | null;
  confidence: number | null;
  evidence: Record<string, unknown> | null;
  recommendedAction: string | null;
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'DISMISSED';
  createdAt: string;
  resolvedAt: string | null;
  assignedToId: string | null;
  assignedToNom: string | null;
  dueDate: string | null;
}

export interface DossierComptableDto {
  entrepriseNom: string;
  scoreGlobal: number;
  statut: 'PRET' | 'A_SURVEILLER' | 'BLOQUE';
  scoresParDomaine: ScoreDomaineDto[];
  actionsPrioritaires: FindingDto[];
  blockingItems: FindingDto[];
}

export const LIBELLES_STATUT_DOSSIER: Record<string, string> = {
  PRET: 'Prêt',
  A_SURVEILLER: 'À surveiller',
  BLOQUE: 'Bloqué',
};

export type StatutControleCloture = 'OK' | 'WARNING' | 'BLOCKING' | 'NOT_APPLICABLE';

export interface ControlItemDto {
  code: string;
  libelle: string;
  statut: StatutControleCloture;
  severite: string | null;
  message: string | null;
  evidence: string | null;
  domaine?: string | null;
}

export interface ClotureReadinessDto {
  statut: 'READY' | 'READY_WITH_WARNINGS' | 'NOT_READY';
  blockingItems: string[];
  warnings: string[];
  recommendations: string[];
  checklist: ControlItemDto[];
  resume?: { bloquants: number; avertissements: number; enOrdre: number; nonApplicables: number } | null;
  evaluatedAt?: string | null;
  controlesExecutesLe?: string | null;
}

export const LIBELLES_STATUT_CONTROLE: Record<StatutControleCloture, string> = {
  OK: '✓',
  WARNING: '⚠',
  BLOCKING: '🔴',
  NOT_APPLICABLE: '—',
};

export const LIBELLES_CLOTURE_READINESS: Record<string, string> = {
  READY: 'Prêt à clôturer',
  READY_WITH_WARNINGS: 'Prêt, sous réserve de revue',
  NOT_READY: 'Pas prêt',
};
