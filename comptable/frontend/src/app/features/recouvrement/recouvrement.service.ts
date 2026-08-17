import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CollectionRiskDto } from './models/recouvrement.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class RecouvrementService {
  private readonly http = inject(HttpClient);

  listerRisques(entrepriseId: string): Observable<CollectionRiskDto[]> {
    return this.http.get<CollectionRiskDto[]>(`${BASE_URL}/${entrepriseId}/recouvrement/risques`);
  }
}
