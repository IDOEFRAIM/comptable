import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AccountingProposalDto } from './models/accounting-proposal.model';
import { ClotureReadinessDto, DossierComptableDto, FindingDto, TableauDeBordDto } from './models/dashboard.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  obtenir(entrepriseId: string): Observable<TableauDeBordDto> {
    return this.http.get<TableauDeBordDto>(`${BASE_URL}/${entrepriseId}/tableau-de-bord`);
  }

  obtenirDossier(entrepriseId: string): Observable<DossierComptableDto> {
    return this.http.get<DossierComptableDto>(`${BASE_URL}/${entrepriseId}/dossier`);
  }

  listerFindings(entrepriseId: string): Observable<FindingDto[]> {
    return this.http.get<FindingDto[]>(`${BASE_URL}/${entrepriseId}/findings`);
  }

  resoudreFinding(entrepriseId: string, findingId: string): Observable<FindingDto> {
    return this.http.post<FindingDto>(`${BASE_URL}/${entrepriseId}/findings/${findingId}/resoudre`, {});
  }

  rejeterFinding(entrepriseId: string, findingId: string): Observable<FindingDto> {
    return this.http.post<FindingDto>(`${BASE_URL}/${entrepriseId}/findings/${findingId}/rejeter`, {});
  }

  assignerFinding(
    entrepriseId: string,
    findingId: string,
    assignedToId: string | null,
    dueDate: string | null,
  ): Observable<FindingDto> {
    return this.http.post<FindingDto>(`${BASE_URL}/${entrepriseId}/findings/${findingId}/assigner`, {
      assignedToId,
      dueDate,
    });
  }

  obtenirClotureReadiness(entrepriseId: string): Observable<ClotureReadinessDto> {
    return this.http.get<ClotureReadinessDto>(`${BASE_URL}/${entrepriseId}/cloture-readiness`);
  }

  obtenirPropositionPourDocument(entrepriseId: string, documentId: string): Observable<AccountingProposalDto> {
    return this.http.get<AccountingProposalDto>(
      `${BASE_URL}/${entrepriseId}/comptabilisation/documents/${documentId}/proposition`,
    );
  }

  accepterProposition(entrepriseId: string, propositionId: string): Observable<AccountingProposalDto> {
    return this.http.post<AccountingProposalDto>(
      `${BASE_URL}/${entrepriseId}/comptabilisation/propositions/${propositionId}/accepter`,
      {},
    );
  }

  rejeterProposition(entrepriseId: string, propositionId: string): Observable<AccountingProposalDto> {
    return this.http.post<AccountingProposalDto>(
      `${BASE_URL}/${entrepriseId}/comptabilisation/propositions/${propositionId}/rejeter`,
      {},
    );
  }
}
