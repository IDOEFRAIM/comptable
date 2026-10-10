/** Nom du cache navigateur (API Cache) qui garde les dernières lectures d'API consultées, pour les relire hors ligne. */
export const NOM_CACHE_DONNEES = 'warimara-donnees-v1';

/**
 * Supprime du navigateur toutes les données d'API gardées pour le hors ligne. Appelé à chaque changement de session (connexion, déconnexion, session expirée, changement de mot de passe) : les données d'un
 * cabinet ne doivent JAMAIS rester lisibles hors ligne pour la personne qui se connecte ensuite sur le même appareil. L'application elle-même (code, images) reste en cache.
 */
export function viderCachesDonnees(): void {
  if (typeof caches === 'undefined') {
    return;
  }
  caches
    .keys()
    .then((noms) => Promise.all(noms.filter((nom) => nom === NOM_CACHE_DONNEES || (nom.startsWith('ngsw:') && nom.includes(':data:'))).map((nom) => caches.delete(nom))))
    .catch(() => undefined);
}
