import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthResponse, RegisterCabinetRequest } from '../../features/auth/models/auth.model';
import { TokenStorageService } from './token-storage.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokenStorage = inject(TokenStorageService);

  login(email: string, motDePasse: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/login', { email, motDePasse })
      .pipe(tap((reponse) => this.tokenStorage.setToken(reponse.token)));
  }

  registerCabinet(request: RegisterCabinetRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/register-cabinet', request)
      .pipe(tap((reponse) => this.tokenStorage.setToken(reponse.token)));
  }

  logout(): void {
    this.tokenStorage.clear();
  }

  estConnecte(): boolean {
    return !!this.tokenStorage.getToken();
  }
}
