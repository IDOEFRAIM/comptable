import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface PageAdmin<T> {
  elements: T[];
  total: number;
  page: number;
  taille: number;
}

export interface UtilisateurAdmin {
  id: string;
  email: string;
  nom: string;
  prenom: string;
  statut: string;
  cabinetId: string | null;
  cabinet: string | null;
  roles: string[];
  creeLe: string;
  derniereConnexion: string | null;
  verrouilleJusqua: string | null;
  verrouille: boolean;
  echecsConsecutifs: number;
  motDePasseAChanger: boolean;
}

export interface ReinitialisationAdmin {
  utilisateur: UtilisateurAdmin;
  motDePasseTemporaire: string;
}

export interface EvenementAudit {
  id: number;
  date: string;
  action: string;
  entite: string;
  entiteId: string | null;
  details: Record<string, unknown>;
  cabinetId: string | null;
  cabinet: string | null;
  acteurId: string | null;
  acteur: string | null;
}

export interface CabinetAdmin {
  id: string;
  raisonSociale: string;
  slug: string;
  pays: string;
  plan: string;
  statut: string;
  finEssai: string | null;
  essaiExpire: boolean;
  quotaUtilisateurs: number | null;
  quotaDossiers: number | null;
  membresActifs: number;
  utilisateursTotal: number;
  dossiersActifs: number;
  creeLe: string;
  derniereActivite: string | null;
  motifSuspension: string | null;
  suspenduLe: string | null;
}

export interface AbonnementCabinet {
  plan: string;
  finEssai: string | null;
  quotaUtilisateurs: number | null;
  quotaDossiers: number | null;
  motif: string;
}

export interface NouveauCabinet {
  raisonSociale: string;
  pays: string;
  emailExpert: string;
  nom: string;
  prenom: string;
  telephone?: string;
  plan?: string;
  finEssai?: string | null;
  quotaUtilisateurs?: number | null;
  quotaDossiers?: number | null;
}

export interface CreationCabinet {
  cabinet: CabinetAdmin;
  emailExpert: string;
  motDePasseTemporaire: string;
}

export interface UsageIaCabinet {
  cabinetId: string;
  cabinet: string;
  appels: number;
  echecs: number;
  refusesPlafond: number;
  latenceMoyenneMs: number | null;
  plafond: number | null;
  dernierAppel: string | null;
}

export interface UsageIaOperation {
  operation: string;
  appels: number;
  echecs: number;
  latenceMoyenneMs: number | null;
}

export interface UsageIa {
  mois: string;
  appels: number;
  echecs: number;
  refusesPlafond: number;
  appelsSansCabinet: number;
  cabinets: UsageIaCabinet[];
  operations: UsageIaOperation[];
}

export interface EtatService {
  nom: string;
  etat: 'OK' | 'DEGRADE' | 'HORS_SERVICE';
  latenceMs: number | null;
  detail: string;
}

export interface SanteSauvegarde {
  etat: 'OK' | 'ANCIENNE' | 'ABSENTE' | 'NON_SURVEILLEE';
  derniere: string | null;
  ageHeures: number | null;
  detail: string;
}

export interface SantePlateforme {
  mesureLe: string;
  services: EtatService[];
  sauvegarde: SanteSauvegarde;
  memoireUtiliseeMo: number;
  memoireMaxMo: number;
  disqueLibreMo: number;
  disqueTotalMo: number;
  versionBase: string;
  demarreDepuisMinutes: number;
}

export const PLANS = ['ESSAI', 'STANDARD', 'PARTENAIRE'];

export interface ApercuAdmin {
  cabinets: number;
  utilisateurs: number;
  actifs: number;
  suspendus: number;
  verrouilles: number;
  jamaisConnectes: number;
  administrateurs: number;
  cabinetsSuspendus: number;
  essaisExpires: number;
}

export type ActionCompte = 'suspendre' | 'reactiver' | 'deverrouiller' | 'revoquer-sessions' | 'reinitialiser-mot-de-passe';

export const MOTIF_MIN = 5;

/** Accès à l'API d'administration de la plateforme (`/api/admin/**`, réservée au rôle SUPER_ADMIN). */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  apercu(): Observable<ApercuAdmin> {
    return this.http.get<ApercuAdmin>('/api/admin/apercu');
  }

  utilisateurs(filtre: { q?: string; statut?: string; page?: number; taille?: number }): Observable<PageAdmin<UtilisateurAdmin>> {
    return this.http.get<PageAdmin<UtilisateurAdmin>>('/api/admin/utilisateurs', { params: parametres(filtre) });
  }

  agir(id: string, action: Exclude<ActionCompte, 'reinitialiser-mot-de-passe'>, motif: string): Observable<UtilisateurAdmin> {
    return this.http.post<UtilisateurAdmin>(`/api/admin/utilisateurs/${id}/${action}`, { motif });
  }

  reinitialiser(id: string, motif: string): Observable<ReinitialisationAdmin> {
    return this.http.post<ReinitialisationAdmin>(`/api/admin/utilisateurs/${id}/reinitialiser-mot-de-passe`, { motif });
  }

  cabinets(filtre: { q?: string; statut?: string; page?: number; taille?: number }): Observable<PageAdmin<CabinetAdmin>> {
    return this.http.get<PageAdmin<CabinetAdmin>>('/api/admin/cabinets', { params: parametres(filtre) });
  }

  agirCabinet(id: string, action: 'suspendre' | 'reactiver', motif: string): Observable<CabinetAdmin> {
    return this.http.post<CabinetAdmin>(`/api/admin/cabinets/${id}/${action}`, { motif });
  }

  modifierAbonnement(id: string, abonnement: AbonnementCabinet): Observable<CabinetAdmin> {
    return this.http.post<CabinetAdmin>(`/api/admin/cabinets/${id}/abonnement`, abonnement);
  }

  creerCabinet(cabinet: NouveauCabinet): Observable<CreationCabinet> {
    return this.http.post<CreationCabinet>('/api/admin/cabinets', cabinet);
  }

  usageIa(mois?: string): Observable<UsageIa> {
    return this.http.get<UsageIa>('/api/admin/ia/usage', { params: parametres({ mois }) });
  }

  modifierPlafondIa(cabinetId: string, plafond: number | null, motif: string): Observable<UsageIaCabinet> {
    return this.http.post<UsageIaCabinet>(`/api/admin/cabinets/${cabinetId}/plafond-ia`, { plafond, motif });
  }

  sante(): Observable<SantePlateforme> {
    return this.http.get<SantePlateforme>('/api/admin/sante');
  }

  audit(filtre: { cabinetId?: string; action?: string; depuis?: string; jusqu?: string; page?: number; taille?: number }): Observable<PageAdmin<EvenementAudit>> {
    return this.http.get<PageAdmin<EvenementAudit>>('/api/admin/audit', { params: parametres(filtre) });
  }
}

/** Ne transmet que les filtres renseignés. */
function parametres(filtre: Record<string, string | number | undefined>): HttpParams {
  let params = new HttpParams();
  for (const [cle, valeur] of Object.entries(filtre)) {
    if (valeur !== undefined && valeur !== '') {
      params = params.set(cle, String(valeur));
    }
  }
  return params;
}
