/** Inventaire physique et regularisation d'ecart (mission stock, Etape 3) : une regularisation ne
 * cree jamais une ecriture directement - elle passe par une AccountingOperationProposal (moteur
 * commun de l'Etape 2), affichee ici via propositionId/ecritureId une fois validee. */

export type StatutInventaire = 'BROUILLON' | 'EN_COURS' | 'VALIDE' | 'CLOTURE';

export const LIBELLES_STATUT_INVENTAIRE: Record<StatutInventaire, string> = {
  BROUILLON: 'Brouillon',
  EN_COURS: 'En cours',
  VALIDE: 'Validé — en attente de comptabilisation',
  CLOTURE: 'Clôturé',
};

export interface LigneInventaireDto {
  id: string;
  articleId: string;
  articleCode: string;
  articleDesignation: string;
  stockTheorique: number;
  stockCompte: number;
  ecart: number;
  coutUnitaire: number;
  valeurEcart: number;
}

export interface InventaireDto {
  id: string;
  date: string;
  statut: StatutInventaire;
  commentaire: string | null;
  nbLignes: number;
  valeurEcartTotal: number;
  lignes: LigneInventaireDto[];
  propositionId: string | null;
  ecritureId: string | null;
  createdAt: string;
}

export interface CreerInventaireRequest {
  date: string;
  commentaire?: string;
}

export interface SaisirLigneInventaireRequest {
  articleId: string;
  stockCompte: number;
  coutUnitaireManuel?: number;
}
