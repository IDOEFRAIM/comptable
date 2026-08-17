import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { NotificationDto } from './models/notification.model';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly http = inject(HttpClient);

  lister(): Observable<NotificationDto[]> {
    return this.http.get<NotificationDto[]>('/api/notifications');
  }

  compterNonLues(): Observable<{ nonLues: number }> {
    return this.http.get<{ nonLues: number }>('/api/notifications/non-lues/compteur');
  }

  marquerLue(id: string): Observable<NotificationDto> {
    return this.http.post<NotificationDto>(`/api/notifications/${id}/marquer-lue`, {});
  }

  marquerToutesLues(): Observable<void> {
    return this.http.post<void>('/api/notifications/marquer-toutes-lues', {});
  }
}
