import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreerDeclarationRequest,
  DeclarationFiscaleDto,
  InitierPaiementRequest,
  PaiementFiscalDto,
} from './models/fiscalite.model';

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

  listerPaiements(entrepriseId: string, declarationId: string): Observable<PaiementFiscalDto[]> {
    return this.http.get<PaiementFiscalDto[]>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/${declarationId}/paiements`,
    );
  }

  initierPaiement(entrepriseId: string, declarationId: string, request: InitierPaiementRequest): Observable<PaiementFiscalDto> {
    return this.http.post<PaiementFiscalDto>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/${declarationId}/paiements`,
      request,
    );
  }

  /** Reservee au mode simulation (voir DOCUMENTATION.md) en l'absence de webhook reel des fournisseurs. */
  simulerConfirmationPaiement(
    entrepriseId: string,
    declarationId: string,
    paiementId: string,
    reussite: boolean,
  ): Observable<PaiementFiscalDto> {
    return this.http.post<PaiementFiscalDto>(
      `${BASE_URL}/${entrepriseId}/declarations-fiscales/${declarationId}/paiements/${paiementId}/simuler-confirmation`,
      { reussite },
    );
  }
}
