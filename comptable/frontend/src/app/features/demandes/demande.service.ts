import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TypeDocument } from '../documents/models/document.model';
import {
  ApercuCollecteDto,
  CreerDemandePieceRequest,
  DemandePieceDto,
  DemanderPieceRequest,
  ElementCollecteDto,
  MessageCollecteDto,
  MetriquesCollecteDto,
  ResultatCollecteDto,
  StatutDemandePiece,
  SyntheseCollecteDto,
} from './models/demande.model';

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

  /** Demande le justificatif d'une opération signalée par un constat « pièce manquante ». */
  demanderPourConstat(entrepriseId: string, findingId: string, request: DemanderPieceRequest): Observable<DemandePieceDto> {
    return this.http.post<DemandePieceDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/depuis-constat/${findingId}`, request);
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

  // ---------------------------------------------------------------------------------------- collecte active (cabinet)

  syntheseCollecte(entrepriseId: string): Observable<SyntheseCollecteDto> {
    return this.http.get<SyntheseCollecteDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/collecte/synthese`);
  }

  metriquesCollecte(entrepriseId: string): Observable<MetriquesCollecteDto> {
    return this.http.get<MetriquesCollecteDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/collecte/metriques`);
  }

  aDemander(entrepriseId: string): Observable<ElementCollecteDto[]> {
    return this.http.get<ElementCollecteDto[]>(`${BASE_URL}/${entrepriseId}/demandes-pieces/collecte/a-demander`);
  }

  listerCollecte(entrepriseId: string): Observable<DemandePieceDto[]> {
    return this.http.get<DemandePieceDto[]>(`${BASE_URL}/${entrepriseId}/demandes-pieces/collecte`);
  }

  /** Aperçu d'une demande groupée : le serveur recalcule tout, rien n'est créé. */
  apercuGroupe(entrepriseId: string, findingIds: string[]): Observable<ApercuCollecteDto> {
    return this.http.post<ApercuCollecteDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/bulk`, {
      findingIds,
      apercuSeulement: true,
    });
  }

  demanderGroupe(entrepriseId: string, findingIds: string[], envoyerMaintenant: boolean): Observable<ResultatCollecteDto> {
    return this.http.post<ResultatCollecteDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/bulk`, {
      findingIds,
      apercuSeulement: false,
      envoyerMaintenant,
    });
  }

  envoyer(entrepriseId: string, demandeId: string): Observable<DemandePieceDto> {
    return this.http.post<DemandePieceDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/${demandeId}/envoyer`, {});
  }

  relancer(entrepriseId: string, demandeId: string): Observable<DemandePieceDto> {
    return this.http.post<DemandePieceDto>(`${BASE_URL}/${entrepriseId}/demandes-pieces/${demandeId}/relancer`, {});
  }

  messages(entrepriseId: string, demandeId: string): Observable<MessageCollecteDto[]> {
    return this.http.get<MessageCollecteDto[]>(`${BASE_URL}/${entrepriseId}/demandes-pieces/${demandeId}/messages`);
  }
}
