/** Préfixe des brouillons de saisie gardés sur l'appareil (voir BrouillonLocalService). */
export const PREFIXE_BROUILLON = 'warimara.brouillon.';

/**
 * Supprime tous les brouillons de saisie de l'appareil. Appelé à la déconnexion et quand une autre personne se connecte : une saisie en cours (montants, tiers, libellés) ne doit pas rester lisible pour
 * la personne suivante. Fichier sans dépendance pour pouvoir être appelé depuis la gestion de session sans import circulaire.
 */
export function supprimerTousLesBrouillons(): void {
  try {
    const cles: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const cle = localStorage.key(i);
      if (cle?.startsWith(PREFIXE_BROUILLON)) {
        cles.push(cle);
      }
    }
    cles.forEach((cle) => localStorage.removeItem(cle));
  } catch {
    // stockage indisponible : rien à supprimer
  }
}
