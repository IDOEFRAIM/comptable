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

export interface ApercuAdmin {
  cabinets: number;
  utilisateurs: number;
  actifs: number;
  suspendus: number;
  verrouilles: number;
  jamaisConnectes: number;
  administrateurs: number;
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
