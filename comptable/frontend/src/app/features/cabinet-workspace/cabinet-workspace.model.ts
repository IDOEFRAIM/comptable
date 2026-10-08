/** Contrat du Cabinet Workspace : le backend fournit statuts, libellés, priorités, compteurs ; le frontend les affiche sans les recalculer. */

export type StatutDossier =
  | 'A_DEMARRER'
  | 'EN_COURS'
  | 'ATTENTION_REQUISE'
  | 'EN_ATTENTE_CLIENT'
  | 'PRET_POUR_REVUE'
  | 'EN_REVUE'
  | 'PRET_A_CLOTURER'
  | 'CLOTURE'
  | 'FINALISE';

export type PrioriteDossier = 'BLOCKER' | 'HIGH' | 'NORMAL' | 'LOW';

export type FiltrePortefeuille = 'MES_DOSSIERS' | 'TOUS' | 'A_TRAITER' | 'EN_ATTENTE_CLIENT' | 'PRETS_REVUE' | 'PRETS_CLOTURE' | 'FINALISES';

export type TriPortefeuille = 'PRIORITE' | 'ENTREPRISE' | 'ACTIVITE' | 'ECHEANCE';

export type PeriodeEcheance = 'EN_RETARD' | 'AUJOURDHUI' | 'CETTE_SEMAINE' | 'A_VENIR';

export interface UtilisateurRef {
  id: string;
  nom: string;
}

export interface EtapeDossier {
  code: string;
  libelle: string;
  prete: boolean;
}

export interface EcheanceRef {
  id: string;
  date: string;
  type: string;
  libelle: string;
}

export interface DossierSummary {
  entrepriseId: string;
  entrepriseNom: string;
  identifiant: string | null;
  exerciceId: string;
  exerciceDebut: string;
  exerciceFin: string;
  courant: boolean;
  collaborateur: UtilisateurRef | null;
  expert: UtilisateurRef | null;
  statut: StatutDossier;
  statutLibelle: string;
  priorite: PrioriteDossier;
  etapesPretes: number;
  etapesTotal: number;
  etapes: EtapeDossier[];
  findingsActifs: number;
  findingsBloquants: number;
  piecesManquantes: number;
  banqueExceptions: number;
  prochaineEcheance: EcheanceRef | null;
  derniereActivite: string | null;
  tva: 'A_PREPARER' | 'A_TRAITER' | 'A_REVOIR' | 'PRETE' | 'CADRE_INCOMPLET' | 'FINALISEE' | 'A_PAYER' | 'PARTIELLEMENT_PAYEE' | 'PAYEE' | 'CREDIT_A_REPORTER' | null;
  tvaLibelle: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  taille: number;
  total: number;
}

export interface WorkItem {
  kind: 'FINDING' | 'DEMANDE_PIECE' | 'TAX_REVIEW' | 'TAX_PAYMENT';
  id: string;
  entrepriseId: string;
  entrepriseNom: string;
  exerciceId: string;
  titre: string;
  domaine: string;
  impact: string;
  priorite: PrioriteDossier;
  ancienneteJours: number;
  depuis: string | null;
  responsable: UtilisateurRef | null;
  cible: 'REVISION' | 'DEMANDES' | 'BANQUE' | 'ETATS_FINANCIERS' | 'FISCALITE';
}

export interface Echeance {
  id: string;
  entrepriseId: string;
  entrepriseNom: string | null;
  exerciceId: string | null;
  type: string;
  typeLibelle: string;
  libelle: string;
  date: string;
  responsable: UtilisateurRef | null;
  statut: 'OUVERTE' | 'FAITE';
  periode: PeriodeEcheance | 'FAITE';
}

export interface Echeances {
  enRetard: number;
  aujourdhui: number;
  cetteSemaine: number;
  aVenir: number;
  items: Echeance[];
}

export interface Summary {
  dossiersActifs: number;
  aTraiter: number;
  enAttenteClient: number;
  pretsPourRevue: number;
  echeancesProches: number;
}

export interface Dashboard {
  perimetre: 'MES_DOSSIERS' | 'CABINET';
  summary: Summary;
  workQueue: Page<WorkItem>;
  reviewQueue: DossierSummary[];
  deadlines: Echeance[];
}

export interface RequeteDossiers {
  filtre: FiltrePortefeuille;
  q: string;
  collaborateurId: string;
  expertId: string;
  exercice: string;
  statut: string;
  tri: TriPortefeuille;
  ordre: 'asc' | 'desc';
  page: number;
  taille: number;
}

export type RoleMembre = 'COLLABORATEUR' | 'EXPERT_COMPTABLE';

export interface Membre {
  id: string;
  nom: string;
  prenom: string;
  email: string;
  role: RoleMembre;
  statut: 'ACTIF' | 'INACTIF';
  dossiersAffectes: number;
  derniereActivite: string | null;
}

export interface ReinitialisationEmise {
  jeton: string;
  expireAt: string;
  chemin: string;
}

export interface MembreCree {
  membre: Membre;
  motDePasseTemporaire: string | null;
}
