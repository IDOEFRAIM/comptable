export interface EvidenceRefDto {
  sourceType: string;
  sourceId: string;
}

export interface CopilotResponseDto {
  answer: string;
  confidence: number;
  evidence: EvidenceRefDto[];
  recommendedActions: string[];
  queryId: string | null;
}

export type CopilotFeedback = 'POSITIVE' | 'NEGATIVE';
