// Contrats du dossier financier SYSCOHADA (backend : etatsfinanciers). Le frontend ne recalcule aucune règle : il affiche les statuts et montants fournis.

export type StatutEtat = 'DRAFT' | 'READY' | 'INCOMPLETE' | 'FINAL';
export type TypeEtat = 'COMPTE_RESULTAT' | 'BILAN_ACTIF' | 'BILAN_PASSIF' | 'TFT';

export interface LigneAccountDto {
  accountId: string;
  number: string;
  label: string;
  role: string;
  amountN: number | null;
  amountNMinus1: number | null;
}

export interface LigneEtatDto {
  code: string;
  label: string;
  order: number;
  calculationType: string;
  verificationStatus: string;
  provenanceType: string;
  amountN: number | null;
  amountNMinus1: number | null;
  /** N − N-1 calculée par le backend (BigDecimal) ; null si une valeur est inconnue. */
  variation: number | null;
  grossN: number | null;
  depreciationN: number | null;
  notCalculableReason: string | null;
  accounts: LigneAccountDto[];
  complete: boolean;
  incompleteReasons: string[];
}

export interface CompteRefDto {
  accountId: string;
  number: string;
  label: string;
  balanceN: number;
}

export interface EtatDto {
  statementType: TypeEtat;
  framework: string;
  version: string;
  definitionHash: string;
  exerciceId: string;
  exerciceDebut: string;
  exerciceFin: string;
  exercicePrecedentId: string | null;
  precedentDebut: string | null;
  precedentFin: string | null;
  status: StatutEtat;
  statusReasons: string[];
  lines: LigneEtatDto[];
  unmapped: CompteRefDto[];
  ambiguous: { accountId: string; number: string; label: string; candidateLines: string[] }[];
  controls: { code: string; message: string; explanation: string; metadata: Record<string, unknown> }[];
}

export interface BilanDto {
  actif: EtatDto;
  passif: EtatDto;
  status: StatutEtat;
  reasons: string[];
  totalActifN: number;
  totalPassifN: number;
  resultatBilanN: number;
  resultatCompteResultatN: number;
}

export interface EtatSynthese {
  status: StatutEtat;
  reasons: string[];
}

export interface ControleEquilibre {
  totalActif: number;
  totalPassif: number;
  ecart: number;
  equilibre: boolean;
}

export interface ProgressionNotes {
  total: number;
  decided: number;
  toDecide: number;
  candidates: number;
  toComplete: number;
  ready: number;
  reviewed: number;
  notApplicable: number;
  blocked: number;
  status: StatutEtat;
  reasons: string[];
}

export interface PointChecklist {
  code: string;
  label: string;
  status: 'OK' | 'BLOCKED' | 'WARNING';
  detail: string;
}

export interface ExceptionDossier {
  id: string;
  code: string;
  severity: 'BLOCKING' | 'WARNING';
  category: string;
  title: string;
  detail: string;
  tab: string;
  target: Record<string, any>;
}

export interface SnapshotInfo {
  id: string;
  createdAt: string;
  createdBy: string | null;
  framework: string;
  definitionVersion: string;
  payloadSha256: string;
  definitionHash: string;
  liveChangedSinceFinal: boolean;
}

export interface SyntheseDossierDto {
  exerciseId: string;
  debut: string;
  fin: string;
  exerciseStatus: 'OUVERT' | 'CLOTURE';
  incomeStatement: EtatSynthese;
  balanceSheet: EtatSynthese;
  balanceCheck: ControleEquilibre;
  cashFlow: EtatSynthese;
  notes: ProgressionNotes;
  blockingFindings: number;
  warnings: number;
  finalizable: boolean;
  finalised: boolean;
  checklist: PointChecklist[];
  exceptions: ExceptionDossier[];
  snapshot: SnapshotInfo | null;
  permissions: { prepare: boolean; review: boolean; finalise: boolean };
  generatedAt: string;
}

