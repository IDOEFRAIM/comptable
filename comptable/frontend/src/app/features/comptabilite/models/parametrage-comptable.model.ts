/** Meme liste minimale que le backend (AccountPurpose) - un purpose n'existe ici que si un module
 * le consomme reellement, jamais ajoute par anticipation. */
export type AccountPurpose =
  | 'ACHAT_MARCHANDISE'
  | 'ACHAT_MATIERE'
  | 'ACHAT_CONSOMMABLE'
  | 'ACHAT_SERVICE'
  | 'ACHAT_FRAIS_ACCESSOIRE'
  | 'VENTE_MARCHANDISE'
  | 'VENTE_PRODUIT'
  | 'VENTE_SERVICE'
  | 'TVA_COLLECTEE'
  | 'TVA_RECUPERABLE'
  | 'STOCK_MARCHANDISE'
  | 'STOCK_MATIERE'
  | 'VARIATION_STOCK'
  | 'CLIENT'
  | 'FOURNISSEUR'
  | 'BANQUE'
  | 'CAISSE'
  | 'IMMOBILISATION_CORPORELLE'
  | 'IMMOBILISATION_INCORPORELLE'
  | 'AMORTISSEMENT'
  | 'DOTATION_AMORTISSEMENT'
  | 'RESULTAT_BENEFICE'
  | 'RESULTAT_PERTE';

/** Regroupement d'affichage uniquement (frontend) - un purpose peut appartenir a un seul groupe. */
export interface GroupePurpose {
  titre: string;
  purposes: AccountPurpose[];
}

export const GROUPES_PURPOSE: GroupePurpose[] = [
  { titre: 'Achats', purposes: ['ACHAT_MARCHANDISE', 'ACHAT_MATIERE', 'ACHAT_CONSOMMABLE', 'ACHAT_SERVICE', 'ACHAT_FRAIS_ACCESSOIRE'] },
  { titre: 'Ventes', purposes: ['VENTE_MARCHANDISE', 'VENTE_PRODUIT', 'VENTE_SERVICE'] },
  { titre: 'TVA', purposes: ['TVA_COLLECTEE', 'TVA_RECUPERABLE'] },
  { titre: 'Trésorerie', purposes: ['BANQUE', 'CAISSE'] },
  { titre: 'Tiers', purposes: ['CLIENT', 'FOURNISSEUR'] },
  { titre: 'Stock', purposes: ['STOCK_MARCHANDISE', 'STOCK_MATIERE', 'VARIATION_STOCK'] },
  { titre: 'Immobilisations', purposes: ['IMMOBILISATION_CORPORELLE', 'IMMOBILISATION_INCORPORELLE', 'AMORTISSEMENT', 'DOTATION_AMORTISSEMENT'] },
  { titre: 'Résultat', purposes: ['RESULTAT_BENEFICE', 'RESULTAT_PERTE'] },
];

export const LIBELLES_PURPOSE: Record<AccountPurpose, string> = {
  ACHAT_MARCHANDISE: 'Marchandises',
  ACHAT_MATIERE: 'Matières premières',
  ACHAT_CONSOMMABLE: 'Consommables',
  ACHAT_SERVICE: 'Services',
  ACHAT_FRAIS_ACCESSOIRE: 'Frais accessoires',
  VENTE_MARCHANDISE: 'Marchandises',
  VENTE_PRODUIT: 'Produits',
  VENTE_SERVICE: 'Services',
  TVA_COLLECTEE: 'TVA collectée',
  TVA_RECUPERABLE: 'TVA récupérable',
  STOCK_MARCHANDISE: 'Stock marchandises',
  STOCK_MATIERE: 'Stock matières',
  VARIATION_STOCK: 'Variation de stock',
  CLIENT: 'Client (racine)',
  FOURNISSEUR: 'Fournisseur (racine)',
  BANQUE: 'Banque',
  CAISSE: 'Caisse',
  IMMOBILISATION_CORPORELLE: 'Immobilisation corporelle',
  IMMOBILISATION_INCORPORELLE: 'Immobilisation incorporelle',
  AMORTISSEMENT: 'Amortissement',
  DOTATION_AMORTISSEMENT: "Dotation aux amortissements",
  RESULTAT_BENEFICE: 'Résultat (bénéfice)',
  RESULTAT_PERTE: 'Résultat (perte)',
};

/** Meme correspondance que AccountPurpose.classesAttendues() cote backend - sert uniquement a
 * filtrer le selecteur de compte a l'ecran, la validation qui compte reste server-side. */
export const CLASSE_ATTENDUE_PURPOSE: Record<AccountPurpose, number> = {
  ACHAT_MARCHANDISE: 6,
  ACHAT_MATIERE: 6,
  ACHAT_CONSOMMABLE: 6,
  ACHAT_SERVICE: 6,
  ACHAT_FRAIS_ACCESSOIRE: 6,
  VENTE_MARCHANDISE: 7,
  VENTE_PRODUIT: 7,
  VENTE_SERVICE: 7,
  TVA_COLLECTEE: 4,
  TVA_RECUPERABLE: 4,
  STOCK_MARCHANDISE: 3,
  STOCK_MATIERE: 3,
  VARIATION_STOCK: 6,
  CLIENT: 4,
  FOURNISSEUR: 4,
  BANQUE: 5,
  CAISSE: 5,
  IMMOBILISATION_CORPORELLE: 2,
  IMMOBILISATION_INCORPORELLE: 2,
  AMORTISSEMENT: 2,
  DOTATION_AMORTISSEMENT: 6,
  RESULTAT_BENEFICE: 1,
  RESULTAT_PERTE: 1,
};

export type AccountResolutionStatus = 'RESOLVED' | 'AMBIGUOUS' | 'NOT_CONFIGURED' | 'INVALID';
export type AccountResolutionSource = 'EXPLICITE' | 'MAPPING_CONTEXTUEL' | 'MAPPING_GENERIQUE' | 'ROLE_SYSTEME' | 'AUCUN';
export type NiveauConfiance = 'CERTAIN' | 'NON_APPLICABLE';

export interface AccountingAccountMappingDto {
  id: string;
  purpose: AccountPurpose;
  contextKey: string | null;
  compteId: string;
  compteNumero: string;
  compteIntitule: string;
  actif: boolean;
  priorite: number;
  createdAt: string;
  updatedAt: string | null;
}

export interface CreerAccountingAccountMappingRequest {
  purpose: AccountPurpose;
  contextKey?: string;
  compteId: string;
  priorite?: number;
}

export interface ModifierAccountingAccountMappingRequest {
  compteId?: string;
  contextKey?: string;
  actif?: boolean;
}

export interface TestResolutionRequest {
  purpose: AccountPurpose;
  natureOperation?: string;
  contextKey?: string;
  metadata?: Record<string, string>;
}

export interface ResolvedAccountDto {
  status: AccountResolutionStatus;
  compteId: string | null;
  compteNumero: string | null;
  compteIntitule: string | null;
  source: AccountResolutionSource;
  confiance: NiveauConfiance;
  explication: string;
}

