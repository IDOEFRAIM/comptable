export type StatutImputationSuggestion = 'PROPOSED' | 'ACCEPTED' | 'REJECTED' | 'APPLIED';

export interface ImputationSuggestionDto {
  id: string;
  documentId: string;
  tiersId: string | null;
  tiersNom: string | null;
  compteChargeProduitId: string | null;
  compteChargeProduitNumero: string | null;
  montantHt: number | null;
  montantTva: number | null;
  montantTtc: number | null;
  confidence: number;
  status: StatutImputationSuggestion;
  reasons: string[];
  createdAt: string;
  validatedAt: string | null;
}
