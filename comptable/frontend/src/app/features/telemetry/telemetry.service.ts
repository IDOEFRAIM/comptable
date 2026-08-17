import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ProductKpiDto } from './models/telemetry.model';

const BASE_URL = '/api/cabinet';

@Injectable({ providedIn: 'root' })
export class TelemetryService {
  private readonly http = inject(HttpClient);

  obtenirKpi(): Observable<ProductKpiDto> {
    return this.http.get<ProductKpiDto>(`${BASE_URL}/kpi`);
  }
}
