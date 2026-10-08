/** Contrat de l'espace TVA : tous les montants sont calculés par le backend (BigDecimal) ; l'écran les affiche, il n'effectue aucun calcul fiscal. */

export type StatutPeriode = 'OPEN' | 'PREPARING' | 'READY_FOR_REVIEW' | 'REVIEWED' | 'FINAL';

export type Traitement = 'OUTPUT_TAX' | 'INPUT_TAX' | 'EXEMPT' | 'OUT_OF_SCOPE' | 'NON_DEDUCTIBLE' | 'PARTIALLY_DEDUCTIBLE' | 'TO_REVIEW';

export interface PeriodeResume {
  id: string;
  periodStart: string;
  periodEnd: string;
  status: StatutPeriode;
  frameworkVersion: string | null;
  frameworkVerified: boolean;
  version: number;
}

export interface Cadre {
  id: string;
  jurisdiction: string;
  version: string;
  status: string;
  verifie: boolean;
  definitionHash: string;
  sourceReferences: string[];
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface Totaux {
  outputBase: number;
  outputVatAccounted: number;
  outputVatFiscal: number | null;
  inputBase: number;
  inputVatAccounted: number;
  inputVatDeductible: number | null;
  adjustmentsOutput: number;
  adjustmentsInput: number;
  declaredOutput: number;
  declaredInput: number;
  netDeclared: number;
  netAccounted: number;
  unexplained: number;
  toReview: number;
  operations: number;
  fiscalComplete: boolean;
}

export interface Rapprochement {
  ledgerOutput: number | null;
  ledgerInput: number | null;
  operationsOutput: number;
  operationsInput: number;
  ledgerGap: number | null;
}

export interface Permissions {
  prepare: boolean;
  review: boolean;
  finalise: boolean;
}

export interface Periode {
  id: string;
  entrepriseId: string;
  periodStart: string;
  periodEnd: string;
  status: StatutPeriode;
  framework: Cadre | null;
  totals: Totaux;
  reconciliation: Rapprochement;
  basis: 'FISCAL_VERIFIED' | 'ACCOUNTED_UNVERIFIED';
  officialStructure: boolean;
  anomalies: number;
  toReview: number;
  finalisable: boolean;
  finalisationBlockers: string[];
  permissions: Permissions;
  snapshotAvailable: boolean;
  /** Jeton de concurrence : renvoyé en expectedVersion à chaque modification (409 si périmé). */
  version: number;
  regulatoryPackVerified: boolean;
  /** Codes machine des raisons de blocage de la finalisation (ex. REGULATORY_PACK_NOT_VERIFIED). */
  finalisationReasons: string[];
  progress: Progression;
  payment: Reglement;
  credit: Credit;
  /** Date limite issue de la règle du cadre vérifié ; null sans règle vérifiée. */
  deadline: string | null;
}

export type StatutOperationnel = 'A_PREPARER' | 'EN_PREPARATION' | 'ATTENTION_REQUISE' | 'PRET_POUR_REVUE' | 'EN_REVUE' | 'PRET_A_FINALISER' | 'FINALISE' | 'A_PAYER' | 'PARTIELLEMENT_PAYE' | 'PAYE' | 'CREDIT_A_REPORTER';

export interface EtapeProgression {
  code: string;
  label: string;
  ready: boolean;
  blocking: boolean;
}

/** Progression en étapes (jamais un pourcentage) et statut opérationnel, calculés par le backend. */
export interface Progression {
  status: StatutOperationnel;
  readySteps: number;
  totalSteps: number;
  blockingSteps: string[];
  steps: EtapeProgression[];
}

export interface Paiement {
  id: string;
  amount: number;
  paymentDate: string;
  reference: string | null;
  source: string;
  status: 'ACTIVE' | 'REVERSED';
  createdAt: string;
  reversedAt: string | null;
  reverseReason: string | null;
}

/** Suivi interne du règlement : montant déclaré / payé / restant (le montant déclaré n'existe qu'une fois la période finalisée). */
export interface Reglement {
  declaredAmount: number | null;
  paidAmount: number;
  remainingAmount: number | null;
  status: 'NON_FINALISE' | 'AUCUN_MONTANT_DU' | 'A_PAYER' | 'PARTIELLEMENT_PAYE' | 'PAYE';
  paymentOpen: boolean;
  payments: Paiement[];
}

export interface Credit {
  openingAmount: number | null;
  reason: string | null;
  source: string | null;
  previousPeriodId: string | null;
  createdAt: string | null;
  openingState: 'NONE' | 'TO_REVIEW' | 'VALIDATED';
  closingAmount: number | null;
  closingState: 'NONE' | 'TO_REVIEW' | 'REPORTABLE';
}

export interface FiltresOperations {
  cote: 'SALES' | 'PURCHASES';
  statut: 'ALL' | 'TO_REVIEW' | 'OK';
  traitement: string;
  constatActif: boolean;
  q: string;
  tri: 'date' | 'document' | 'amount' | 'status' | 'tiers';
  sens: 'asc' | 'desc';
}

export interface LigneTva {
  ecritureId: string;
  date: string;
  piece: string | null;
  document: string | null;
  tiers: string | null;
  libelle: string | null;
  side: 'OUTPUT' | 'INPUT' | 'UNDETERMINED';
  treatment: Traitement;
  treatmentSource: 'DECISION' | 'DERIVED';
  base: number;
  impliedRatePercent: number | null;
  rateCode: string | null;
  vatAccounted: number;
  vatFiscal: number | null;
  vatDeductible: number | null;
  ecart: number | null;
  explication: string | null;
  status: 'OK' | 'TO_REVIEW';
  issues: string[];
}

export interface Page<T> {
  items: T[];
  page: number;
  taille: number;
  total: number;
}

export interface Anomalie {
  code: string;
  ecritureId: string | null;
  key: string | null;
  message: string;
}

export interface Controles {
  anomalies: Anomalie[];
  reconciliation: Rapprochement;
  unexplained: number;
}

export interface Ajustement {
  id: string;
  kind: string;
  effect: 'OUTPUT' | 'INPUT';
  amount: number;
  reason: string;
  evidence: string | null;
  ecritureId: string | null;
  createdAt: string;
  cancelled: boolean;
  cancelReason: string | null;
}

export interface LigneDeclaration {
  code: string;
  label: string;
  source: string;
  amount: number | null;
}

export interface Declaration {
  id: string | null;
  status: string;
  basis: string;
  officialStructure: boolean;
  framework: Cadre | null;
  lines: LigneDeclaration[];
  reviewedAt: string | null;
  finalizedAt: string | null;
  snapshotId: string | null;
  snapshotSha256: string | null;
  finalisable: boolean;
  finalisationBlockers: string[];
  finalisationReasons: string[];
}

export interface Origine {
  operation: LigneTva;
  ecritureLignes: { compte: string; intitule: string; libelle: string; debit: number; credit: number }[];
  facture: Record<string, unknown> | null;
  documents: Record<string, unknown>[];
}
