import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CashPositionDto,
  ComparaisonFinancialSnapshotDto,
  FinancialSnapshotDto,
  PrevisionTresorerieDto,
  TypeComparaison,
} from './models/finance.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class FinanceService {
  private readonly http = inject(HttpClient);

  obtenirPosition(entrepriseId: string, date?: string): Observable<CashPositionDto> {
    return this.http.get<CashPositionDto>(`${BASE_URL}/${entrepriseId}/tresorerie/position`, {
      params: date ? { date } : {},
    });
  }

  obtenirPrevision(entrepriseId: string): Observable<PrevisionTresorerieDto[]> {
    return this.http.get<PrevisionTresorerieDto[]>(`${BASE_URL}/${entrepriseId}/tresorerie/prevision`);
  }

  obtenirSnapshot(entrepriseId: string, debut: string, fin: string): Observable<FinancialSnapshotDto> {
    return this.http.get<FinancialSnapshotDto>(`${BASE_URL}/${entrepriseId}/financial-snapshot`, {
      params: { debut, fin },
    });
  }

  obtenirComparaison(
    entrepriseId: string,
    debut: string,
    fin: string,
    comparerA: TypeComparaison = 'PERIODE_PRECEDENTE',
  ): Observable<ComparaisonFinancialSnapshotDto> {
    return this.http.get<ComparaisonFinancialSnapshotDto>(
      `${BASE_URL}/${entrepriseId}/financial-snapshot/comparaison`,
      { params: { debut, fin, comparerA } },
    );
  }
}
