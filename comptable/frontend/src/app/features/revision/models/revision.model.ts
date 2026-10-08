export type SeveriteControle = 'INFO' | 'WARNING' | 'ERROR' | 'BLOCKING';
export type CategorieControle =
  | 'ACCOUNTING'
  | 'DOCUMENT'
  | 'TAX'
  | 'RECONCILIATION'
  | 'THIRD_PARTY'
  | 'STOCK'
  | 'FIXED_ASSET'
  | 'CLOSING'
  | 'ANOMALY';
export type StatutConstat = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'DISMISSED';
export type TuileRevue =
  | 'BLOQUANTS'
  | 'ERREURS'
  | 'ANOMALIES'
  | 'PIECES_MANQUANTES'
  | 'IMPUTATIONS'
  | 'RAPPROCHEMENTS';

export const LIBELLES_SEVERITE: Record<SeveriteControle, string> = {
  BLOCKING: 'Bloquant',
  ERROR: 'Erreur',
  WARNING: 'À traiter',
  INFO: 'À vérifier',
};

export const LIBELLES_CATEGORIE: Record<CategorieControle, string> = {
  ACCOUNTING: 'Comptabilité',
  DOCUMENT: 'Pièces',
  TAX: 'Fiscalité',
  RECONCILIATION: 'Rapprochement',
  THIRD_PARTY: 'Tiers',
  STOCK: 'Stock',
  FIXED_ASSET: 'Immobilisations',
  CLOSING: 'Clôture',
  ANOMALY: 'Anomalie',
};

export const LIBELLES_OBJET: Record<string, string> = {
  DOCUMENT: 'Document',
  ECRITURE_COMPTABLE: 'Écriture',
  ECRITURES_BROUILLON: 'Écritures brouillon',
  PIECES_MANQUANTES: 'Pièces manquantes',
  LIGNE_RELEVE: 'Ligne de relevé',
  RELEVE: 'Relevé bancaire',
  IMMOBILISATION: 'Immobilisation',
  TIERS: 'Tiers',
  ARTICLE: 'Article',
  FACTURE: 'Facture',
  LIGNE_ECRITURE: 'Règlement',
  DECLARATION_FISCALE: 'Déclaration fiscale',
  DECLARATION_FISCALE_PERIODE: 'Période fiscale',
};

/** Page de l'espace entreprise où traiter un objet (navigation depuis une ligne de revue). */
export const ROUTE_OBJET: Record<string, string> = {
  DOCUMENT: 'validation',
  ECRITURE_COMPTABLE: 'ecritures',
  ECRITURES_BROUILLON: 'ecritures',
  PIECES_MANQUANTES: 'documents',
  LIGNE_RELEVE: 'rapprochement',
  RELEVE: 'rapprochement',
  IMMOBILISATION: 'immobilisations',
  TIERS: 'tiers',
  ARTICLE: 'stocks',
  FACTURE: 'facturation',
  LIGNE_ECRITURE: 'rapprochement',
  DECLARATION_FISCALE: 'fiscalite',
  DECLARATION_FISCALE_PERIODE: 'fiscalite',
};

/** Page où traiter un constat : le code prime sur le type d'objet (un document à risque se valide, un doublon se traite dans le Hub). */
export function routePour(constat: { code: string; entityType: string }): string | null {
  if (constat.code === 'DOCUMENT_AT_RISK') {
    return 'validation';
  }
  if (constat.entityType === 'DOCUMENT' || constat.entityType === 'DOCUMENTS') {
    return 'documents';
  }
  return ROUTE_OBJET[constat.entityType] ?? null;
}

export interface TuileDto {
  code: TuileRevue;
  libelle: string;
  nombre: number;
}

export interface SyntheseRevueDto {
  totalActifs: number;
  bloquantPourCloture: boolean;
  tuiles: TuileDto[];
  parSeverite: Record<string, number>;
  parCategorie: Record<string, number>;
  derniereExecution: string | null;
}

