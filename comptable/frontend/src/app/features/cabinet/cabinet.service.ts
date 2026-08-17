import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CabinetAnalyticsDto,
  CabinetCockpitDto,
  CollaborateurDto,
  InboxItemDto,
  WorkloadDto,
} from './models/cabinet.model';

const BASE_URL = '/api/cabinet';

@Injectable({ providedIn: 'root' })
export class CabinetService {
  private readonly http = inject(HttpClient);

  obtenirCockpit(): Observable<CabinetCockpitDto> {
    return this.http.get<CabinetCockpitDto>(`${BASE_URL}/cockpit`);
  }

  obtenirInbox(assigneAMoi: boolean): Observable<InboxItemDto[]> {
    return this.http.get<InboxItemDto[]>(`${BASE_URL}/inbox`, { params: { assigneAMoi } });
  }

  obtenirChargeCollaborateurs(): Observable<WorkloadDto[]> {
    return this.http.get<WorkloadDto[]>(`${BASE_URL}/charge-collaborateurs`);
  }

  obtenirAnalytics(): Observable<CabinetAnalyticsDto> {
    return this.http.get<CabinetAnalyticsDto>(`${BASE_URL}/analytics`);
  }

  obtenirCollaborateurs(): Observable<CollaborateurDto[]> {
    return this.http.get<CollaborateurDto[]>(`${BASE_URL}/collaborateurs`);
  }
}
