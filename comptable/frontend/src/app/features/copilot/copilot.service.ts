import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CopilotFeedback, CopilotResponseDto } from './models/copilot.model';

@Injectable({ providedIn: 'root' })
export class CopilotService {
  private readonly http = inject(HttpClient);

  demanderEntreprise(entrepriseId: string, question: string): Observable<CopilotResponseDto> {
    return this.http.post<CopilotResponseDto>(`/api/entreprises/${entrepriseId}/copilot/question`, { question });
  }

  demanderCabinet(question: string): Observable<CopilotResponseDto> {
    return this.http.post<CopilotResponseDto>(`/api/cabinet/copilot/question`, { question });
  }

  demanderDirigeant(entrepriseId: string, question: string): Observable<CopilotResponseDto> {
    return this.http.post<CopilotResponseDto>(
      `/api/entreprises/${entrepriseId}/copilot-dirigeant/question`,
      { question },
    );
  }

  envoyerFeedback(queryId: string, feedback: CopilotFeedback, reason: string | null): Observable<void> {
    return this.http.post<void>(`/api/copilot/queries/${queryId}/feedback`, { feedback, reason });
  }
}
