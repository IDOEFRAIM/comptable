import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenStorageService } from './token-storage.service';

export const ROLE_ADMIN_PLATEFORME = 'ROLE_SUPER_ADMIN';

/** Page d'accueil après connexion selon le rôle : l'administrateur de la plateforme arrive sur /admin, un membre de cabinet sur son espace, un client sur ses dossiers. */
export function accueilPourRoles(roles: string[]): string {
  if (roles.includes(ROLE_ADMIN_PLATEFORME)) {
    return '/admin';
  }
  return roles.includes('ROLE_EXPERT_COMPTABLE') || roles.includes('ROLE_COLLABORATEUR') ? '/cabinet' : '/entreprises';
}

/** Mode administrateur : réservé au personnel de la plateforme. L'API le refuse déjà (SecurityConfig) ; le guard évite seulement d'afficher un écran qui échouerait. */
export const adminGuard: CanActivateFn = () => {
  const roles = inject(TokenStorageService).getRoles();
  return roles.includes(ROLE_ADMIN_PLATEFORME) ? true : inject(Router).parseUrl(accueilPourRoles(roles));
};
