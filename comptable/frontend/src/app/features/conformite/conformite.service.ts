import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ComplianceCenterDto, TaxHealthDto } from './models/conformite.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class ConformiteService {
  private readonly http = inject(HttpClient);

  obtenir(entrepriseId: string): Observable<ComplianceCenterDto> {
    return this.http.get<ComplianceCenterDto>(`${BASE_URL}/${entrepriseId}/conformite`);
  }

  obtenirTaxHealth(entrepriseId: string): Observable<TaxHealthDto> {
    return this.http.get<TaxHealthDto>(`${BASE_URL}/${entrepriseId}/conformite/tax-health`);
  }
}
