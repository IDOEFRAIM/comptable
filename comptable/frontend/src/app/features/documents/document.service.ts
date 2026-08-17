import { HttpClient, HttpEvent, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { filter, map } from 'rxjs/operators';
import { DocumentDto, StatutDocument, TypeDocument } from './models/document.model';

const BASE_URL = '/api/entreprises';

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly http = inject(HttpClient);

  /** Emet la progression (0-100) puis le DocumentDto final. */
  uploader(entrepriseId: string, typeDocument: TypeDocument, fichier: File): Observable<number | DocumentDto> {
    const formData = new FormData();
    formData.append('typeDocument', typeDocument);
    formData.append('fichier', fichier);

    return this.http
      .post<DocumentDto>(`${BASE_URL}/${entrepriseId}/documents`, formData, {
        reportProgress: true,
        observe: 'events',
      })
      .pipe(
        filter(
          (event: HttpEvent<DocumentDto>) =>
            event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response,
        ),
        map((event: HttpEvent<DocumentDto>) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            return Math.round((event.loaded / event.total) * 100);
          }
          if (event.type === HttpEventType.Response) {
            return event.body as DocumentDto;
          }
          return 0;
        }),
      );
  }

  lister(entrepriseId: string, statut?: StatutDocument): Observable<DocumentDto[]> {
    const url = statut
      ? `${BASE_URL}/${entrepriseId}/documents?statut=${statut}`
      : `${BASE_URL}/${entrepriseId}/documents`;
    return this.http.get<DocumentDto[]>(url);
  }
}