export interface ComposantFluxDto {
  ligneTft: string;
  ecritureId: string;
  numeroPiece: string;
  date: string;
  evenement: string;
  montant: number;
  lineage: { operationType: string; sourceType: string; sourceId: string; sourceEvent: string }[];
}

export interface EntreeEcritureDto {
  ecritureId: string;
  ligneId: string;
  date: string;
  journal: string;
  numeroPiece: string;
  libelle: string;
  nature: string;
  debit: number;
  credit: number;
  lineage: { operationType: string; sourceType: string; sourceId: string; sourceEvent: string }[];
}

export interface DetailLigneDto {
  statementType: TypeEtat;
  lineCode: string;
  exerciceId: string;
  accounts: LigneAccountDto[];
  account: {
    accountId: string;
    number: string;
    label: string;
    opening: number;
    movementDebit: number;
    movementCredit: number;
    closing: number;
    entries: EntreeEcritureDto[];
  } | null;
  components: ComposantFluxDto[];
}

export interface OptionEvenement {
  code: string;
  libelle: string;
  ligneTft: string | null;
  encaissement: boolean;
}

// ------------------------------------------------------------------ Notes

export interface ChampEtat {
  fieldCode: string;
  label: string;
  type: 'TEXT' | 'DECIMAL' | 'INTEGER' | 'DATE' | 'BOOLEAN' | 'ENUM';
  required: boolean;
  filled: boolean;
  value: string | null;
  comment: string | null;
  source: string | null;
  /** Valeurs autorisées d'un champ ENUM (référentiel du backend). */
  options?: string[];
}

export interface SectionNote {
  sectionCode: string;
  label: string;
  sourceType: string;
  required: boolean;
  status: 'GENERATED' | 'COMPLETE' | 'MISSING' | 'OPTIONAL';
  fields: ChampEtat[];
}

export interface LigneNote {
  code: string;
  label: string;
  kind: string;
  amountN: number | null;
  amountNMinus1: number | null;
  accounts: string[];
}

export interface ManqueNote {
  noteCode: string;
  section: string;
  missingInformation: string;
  kind: string;
  fieldCodes: string[];
}

export type ApplicabiliteNote = 'TO_DETERMINE' | 'NOT_APPLICABLE_CANDIDATE' | 'NOT_APPLICABLE' | 'APPLICABLE';
export type CompletudeNote = 'INCOMPLETE' | 'READY' | 'MANUAL_REQUIRED' | 'NOT_APPLICABLE';

export interface NoteDto {
  noteCode: string;
  title: string;
  frameworkVersion: string;
  definitionVersion: string;
  applicabilityStatus: ApplicabiliteNote;
  applicabilityDecision: { decidedBy: string | null; decidedAt: string | null; reason: string | null; humaine: boolean };
  completionStatus: CompletudeNote;
  automationType: 'AUTOMATIC' | 'MIXED' | 'MANUAL_REQUIRED';
  readiness: string;
  schemaStatus: 'COMPLETE' | 'PARTIAL';
  attached: boolean;
  generatedSections: string[];
  manualSections: SectionNote[];
  missingSections: string[];
  generatedData: LigneNote[];
  gaps: ManqueNote[];
  manualComment: string | null;
  preparedBy: string | null;
  preparedAt: string | null;
  review: { reviewedBy: string | null; reviewedAt: string | null; valid: boolean };
  version: number;
}

export interface EspaceNotesDto {
  exerciceId: string;
  frameworkVersion: string;
  status: StatutEtat;
  statusReasons: string[];
  countsByStatus: Record<string, number>;
  notes: NoteDto[];
}

export interface PatchChamp {
  sectionCode: string;
  fieldCode: string;
  value: string | null;
  comment?: string | null;
  source?: string | null;
}

// ------------------------------------------------------------------ Snapshot

export interface SnapshotDto {
  id: string;
  exerciceId: string;
  statementType: string;
  framework: string;
  definitionVersion: string;
  definitionHash: string;
  status: string;
  datasetFingerprint: string;
  payloadSha256: string;
  createdAt: string;
  payload: any;
}
