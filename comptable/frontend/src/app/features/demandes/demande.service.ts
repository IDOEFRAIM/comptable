import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TypeDocument } from '../documents/models/document.model';
import { CreerDemandePieceRequest, DemandePieceDto, StatutDemandePiece } from './models/demande.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class DemandeService {
  private readonly http = inject(HttpClient);

  lister(entrepriseId: string, statut?: StatutDemandePiece): Observable<DemandePieceDto[]> {
    const params = statut ? new HttpParams().set('statut', statut) : undefined;
    return this.http.get<DemandePieceDto[]>(`${BASE_URL}/${entrepriseId}/demandes-pieces`, { params });
  }

  creer(entrepriseId: string, request: CreerDemandePieceRequest): Observable<DemandePieceDto> {
    return this.http.post<DemandePieceDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces`, request);
  }

  annuler(entrepriseId: string, demandeId: string): Observable<DemandePieceDto> {
    return this.http.post<DemandePieceDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/${demandeId}/annuler`, {});
  }

  repondre(entrepriseId: string, demandeId: string, typeDocument: TypeDocument, fichier: File): Observable<DemandePieceDto> {
    const formData = new FormData();
    formData.append('typeDocument', typeDocument);
    formData.append('fichier', fichier);
    return this.http.post<DemandePieceDto>(
      `${BASE_URL}/${entrepriseId}/demandes-pieces/${demandeId}/repondre`,
      formData,
    );
  }
}
