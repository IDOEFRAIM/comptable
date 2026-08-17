import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { TokenStorageService } from './token-storage.service';

/** Le CLIENT n'a pas acces au tableau de bord (reserve au cabinet) : on l'envoie
 * directement sur ses documents plutot que de le laisser heurter un 403. */
export const defaultTabRedirectGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  const roles = tokenStorage.getRoles();
  const estMembreCabinet = roles.includes('ROLE_EXPERT_COMPTABLE') || roles.includes('ROLE_COLLABORATEUR');
  const entrepriseId = route.paramMap.get('entrepriseId');

  const ongletParDefaut = estMembreCabinet ? 'tableau-de-bord' : 'documents';
  return router.parseUrl(`/entreprises/${entrepriseId}/${ongletParDefaut}`);
};
