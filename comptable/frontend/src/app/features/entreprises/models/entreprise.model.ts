export interface EntrepriseDto {
  id: string;
  raisonSociale: string;
  pays: string;
  devise: string;
  statut: string;
}

export interface CreerEntrepriseRequest {
  raisonSociale: string;
  pays: string;
  devise?: string;
  regimeFiscal?: string;
  secteurActivite?: string;
}

/** Le code 'EXONERE_TVA' est reconnu tel quel par AnalyseFraudeService.estExonereDeTva
 * (recherche du fragment "EXONER", insensible a la casse) : le controle de fraude ne signale
 * jamais d'ecart de TVA pour une entreprise ayant choisi ce regime a l'onboarding. */
export const REGIMES_FISCAUX_UEMOA: Array<{ code: string; nom: string }> = [
  { code: 'REEL_NORMAL', nom: 'Régime réel normal (TVA 18 %)' },
  { code: 'REEL_SIMPLIFIE', nom: 'Régime réel simplifié (TVA 18 %)' },
  { code: 'ENTREPRENANT', nom: "Régime de l'entreprenant / micro-entreprise" },
  { code: 'EXONERE_TVA', nom: 'Exonéré de TVA' },
];

export type RoleInvitable = 'CLIENT' | 'COLLABORATEUR';

export interface InviterUtilisateurRequest {
  email: string;
  nom: string;
  prenom: string;
  telephone?: string;
  role: RoleInvitable;
}

export interface UtilisateurInviteDto {
  id: string;
  email: string;
  nom: string;
  prenom: string;
  role: RoleInvitable;
  motDePasseTemporaire: string;
}
