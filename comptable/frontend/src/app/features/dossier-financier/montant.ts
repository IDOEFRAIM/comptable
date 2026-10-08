/** Affichage des montants : le frontend n'affiche que des valeurs calculées côté backend (BigDecimal). Aucun calcul, y compris la variation N − N-1 fournie par l'API. */
const FORMAT = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 });

export function formaterMontant(valeur: number | null | undefined): string {
  return valeur === null || valeur === undefined ? '' : FORMAT.format(valeur);
}
