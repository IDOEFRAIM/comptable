import { CanDeactivateFn } from '@angular/router';

/** Un écran de saisie qui peut contenir du travail non enregistré. */
export interface AvecModificationsNonEnregistrees {
  aDesModificationsNonEnregistrees(): boolean;
}

export const MESSAGE_MODIFICATIONS_NON_ENREGISTREES = 'Vous avez des modifications non enregistrées. Quitter cette page les perdra. Continuer ?';

/** Demande une confirmation avant de quitter un écran dont la saisie n'est pas enregistrée ; jamais de blocage si rien n'a été saisi. */
export const modificationsNonEnregistreesGuard: CanDeactivateFn<AvecModificationsNonEnregistrees> = (composant) =>
  !composant.aDesModificationsNonEnregistrees() || window.confirm(MESSAGE_MODIFICATIONS_NON_ENREGISTREES);
