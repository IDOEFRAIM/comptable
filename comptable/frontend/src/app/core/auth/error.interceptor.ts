import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { normaliserErreur } from '../http/messages-erreur';
import { TokenStorageService } from './token-storage.service';

/**
 * Traitement uniforme des erreurs HTTP : 401 = session absente / expirée / révoquée (retour à la connexion) ; 403 PASSWORD_CHANGE_REQUIRED = mot de passe temporaire (page de changement) ; toutes les autres
 * erreurs sont normalisées (message lisible, identifiant de corrélation) pour que l'interface n'affiche jamais de JSON brut ni de trace.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // une route publique (connexion, réinitialisation, dépôt par lien) gère elle-même son 401
      const publique = req.url.startsWith('/api/auth/') || req.url.startsWith('/api/depot/');
      if (error.status === 401 && !publique) {
        tokenStorage.clear();
        router.navigateByUrl('/login');
      }
      if (error.status === 403 && error.error?.code === 'PASSWORD_CHANGE_REQUIRED') {
        router.navigateByUrl('/changer-mot-de-passe');
      }
      return throwError(() => normaliserErreur(error));
    }),
  );
};
