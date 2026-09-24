import { AbstractControl } from '@angular/forms';

/**
 * Traduit l'etat d'erreur d'un FormControl en message lisible, uniquement une fois le champ
 * touche (evite d'afficher des erreurs avant que l'utilisateur ait interagi avec le champ).
 */
export function messageErreurChamp(control: AbstractControl | null | undefined): string | null {
  if (!control || !control.touched || control.valid) {
    return null;
  }
  const erreurs = control.errors ?? {};
  if (erreurs['required']) {
    return 'Ce champ est requis.';
  }
  if (erreurs['email']) {
    return "Adresse email invalide.";
  }
  if (erreurs['minlength']) {
    return `Doit contenir au moins ${erreurs['minlength'].requiredLength} caracteres.`;
  }
  return 'Valeur invalide.';
}
