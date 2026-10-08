import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Dashboard, DossierSummary, Echeance, Echeances, Membre, MembreCree, Page, PeriodeEcheance, ReinitialisationEmise, RequeteDossiers, RoleMembre, UtilisateurRef, WorkItem } from './cabinet-workspace.model';

const BASE = '/api/cabinet';

/** Accès HTTP au Cabinet Workspace : une méthode par endpoint, aucune règle métier (statuts et priorités viennent du backend). */
@Injectable({ providedIn: 'root' })
export class CabinetWorkspaceService {
  private readonly http = inject(HttpClient);

  dashboard(mes: boolean): Observable<Dashboard> {
    return this.http.get<Dashboard>(`${BASE}/dashboard`, { params: { mes } });
  }

  dossiers(r: Partial<RequeteDossiers>): Observable<Page<DossierSummary>> {
    let params = new HttpParams();
    for (const [cle, valeur] of Object.entries(r)) {
      if (valeur !== undefined && valeur !== null && valeur !== '') {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<Page<DossierSummary>>(`${BASE}/dossiers`, { params });
  }

  workQueue(mes: boolean, page: number, taille: number): Observable<Page<WorkItem>> {
    return this.http.get<Page<WorkItem>>(`${BASE}/work-queue`, { params: { mes, page, taille } });
  }

  echeances(periode: PeriodeEcheance | null, mes: boolean): Observable<Echeances> {
    let params = new HttpParams().set('mes', mes);
    if (periode) {
      params = params.set('periode', periode);
    }
    return this.http.get<Echeances>(`${BASE}/deadlines`, { params });
  }

  membres(): Observable<UtilisateurRef[]> {
    return this.http.get<UtilisateurRef[]>(`${BASE}/membres`);
  }

  equipe(): Observable<Membre[]> {
    return this.http.get<Membre[]>(`${BASE}/equipe`);
  }

  ajouterMembre(corps: { prenom: string; nom: string; email: string; role: RoleMembre }): Observable<MembreCree> {
    return this.http.post<MembreCree>(`${BASE}/membres`, corps);
  }

  /** Émet un jeton de réinitialisation à usage unique, renvoyé UNE seule fois (aucun e-mail n'est envoyé). */
  reinitialiserMotDePasse(id: string): Observable<ReinitialisationEmise> {
    return this.http.post<ReinitialisationEmise>(`${BASE}/equipe/${id}/reinitialiser-mot-de-passe`, {});
  }

  modifierMembre(id: string, corps: { role?: RoleMembre; statut?: 'ACTIF' | 'INACTIF' }): Observable<Membre> {
    return this.http.patch<Membre>(`${BASE}/membres/${id}`, corps);
  }

  affecter(entrepriseId: string, exerciceId: string, collaborateurId: string | null, expertId: string | null): Observable<DossierSummary> {
    return this.http.patch<DossierSummary>(`${BASE}/dossiers/${entrepriseId}/${exerciceId}/affectation`, { collaborateurId, expertId });
  }

  affecterEnMasse(dossiers: { entrepriseId: string; exerciceId: string }[], collaborateurId: string | null, expertId: string | null): Observable<{ dossiers: number }> {
    return this.http.patch<{ dossiers: number }>(`${BASE}/dossiers/affectation`, { dossiers, collaborateurId, expertId });
  }

  creerEcheance(corps: { entrepriseId: string; exerciceId?: string | null; type: string; libelle: string; date: string; responsableId?: string | null }): Observable<Echeance> {
    return this.http.post<Echeance>(`${BASE}/echeances`, corps);
  }

  modifierEcheance(id: string, corps: { date?: string; statut?: string; responsableId?: string; libelle?: string }): Observable<Echeance> {
    return this.http.patch<Echeance>(`${BASE}/echeances/${id}`, corps);
  }
}
