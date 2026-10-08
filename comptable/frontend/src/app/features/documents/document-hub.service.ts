import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CandidatDemandeDto,
  CandidatFactureDto,
  DocumentSimpleDto,
  FiltreHub,
  PageHubDto,
  ResultatActionDto,
  ResultatLotDto,
  SyntheseHubDto,
} from './models/document-hub.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class DocumentHubService {
  private readonly http = inject(HttpClient);

  synthese(entrepriseId: string, periode?: string): Observable<SyntheseHubDto> {
    let params = new HttpParams();
    if (periode) {
      params = params.set('periode', periode);
    }
    return this.http.get<SyntheseHubDto>(`${BASE_URL}/${entrepriseId}/documents/hub/synthese`, { params });
  }

  lister(entrepriseId: string, filtre: FiltreHub): Observable<PageHubDto> {
    let params = new HttpParams();
    for (const [cle, valeur] of Object.entries(filtre)) {
      if (valeur !== undefined && valeur !== null && valeur !== '') {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<PageHubDto>(`${BASE_URL}/${entrepriseId}/documents/hub`, { params });
  }

  candidats(entrepriseId: string, documentId: string): Observable<CandidatFactureDto[]> {
    return this.http.get<CandidatFactureDto[]>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/candidats`);
  }

  /** Demandes de pièces ouvertes, EXACT / PROBABLE / AMBIGUOUS d'abord : le comptable choisit, il ne saisit jamais un identifiant. */
  demandesCandidates(entrepriseId: string, documentId: string): Observable<CandidatDemandeDto[]> {
    return this.http.get<CandidatDemandeDto[]>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/demandes-candidates`);
  }

  associerDemande(entrepriseId: string, documentId: string, demandeId: string): Observable<ResultatActionDto> {
    return this.http.post<ResultatActionDto>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/associer-demande`, { demandeId });
  }

  originauxPossibles(entrepriseId: string, documentId: string): Observable<DocumentSimpleDto[]> {
    return this.http.get<DocumentSimpleDto[]>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/originaux-possibles`);
  }

  associer(entrepriseId: string, documentId: string, cibleType: string, cibleId: string): Observable<ResultatActionDto> {
    return this.http.post<ResultatActionDto>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/associer`, { cibleType, cibleId });
  }

  rejeterProposition(entrepriseId: string, documentId: string): Observable<ResultatActionDto> {
    return this.http.post<ResultatActionDto>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/rejeter-proposition`, {});
  }

  marquerDoublon(entrepriseId: string, documentId: string, doublonDeId: string): Observable<ResultatActionDto> {
    return this.http.post<ResultatActionDto>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/marquer-doublon`, { doublonDeId });
  }

  valider(entrepriseId: string, documentId: string): Observable<ResultatActionDto> {
    return this.http.post<ResultatActionDto>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/valider`, {});
  }

  retraiter(entrepriseId: string, documentId: string): Observable<ResultatActionDto> {
    return this.http.post<ResultatActionDto>(`${BASE_URL}/${entrepriseId}/documents/${documentId}/retraiter`, {});
  }

  apercuValidationLot(entrepriseId: string): Observable<DocumentSimpleDto[]> {
    return this.http.get<DocumentSimpleDto[]>(`${BASE_URL}/${entrepriseId}/documents/hub/validation-lot/apercu`);
  }

  validerLot(entrepriseId: string, documentIds: string[]): Observable<ResultatLotDto> {
    return this.http.post<ResultatLotDto>(`${BASE_URL}/${entrepriseId}/documents/hub/validation-lot`, { documentIds });
  }

  /** Le fichier se récupère avec le jeton (un simple lien ne l'enverrait pas) puis s'ouvre dans un nouvel onglet. */
  fichier(entrepriseId: string, documentId: string): Observable<Blob> {
    return this.http.get(`${BASE_URL}/${entrepriseId}/documents/${documentId}/fichier`, { responseType: 'blob' });
  }
}