export interface ConstatDto {
  id: string;
  domaine: DomaineControle;
  domaineLibelle: string;
  code: string;
  category: CategorieControle;
  severity: SeveriteControle;
  status: StatutConstat;
  blocking: boolean;
  entityType: string;
  entityId: string;
  message: string;
  explanation: string | null;
  suggestedAction: string | null;
  evidence: Record<string, unknown>;
  relatedEntityIds: string[];
  detectedAt: string;
  createdAt: string;
  resolutionType: string | null;
  resolutionComment: string | null;
  resolvedAt: string | null;
  assignedToId: string | null;
  assignedToNom: string | null;
}

export interface PageConstatsDto {
  contenu: ConstatDto[];
  page: number;
  taille: number;
  total: number;
  totalPages: number;
}

export interface ExecutionControlesDto {
  detectes: number;
  crees: number;
  misAJour: number;
  resolus: number;
}

export interface FiltreRevue {
  domaine?: DomaineControle | '';
  tuile?: TuileRevue | '';
  severite?: SeveriteControle | '';
  categorie?: CategorieControle | '';
  statut?: StatutConstat | '';
  entityType?: string;
  du?: string;
  au?: string;
  q?: string;
  page?: number;
  taille?: number;
}

export type DomaineControle =
  | 'BANK'
  | 'CUSTOMER'
  | 'SUPPLIER'
  | 'DOCUMENT'
  | 'VAT'
  | 'STOCK'
  | 'FIXED_ASSET'
  | 'JOURNAL'
  | 'ACCOUNTING'
  | 'CLOSING'
  | 'OTHER';

export interface DomaineRevueDto {
  code: DomaineControle;
  libelle: string;
  bloquants: number;
  avertissements: number;
  aExaminer: number;
  total: number;
}

/** Espace de révision : la clôture est-elle possible, combien de bloquants / avertissements / à examiner, résolus du jour, détail par domaine. */
export interface EspaceRevueDto {
  pret: boolean;
  statutCloture: 'READY' | 'READY_WITH_WARNINGS' | 'NOT_READY';
  bloquants: number;
  avertissements: number;
  aExaminer: number;
  resolusAujourdhui: number;
  domaines: DomaineRevueDto[];
  derniereExecution: string | null;
}

export interface PointPreparationDto {
  code: string;
  libelle: string;
  statut: 'OK' | 'WARNING' | 'BLOCKING' | 'NOT_APPLICABLE';
  message: string | null;
  explanation: string | null;
  domaine: DomaineControle | null;
  entityType: string | null;
  entityId: string | null;
}

export interface PreparationClotureDto {
  statut: 'READY' | 'READY_WITH_WARNINGS' | 'NOT_READY';
  blockingItems: string[];
  warnings: string[];
  checklist: PointPreparationDto[];
  resume: { bloquants: number; avertissements: number; enOrdre: number; nonApplicables: number } | null;
  evaluatedAt: string | null;
  controlesExecutesLe: string | null;
}

// ---------------------------------------------------------------- « Pourquoi ? » (investigation)

export type NatureCause = 'ANOMALY' | 'EXPLANATION' | 'INFO';

export interface PreuveDto {
  kind: string;
  entityType: string;
  entityId: string | null;
  label: string;
  amount: number | null;
  date: string | null;
}

export interface CauseDto {
  code: string;
  nature: NatureCause;
  explanation: string;
  basis: 'PERSISTED' | 'LINEAGE' | 'EXACT' | 'HEURISTIC';
  amount: number | null;
  evidence: PreuveDto[];
}

export interface EvenementChronologieDto {
  date: string | null;
  type: string;
  label: string;
  amount: number | null;
  entityType: string | null;
  entityId: string | null;
}

export interface ObjetLieDto {
  type: string;
  id: string;
  label: string;
}

export interface ActionSuggereeDto {
  code: string;
  label: string;
  operation: string;
  targetType: string | null;
  targetId: string | null;
}

