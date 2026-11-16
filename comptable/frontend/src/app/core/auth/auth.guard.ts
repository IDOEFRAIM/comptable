import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { HorsLigneService } from '../pwa/hors-ligne.service';
import { CodePinService } from './code-pin.service';
import { TokenStorageService } from './token-storage.service';

/** Bloque l'acces aux routes protegees si aucun JWT n'est stocke. */
export const authGuard: CanActivateFn = () => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  if (!tokenStorage.getToken()) {
    return router.parseUrl('/login');
  }
  if (tokenStorage.sessionExpiree()) {
    // Session expirée : avec Internet, reconnexion obligatoire ; sans Internet, accès conservé 7 jours aux dossiers téléchargés, derrière le code PIN s'il est défini.
    const horsLigne = !inject(HorsLigneService).enLigne();
    return horsLigne && tokenStorage.peutContinuerHorsLigne() && inject(CodePinService).deverrouille() ? true : router.parseUrl('/login');
  }
  // Mot de passe temporaire ou réinitialisé : aucune page de travail avant le changement de mot de passe.
  return tokenStorage.doitChangerMotDePasse() ? router.parseUrl('/changer-mot-de-passe') : true;
};
