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

  /** Change le mot de passe de l'utilisateur connecté ; le serveur renvoie un nouveau jeton (les anciens sont révoqués). */
  changerMotDePasse(motDePasseActuel: string, nouveauMotDePasse: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/change-password', { motDePasseActuel, nouveauMotDePasse })
      .pipe(tap((reponse) => this.tokenStorage.setToken(reponse.token)));
  }

  /** Public : le jeton à usage unique remis par l'expert du cabinet tient lieu d'authentification. */
  reinitialiserMotDePasse(jeton: string, nouveauMotDePasse: string): Observable<void> {
    return this.http.post<void>('/api/auth/reset-password', { jeton, nouveauMotDePasse });
  }

  logout(): void {
    this.tokenStorage.clear();
  }

  estConnecte(): boolean {
    return !!this.tokenStorage.getToken();
  }
}
