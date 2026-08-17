export interface HumanTouchRateDto {
  automatique: number;
  assisteParIa: number;
  humainSeul: number;
  enAttente: number;
  totalResolu: number;
  humanTouchRatePourcent: number | null;
  automationRatePourcent: number | null;
}

export interface MatchingQualityDto {
  domaine: string;
  propositions: number;
  acceptees: number;
  rejetees: number;
  enAttente: number;
  expirees: number;
  tauxAcceptationPourcent: number | null;
  tauxRejetPourcent: number | null;
}

export interface EvidenceResolutionDto {
  creees: number;
  completees: number;
  annulees: number;
  enAttente: number;
  tauxResolutionPourcent: number | null;
  dureeMoyenneResolutionHeures: number | null;
}

export interface FindingResolutionTimeDto {
  nombreResolu: number;
  moyenneHeures: number | null;
  medianeHeures: number | null;
  p90Heures: number | null;
}

export interface CopilotUsageDto {
  questions: number;
  reponsesAvecPreuve: number;
  fallback: number;
  latenceMoyenneMs: number | null;
  feedbackPositif: number;
  feedbackNegatif: number;
  sansFeedback: number;
  tauxFallbackPourcent: number | null;
  tauxFeedbackPositifPourcent: number | null;
  tauxFeedbackNegatifPourcent: number | null;
  tauxReponseAvecPreuvePourcent: number | null;
}

export interface WorkQueueDto {
  findingsOuverts: number;
  findingsEnRetard: number;
  demandesEnAttente: number;
  demandesEnRetard: number;
}

export interface ProductKpiDto {
  humanTouchRate: HumanTouchRateDto;
  matchingBancaire: MatchingQualityDto;
  comptabilisation: MatchingQualityDto;
  resolutionPreuves: EvidenceResolutionDto;
  tempsResolutionFindings: FindingResolutionTimeDto;
  usageCopilot: CopilotUsageDto;
  workQueue: WorkQueueDto;
}
