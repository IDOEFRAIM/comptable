import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreerEntrepriseRequest,
  EntrepriseDto,
  InviterUtilisateurRequest,
  UtilisateurInviteDto,
} from './models/entreprise.model';

@Injectable({ providedIn: 'root' })
export class EntrepriseService {
  private readonly http = inject(HttpClient);

  lister(): Observable<EntrepriseDto[]> {
    return this.http.get<EntrepriseDto[]>('/api/entreprises');
  }

  creer(request: CreerEntrepriseRequest): Observable<EntrepriseDto> {
    return this.http.post<EntrepriseDto>('/api/entreprises', request);
  }

  inviter(entrepriseId: string, request: InviterUtilisateurRequest): Observable<UtilisateurInviteDto> {
    return this.http.post<UtilisateurInviteDto>(`/api/entreprises/${entrepriseId}/inviter`, request);
  }
}
