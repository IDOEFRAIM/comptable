import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ConstatDto,
  EspaceRevueDto,
  InvestigationDto,
  PreparationClotureDto,
  ExecutionControlesDto,
  FiltreRevue,
  PageConstatsDto,
  SyntheseRevueDto,
} from './models/revision.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class RevisionService {
  private readonly http = inject(HttpClient);

  synthese(entrepriseId: string): Observable<SyntheseRevueDto> {
    return this.http.get<SyntheseRevueDto>(`${BASE_URL}/${entrepriseId}/revue/synthese`);
  }

  constats(entrepriseId: string, filtre: FiltreRevue): Observable<PageConstatsDto> {
    let params = new HttpParams();
    for (const [cle, valeur] of Object.entries(filtre)) {
      if (valeur !== undefined && valeur !== null && valeur !== '') {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<PageConstatsDto>(`${BASE_URL}/${entrepriseId}/revue/constats`, { params });
  }

  relancer(entrepriseId: string): Observable<ExecutionControlesDto> {
    return this.http.post<ExecutionControlesDto>(`${BASE_URL}/${entrepriseId}/revue/executer`, {});
  }

  ecarter(entrepriseId: string, constatId: string, motif: string): Observable<unknown> {
    return this.http.post(`${BASE_URL}/${entrepriseId}/revue/constats/${constatId}/ecarter`, { motif });
  }

  constat(entrepriseId: string, constatId: string): Observable<ConstatDto> {
    return this.http.get<ConstatDto>(`${BASE_URL}/${entrepriseId}/revue/constats/${constatId}`);
  }

  espace(entrepriseId: string): Observable<EspaceRevueDto> {
    return this.http.get<EspaceRevueDto>(`${BASE_URL}/${entrepriseId}/revue/espace`);
  }

  /** « Pourquoi ? » d'un constat : reconstitution de la source à l'anomalie (lecture seule). */
  investigation(entrepriseId: string, constatId: string): Observable<InvestigationDto> {
    return this.http.get<InvestigationDto>(`${BASE_URL}/${entrepriseId}/revue/constats/${constatId}/investigation`);
  }

  investigationObjet(entrepriseId: string, type: string, id: string): Observable<InvestigationDto> {
    return this.http.get<InvestigationDto>(`${BASE_URL}/${entrepriseId}/revue/investigations/${type}/${id}`);
  }

  preparationCloture(entrepriseId: string): Observable<PreparationClotureDto> {
    return this.http.get<PreparationClotureDto>(`${BASE_URL}/${entrepriseId}/cloture-readiness`);
  }
}
