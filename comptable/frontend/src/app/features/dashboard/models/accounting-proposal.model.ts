export type StatutAccountingProposal = 'PROPOSED' | 'ACCEPTED' | 'REJECTED' | 'APPLIED';

export interface AccountingProposalDto {
  id: string;
  documentId: string;
  journalCode: string | null;
  tiersId: string | null;
  tiersNom: string | null;
  compteChargeProduitId: string | null;
  compteChargeProduitNumero: string | null;
  montantHt: number | null;
  montantTva: number | null;
  montantTtc: number | null;
  confidence: number;
  status: StatutAccountingProposal;
  reasons: string[];
  createdAt: string;
  validatedAt: string | null;
}
