import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TokenStorageService } from './token-storage.service';

/** Ajoute le JWT courant sur chaque requete vers l'API. */
export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(TokenStorageService).getToken();
  // Le depot public s'autorise par son lien : on n'y envoie jamais la session d'un utilisateur connecte.
  if (!token || req.url.startsWith('/api/depot/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
