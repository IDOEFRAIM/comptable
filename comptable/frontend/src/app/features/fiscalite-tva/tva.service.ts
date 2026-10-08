import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Ajustement, Controles, Credit, Declaration, FiltresOperations, LigneTva, Origine, Page, Periode, PeriodeResume, Reglement } from './tva.model';

/** Accès HTTP à l'espace TVA : une méthode par endpoint, aucune règle fiscale (le backend calcule et décide). Chaque modification renvoie la version lue (expectedVersion) : un conflit donne 409. */
@Injectable({ providedIn: 'root' })
export class TvaService {
  private readonly http = inject(HttpClient);

  private base(entrepriseId: string): string {
    return `/api/entreprises/${entrepriseId}/fiscalite/tva`;
  }

  periodes(entrepriseId: string): Observable<PeriodeResume[]> {
    return this.http.get<PeriodeResume[]>(`${this.base(entrepriseId)}/periodes`);
  }

  ouvrir(entrepriseId: string, periodStart: string, periodEnd: string): Observable<Periode> {
    return this.http.post<Periode>(`${this.base(entrepriseId)}/periodes`, { periodStart, periodEnd });
  }

  periode(entrepriseId: string, periodeId: string): Observable<Periode> {
    return this.http.get<Periode>(`${this.base(entrepriseId)}/periodes/${periodeId}`);
  }

  /** Liste paginée, filtrée, triée et recherchée CÔTÉ SERVEUR. */
  operations(entrepriseId: string, periodeId: string, f: Partial<FiltresOperations>, page: number, taille: number): Observable<Page<LigneTva>> {
    let params = new HttpParams().set('page', page).set('taille', taille);
    if (f.cote) {
      params = params.set('cote', f.cote);
    }
    if (f.statut && f.statut !== 'ALL') {
      params = params.set('statut', f.statut);
    }
    if (f.traitement) {
      params = params.set('traitement', f.traitement);
    }
    if (f.constatActif) {
      params = params.set('constatActif', true);
    }
    if (f.q && f.q.trim()) {
      params = params.set('q', f.q.trim());
    }
    if (f.tri) {
      params = params.set('tri', f.tri).set('sens', f.sens ?? 'asc');
    }
    return this.http.get<Page<LigneTva>>(`${this.base(entrepriseId)}/periodes/${periodeId}/operations`, { params });
  }

  origine(entrepriseId: string, periodeId: string, ecritureId: string): Observable<Origine> {
    return this.http.get<Origine>(`${this.base(entrepriseId)}/periodes/${periodeId}/operations/${ecritureId}/origine`);
  }

  decider(entrepriseId: string, periodeId: string, ecritureId: string, corps: { treatment: string; deductiblePercent: number | null; reason: string; expectedVersion: number }): Observable<Periode> {
    return this.http.put<Periode>(`${this.base(entrepriseId)}/periodes/${periodeId}/operations/${ecritureId}/traitement`, corps);
  }

  /** Validation en masse d'un traitement certain : le serveur revalide chaque opération (tout ou rien). */
  traitementGroupe(entrepriseId: string, periodeId: string, corps: { ecritureIds: string[]; treatment: string; reason: string; expectedVersion: number }): Observable<{ applied: number; period: Periode }> {
    return this.http.post<{ applied: number; period: Periode }>(`${this.base(entrepriseId)}/periodes/${periodeId}/operations/traitement-groupe`, corps);
  }

  ajustements(entrepriseId: string, periodeId: string): Observable<Ajustement[]> {
    return this.http.get<Ajustement[]>(`${this.base(entrepriseId)}/periodes/${periodeId}/ajustements`);
  }

  ajouterAjustement(entrepriseId: string, periodeId: string, corps: { kind: string; effect: string; amount: number; reason: string; evidence: string | null; ecritureId: string | null; expectedVersion: number }): Observable<Periode> {
    return this.http.post<Periode>(`${this.base(entrepriseId)}/periodes/${periodeId}/ajustements`, corps);
  }

  annulerAjustement(entrepriseId: string, periodeId: string, ajustementId: string, reason: string, expectedVersion: number): Observable<Periode> {
    return this.http.post<Periode>(`${this.base(entrepriseId)}/periodes/${periodeId}/ajustements/${ajustementId}/annuler`, { reason, expectedVersion });
  }

  controles(entrepriseId: string, periodeId: string): Observable<Controles> {
    return this.http.get<Controles>(`${this.base(entrepriseId)}/periodes/${periodeId}/controles`);
  }

  declaration(entrepriseId: string, periodeId: string): Observable<Declaration> {
    return this.http.get<Declaration>(`${this.base(entrepriseId)}/periodes/${periodeId}/declaration`);
  }

  action(entrepriseId: string, periodeId: string, action: 'recalculer' | 'preparer' | 'revoir' | 'rouvrir' | 'finaliser', expectedVersion: number | null): Observable<Periode> {
    return this.http.post<Periode>(`${this.base(entrepriseId)}/periodes/${periodeId}/${action}`, { expectedVersion });
  }

  // ---- règlement interne et crédit d'ouverture
  paiements(entrepriseId: string, periodeId: string): Observable<Reglement> {
    return this.http.get<Reglement>(`${this.base(entrepriseId)}/periodes/${periodeId}/paiements`);
  }

  enregistrerPaiement(entrepriseId: string, periodeId: string, corps: { amount: number; paymentDate: string; reference: string | null; source: string; idempotencyKey: string; expectedVersion: number }): Observable<Reglement> {
    return this.http.post<Reglement>(`${this.base(entrepriseId)}/periodes/${periodeId}/paiements`, corps);
  }

  annulerPaiement(entrepriseId: string, periodeId: string, paiementId: string, reason: string, expectedVersion: number): Observable<Reglement> {
    return this.http.post<Reglement>(`${this.base(entrepriseId)}/periodes/${periodeId}/paiements/${paiementId}/annuler`, { reason, expectedVersion });
  }

  saisirCredit(entrepriseId: string, periodeId: string, corps: { amount: number; reason: string; source: string; previousPeriodId: string | null; expectedVersion: number }): Observable<Credit> {
    return this.http.put<Credit>(`${this.base(entrepriseId)}/periodes/${periodeId}/credit-ouverture`, corps);
  }
}
