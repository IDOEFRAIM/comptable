import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenStorageService } from './token-storage.service';

/** Cabinet Cockpit (mission P1) : jamais accessible a un CLIENT, meme cote UI - l'API le refuse
 * deja (SecurityConfig), ce guard evite juste d'afficher un ecran qui echouerait a chaque appel. */
export const cabinetGuard: CanActivateFn = () => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  const roles = tokenStorage.getRoles();
  const estMembreCabinet = roles.includes('ROLE_EXPERT_COMPTABLE') || roles.includes('ROLE_COLLABORATEUR');

  return estMembreCabinet ? true : router.parseUrl('/entreprises');
};
