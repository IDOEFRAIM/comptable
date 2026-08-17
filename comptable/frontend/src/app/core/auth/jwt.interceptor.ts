import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TokenStorageService } from './token-storage.service';

/** Ajoute le JWT courant sur chaque requete vers l'API. */
export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(TokenStorageService).getToken();
  if (!token) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
