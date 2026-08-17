import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreerDeclarationRequest, DeclarationFiscaleDto } from './models/fiscalite.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class FiscaliteService {
  private readonly http = inject(HttpClient);

  lister(entrepriseId: string): Observable<DeclarationFiscaleDto[]> {
    return this.http.get<DeclarationFiscaleDto[]>(`${BASE_URL}/${entrepriseId}/declarations-fiscales`);
  }

  creer(entrepriseId: string, request: CreerDeclarationRequest): Observable<DeclarationFiscaleDto> {
    return this.http.post<DeclarationFiscaleDto>(`${BASE_URL}/${entrepriseId}/declarations-fiscales`, request);
  }

  genererEcheancierTva(entrepriseId: string, annee: number): Observable<DeclarationFiscaleDto[]> {
    return this.http.post<DeclarationFiscaleDto[]>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/generer-echeancier-tva`,
      { annee },
    );
  }

  marquerDeposee(entrepriseId: string, declarationId: string, dateDeclaration?: string): Observable<DeclarationFiscaleDto> {
    return this.http.post<DeclarationFiscaleDto>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/${declarationId}/marquer-deposee`,
      { dateDeclaration },
    );
  }

  marquerPayee(entrepriseId: string, declarationId: string, datePaiement?: string, montant?: number): Observable<DeclarationFiscaleDto> {
    return this.http.post<DeclarationFiscaleDto>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/${declarationId}/marquer-payee`,
      { datePaiement, montant },
    );
  }

  recalculerTva(entrepriseId: string, declarationId: string): Observable<DeclarationFiscaleDto> {
    return this.http.post<DeclarationFiscaleDto>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/${declarationId}/recalculer-tva`,
      {},
    );
  }
}
