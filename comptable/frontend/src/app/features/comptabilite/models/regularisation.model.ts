/** Regularisations de fin de periode (mission cloture Etape 4) : CCA/PCA/FNP/CAP/PAR. Une
 * regularisation ne cree jamais une ecriture directement - elle passe par une
 * AccountingOperationProposal (moteur commun), validee depuis l'ecran Operations existant, exactement
 * comme l'inventaire (voir models/inventaire.model.ts). */

export type RegularisationType =
  | 'CHARGE_CONSTATEE_AVANCE'
  | 'PRODUIT_CONSTATE_AVANCE'
  | 'FACTURE_NON_PARVENUE'
  | 'PRODUIT_A_RECEVOIR'
  | 'CHARGE_A_PAYER'
  | 'PRODUIT_A_REPORTER';

export const LIBELLES_TYPE_REGULARISATION: Record<RegularisationType, string> = {
  CHARGE_CONSTATEE_AVANCE: 'Charge constatée d\'avance (CCA)',
  PRODUIT_CONSTATE_AVANCE: 'Produit constaté d\'avance (PCA)',
  FACTURE_NON_PARVENUE: 'Facture non parvenue (FNP)',
  PRODUIT_A_RECEVOIR: 'Produit à recevoir',
  CHARGE_A_PAYER: 'Charge à payer',
  PRODUIT_A_REPORTER: 'Produit à reporter',
};

export type StatutRegularisation = 'BROUILLON' | 'PROPOSEE' | 'VALIDEE' | 'REJETEE';

export const LIBELLES_STATUT_REGULARISATION: Record<StatutRegularisation, string> = {
  BROUILLON: 'Brouillon',
  PROPOSEE: 'Proposée — en attente de validation',
  VALIDEE: 'Validée',
  REJETEE: 'Rejetée',
};

export interface RegularisationDto {
  id: string;
  exerciceId: string;
  type: RegularisationType;
  dateOrigine: string;
  dateDebut: string | null;
  dateFin: string | null;
  montant: number;
  montantExercice: number | null;
  montantFutur: number | null;
  libelle: string;
  compteOrigineId: string;
  compteOrigineNumero: string;
  compteContrepartieId: string;
  compteContrepartieNumero: string;
  statut: StatutRegularisation;
  propositionId: string | null;
  ecritureId: string | null;
  repriseAutomatique: boolean;
  dateReprise: string | null;
  propositionRepriseId: string | null;
  ecritureRepriseId: string | null;
  createdAt: string;
}

export interface CreerRegularisationRequest {
  exerciceId: string;
  type: RegularisationType;
  dateOrigine: string;
  dateDebut?: string;
  dateFin?: string;
  montant: number;
  libelle: string;
  compteOrigineId: string;
  compteContrepartieId: string;
  repriseAutomatique?: boolean;
}
