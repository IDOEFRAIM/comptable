export interface AuthResponse {
  token: string;
  utilisateurId: string;
  tenantId: string;
  email: string;
  roles: string[];
  /** Mot de passe temporaire ou réinitialisé : seule la page de changement de mot de passe est accessible. */
  mustChangePassword?: boolean;
}

export interface RegisterCabinetRequest {
  raisonSocialeCabinet: string;
  pays: string;
  email: string;
  motDePasse: string;
  nom: string;
  prenom: string;
  telephone?: string;
}

export const PAYS_OHADA_OUEST_AFRICAIN: Array<{ code: string; nom: string }> = [
  { code: 'CI', nom: "Côte d'Ivoire" },
  { code: 'SN', nom: 'Sénégal' },
  { code: 'ML', nom: 'Mali' },
  { code: 'BF', nom: 'Burkina Faso' },
  { code: 'TG', nom: 'Togo' },
  { code: 'BJ', nom: 'Bénin' },
  { code: 'NE', nom: 'Niger' },
  { code: 'GW', nom: 'Guinée-Bissau' },
];