export interface InvestigationDto {
  subject: { type: string; id: string; label: string };
  summary: string;
  comparison: { expectedLabel: string; expected: number | null; actualLabel: string; actual: number | null; difference: number | null } | null;
  causes: CauseDto[];
  evidence: PreuveDto[];
  timeline: EvenementChronologieDto[];
  relatedEntities: ObjetLieDto[];
  suggestedActions: ActionSuggereeDto[];
  details: Record<string, unknown>;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  generatedAt: string;
}

export const LIBELLES_DOMAINE: Record<DomaineControle, string> = {
  BANK: 'Banque',
  CUSTOMER: 'Clients',
  SUPPLIER: 'Fournisseurs',
  DOCUMENT: 'Documents',
  VAT: 'TVA',
  STOCK: 'Stock',
  FIXED_ASSET: 'Immobilisations',
  JOURNAL: 'Journal',
  ACCOUNTING: 'Comptabilité',
  CLOSING: 'Clôture',
  OTHER: 'Autres',
};

/** Explication courte de chaque code de cause (le code lui-même reste stable ; ce texte n'est qu'un libellé d'affichage). */
export const LIBELLES_CAUSE: Record<string, string> = {
  UNALLOCATED_PAYMENT: 'Règlement non affecté',
  PARTIAL_PAYMENT: 'Facture partiellement réglée',
  UNPAID_INVOICE: 'Facture non réglée',
  OVERPAYMENT: 'Trop-perçu',
  PAYMENT_WITHOUT_INVOICE: 'Règlement sans facture',
  CREDIT_NOTE_NOT_APPLIED: 'Avoir non imputé',
  CREDIT_BALANCE_EXPLAINED: 'Solde créditeur expliqué',
  CREDIT_BALANCE_UNEXPLAINED: 'Solde créditeur inexpliqué',
  DEBIT_BALANCE_EXPLAINED: 'Solde débiteur expliqué',
  DEBIT_BALANCE_UNEXPLAINED: 'Solde débiteur inexpliqué',
  NORMAL_BALANCE: 'Solde normal',
  OPEN_INVOICES_AVAILABLE: 'Factures ouvertes disponibles',
  BANK_TRANSACTION_UNMATCHED: 'Transaction bancaire sans écriture',
  BANK_MATCH_AMBIGUOUS: 'Plusieurs écritures possibles',
  BANK_MATCH_TO_CONFIRM: 'Rapprochement à confirmer',
  BANK_RECONCILIATION_REVERSED: 'Rapprochement annulé',
  BOOKED_NOT_ON_STATEMENT: 'Écritures absentes du relevé',
  OPENING_BALANCE_MISMATCH: "Solde d'ouverture différent",
  BANK_DIFFERENCE_UNEXPLAINED: 'Écart bancaire inexpliqué',
  INTERNAL_TRANSFER_PENDING: 'Virement interne à confirmer',
  INTERNAL_TRANSFER_WITHOUT_ENTRY: 'Virement interne sans écriture',
  BANK_DUPLICATE_TRANSACTION: 'Doublon bancaire possible',
  BANK_RECONCILED: 'Rapprochée',
  MISSING_DOCUMENT: 'Pièce manquante',
  INVOICE_NOT_POSTED: 'Facture non comptabilisée',
  DUPLICATE_INVOICE: 'Facture en double',
  FIXED_ASSET_WITHOUT_SOURCE: 'Immobilisation sans acquisition',
  POSSIBLE_DOUBLE_ACQUISITION: 'Double acquisition possible',
  ALLOCATION_IMBALANCE: 'Affectations déséquilibrées',
  DOCUMENT_REQUEST_PENDING: 'Demande de pièce en cours',
};

/** Page de l'espace entreprise où traiter un objet d'une investigation (sans jamais montrer d'identifiant). */
export const ROUTE_INVESTIGATION: Record<string, string> = {
  TIERS: 'tiers',
  ECRITURE: 'ecritures',
  LIGNE_ECRITURE: 'rapprochement',
  LIGNE_RELEVE: 'rapprochement',
  RELEVE: 'rapprochement',
  DOCUMENT: 'documents',
  FACTURE: 'facturation',
  IMMOBILISATION: 'immobilisations',
  DEMANDE_PIECE: 'demandes-pieces',
};
