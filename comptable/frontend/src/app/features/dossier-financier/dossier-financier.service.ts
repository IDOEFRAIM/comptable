import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BilanDto,
  DetailLigneDto,
  EspaceNotesDto,
  EtatDto,
  NoteDto,
  OptionEvenement,
  PatchChamp,
  SnapshotDto,
  SyntheseDossierDto,
  TypeEtat,
} from './dossier-financier.model';

const BASE = '/api/entreprises';

/** Accès HTTP au dossier financier : une méthode par endpoint backend, aucune règle métier. */
@Injectable({ providedIn: 'root' })
export class DossierFinancierService {
  private readonly http = inject(HttpClient);

  private url(entrepriseId: string, chemin: string): string {
    return `${BASE}/${entrepriseId}/etats-financiers-syscohada${chemin}`;
  }

  private exo(exerciceId: string): HttpParams {
    return new HttpParams().set('exerciceId', exerciceId);
  }

  synthese(entrepriseId: string, exerciceId: string): Observable<SyntheseDossierDto> {
    return this.http.get<SyntheseDossierDto>(this.url(entrepriseId, '/dossier-financier'), { params: this.exo(exerciceId) });
  }

  compteResultat(entrepriseId: string, exerciceId: string): Observable<EtatDto> {
    return this.http.get<EtatDto>(this.url(entrepriseId, '/compte-resultat'), { params: this.exo(exerciceId) });
  }

  bilan(entrepriseId: string, exerciceId: string): Observable<BilanDto> {
    return this.http.get<BilanDto>(this.url(entrepriseId, '/bilan'), { params: this.exo(exerciceId) });
  }

  tft(entrepriseId: string, exerciceId: string): Observable<EtatDto> {
    return this.http.get<EtatDto>(this.url(entrepriseId, '/tft'), { params: this.exo(exerciceId) });
  }

  detailLigne(entrepriseId: string, exerciceId: string, type: TypeEtat, ligne: string, compteId?: string): Observable<DetailLigneDto> {
    let params = this.exo(exerciceId);
    if (compteId) {
      params = params.set('compteId', compteId);
    }
    return this.http.get<DetailLigneDto>(this.url(entrepriseId, `/${type}/lignes/${encodeURIComponent(ligne)}`), { params });
  }

  // ---- Notes
  notes(entrepriseId: string, exerciceId: string): Observable<EspaceNotesDto> {
    return this.http.get<EspaceNotesDto>(this.url(entrepriseId, '/notes'), { params: this.exo(exerciceId) });
  }

  modifierDonneesManuelles(entrepriseId: string, exerciceId: string, code: string, expectedVersion: number, fields: PatchChamp[], manualComment?: string): Observable<NoteDto> {
    return this.http.patch<NoteDto>(this.url(entrepriseId, `/notes/${encodeURIComponent(code)}/manual-data`), { expectedVersion, fields, manualComment: manualComment ?? null }, {
      params: this.exo(exerciceId),
    });
  }

  modifierApplicabilite(entrepriseId: string, exerciceId: string, code: string, expectedVersion: number, status: string, reason?: string): Observable<NoteDto> {
    return this.http.patch<NoteDto>(this.url(entrepriseId, `/notes/${encodeURIComponent(code)}/applicability`), { expectedVersion, status, reason: reason ?? null }, {
      params: this.exo(exerciceId),
    });
  }

  validerCandidates(entrepriseId: string, exerciceId: string): Observable<EspaceNotesDto> {
    return this.http.post<EspaceNotesDto>(this.url(entrepriseId, '/notes/validate-candidates'), {}, { params: this.exo(exerciceId) });
  }

  relire(entrepriseId: string, exerciceId: string, code: string, expectedVersion: number): Observable<NoteDto> {
    return this.http.post<NoteDto>(this.url(entrepriseId, `/notes/${encodeURIComponent(code)}/review`), {}, {
      params: this.exo(exerciceId).set('expectedVersion', expectedVersion),
    });
  }

  rouvrir(entrepriseId: string, exerciceId: string, code: string, expectedVersion: number): Observable<NoteDto> {
    return this.http.post<NoteDto>(this.url(entrepriseId, `/notes/${encodeURIComponent(code)}/reopen`), {}, {
      params: this.exo(exerciceId).set('expectedVersion', expectedVersion),
    });
  }

  // ---- Flux de trésorerie
  evenementsTresorerie(entrepriseId: string): Observable<OptionEvenement[]> {
    return this.http.get<OptionEvenement[]>(this.url(entrepriseId, '/cash-flow-events'));
  }

  qualifier(entrepriseId: string, ecritureId: string, event: string | null): Observable<{ ecritureId: string; numeroPiece: string; cashFlowEvent: string | null }> {
    return this.http.put<{ ecritureId: string; numeroPiece: string; cashFlowEvent: string | null }>(this.url(entrepriseId, `/ecritures/${ecritureId}/cash-flow-event`), { event });
  }

  // ---- Finalisation, snapshot, export
  finaliser(entrepriseId: string, exerciceId: string): Observable<SnapshotDto> {
    return this.http.post<SnapshotDto>(this.url(entrepriseId, '/DOSSIER_COMPLET/finaliser'), {}, { params: this.exo(exerciceId) });
  }

  snapshot(entrepriseId: string, snapshotId: string): Observable<SnapshotDto> {
    return this.http.get<SnapshotDto>(this.url(entrepriseId, `/snapshots/${snapshotId}`));
  }

  /** Export imprimable (HTML) récupéré avec le jeton d'authentification puis ouvert dans un nouvel onglet : un lien direct n'enverrait pas l'en-tête Authorization. */
  exporterBrouillon(entrepriseId: string, exerciceId: string): Observable<string> {
    return this.http.get(this.url(entrepriseId, '/dossier-financier/export'), { params: this.exo(exerciceId), responseType: 'text' });
  }

  exporterFinal(entrepriseId: string, snapshotId: string): Observable<string> {
    return this.http.get(this.url(entrepriseId, `/snapshots/${snapshotId}/export`), { responseType: 'text' });
  }
}
