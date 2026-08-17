import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DocumentDto } from '../documents/models/document.model';
import { DocumentEnAttenteValidationDto, ValiderDocumentRequest } from './models/document-agent.model';

const BASE_URL = '/api/entreprises';

/** API de l'inbox de validation par lot (pieces en pause dans le pipeline agentique, Sprint 3/4). */
@Injectable({ providedIn: 'root' })
export class DocumentAgentService {
  private readonly http = inject(HttpClient);

  listerEnAttenteValidation(entrepriseId: string): Observable<DocumentEnAttenteValidationDto[]> {
    return this.http.get<DocumentEnAttenteValidationDto[]>(`${BASE_URL}/${entrepriseId}/documents-agent/en-attente`);
  }

  validerEtReprendre(
    entrepriseId: string,
    documentId: string,
    request: ValiderDocumentRequest,
  ): Observable<DocumentDto> {
    return this.http.post<DocumentDto>(
      `${BASE_URL}/${entrepriseId}/documents-agent/${documentId}/valider-et-reprendre`,
      request,
    );
  }

  /** Recupere le fichier original en Blob (pas en URL directe sur un &lt;img&gt;) : le viewer doit
   * passer par HttpClient pour que l'intercepteur JWT attache le token - un &lt;img [src]&gt; brut
   * ferait une requete navigateur nue, sans authentification, et echouerait en 401/403. */
  telechargerFichier(entrepriseId: string, documentId: string): Observable<Blob> {
    return this.http.get(`${BASE_URL}/${entrepriseId}/documents/${documentId}/fichier`, { responseType: 'blob' });
  }
}
