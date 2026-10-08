import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenStorageService } from './token-storage.service';

/** Bloque l'acces aux routes protegees si aucun JWT n'est stocke. */
export const authGuard: CanActivateFn = () => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  if (tokenStorage.getToken()) {
    // Mot de passe temporaire ou réinitialisé : aucune page de travail avant le changement de mot de passe.
    return tokenStorage.doitChangerMotDePasse() ? router.parseUrl('/changer-mot-de-passe') : true;
  }
  return router.parseUrl('/login');
};
