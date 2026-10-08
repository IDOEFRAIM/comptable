import { EspaceNotesDto, NoteDto, SectionNote, SyntheseDossierDto } from './dossier-financier.model';

/** Fabriques de données de test (contrats backend) : aucune règle métier, uniquement des DTO. */

export function section(code: string, label: string, status: SectionNote['status'] = 'MISSING', valeur: string | null = null): SectionNote {
  return {
    sectionCode: code,
    label,
    sourceType: 'MANUAL',
    required: true,
    status,
    fields: [{ fieldCode: 'contenu', label, type: 'TEXT', required: true, filled: valeur !== null, value: valeur, comment: null, source: null }],
  };
}

export function note(partiel: Partial<NoteDto> = {}): NoteDto {
  return {
    noteCode: '17',
    title: "Fournisseurs d'exploitation",
    frameworkVersion: 'SYSCOHADA_REVISE_AUDCIF_2017/2',
    definitionVersion: '2',
    applicabilityStatus: 'APPLICABLE',
    applicabilityDecision: { decidedBy: null, decidedAt: null, reason: null, humaine: false },
    completionStatus: 'INCOMPLETE',
    automationType: 'MIXED',
    readiness: 'IMPLEMENTED',
    schemaStatus: 'COMPLETE',
    attached: true,
    generatedSections: ['dettes fournisseurs'],
    manualSections: [section('groupe', 'groupe'), section('echeances', 'échéances')],
    missingSections: ['groupe', 'echeances'],
    generatedData: [{ code: 'a1', label: 'Fournisseurs, dettes en compte', kind: 'LINE', amountN: 300000, amountNMinus1: null, accounts: ['401'] }],
    gaps: [],
    manualComment: null,
    preparedBy: null,
    preparedAt: null,
    review: { reviewedBy: null, reviewedAt: null, valid: false },
    version: 3,
    ...partiel,
  };
}

export function espace(notes: NoteDto[]): EspaceNotesDto {
  return { exerciceId: 'ex-1', frameworkVersion: 'SYSCOHADA_REVISE_AUDCIF_2017/2', status: 'INCOMPLETE', statusReasons: [], countsByStatus: {}, notes };
}

export function synthese(partiel: Partial<SyntheseDossierDto> = {}): SyntheseDossierDto {
  return {
    exerciseId: 'ex-1',
    debut: '2026-01-01',
    fin: '2026-12-31',
    exerciseStatus: 'CLOTURE',
    incomeStatement: { status: 'READY', reasons: [] },
    balanceSheet: { status: 'READY', reasons: [] },
    balanceCheck: { totalActif: 1000, totalPassif: 1000, ecart: 0, equilibre: true },
    cashFlow: { status: 'READY', reasons: [] },
    notes: { total: 46, decided: 46, toDecide: 0, candidates: 0, toComplete: 0, ready: 4, reviewed: 0, notApplicable: 42, blocked: 0, status: 'READY', reasons: [] },
    blockingFindings: 0,
    warnings: 0,
    finalizable: true,
    finalised: false,
    checklist: [{ code: 'EXERCICE', label: 'Exercice cloture', status: 'OK', detail: 'Cloture' }],
    exceptions: [],
    snapshot: null,
    permissions: { prepare: true, review: true, finalise: true },
    generatedAt: '2026-10-05T12:00:00Z',
    ...partiel,
  };
}
