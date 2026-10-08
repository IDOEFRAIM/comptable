/** Extrait un message lisible d'une erreur HTTP : message metier du backend s'il existe, sinon
 * une explication adaptee au statut (jamais un « impossible » nu qui laisse l'utilisateur sans piste). */
export function messageErreur(err: unknown, parDefaut: string): string {
  const e = err as { status?: number; error?: unknown } | null;
  const corps = e?.error as { message?: unknown; detail?: unknown; errors?: unknown } | string | null | undefined;

  if (typeof corps === 'string' && corps.trim() && !corps.trim().startsWith('<')) {
    return corps.trim();
  }
  if (corps && typeof corps === 'object') {
    for (const champ of [corps.message, corps.detail]) {
      if (typeof champ === 'string' && champ.trim()) {
        return champ.trim();
      }
    }
  }

  switch (e?.status) {
    case 0:
      return 'Le serveur est injoignable. Vérifiez votre connexion puis réessayez.';
    case 400:
      return `${parDefaut} Certaines informations envoyées sont invalides.`;
    case 403:
      return "Vous n'avez pas les droits nécessaires pour effectuer cette action.";
    case 404:
      return `${parDefaut} Un élément concerné est introuvable (il a peut-être été supprimé).`;
    case 409:
      return `${parDefaut} Cette opération est en conflit avec l'état actuel des données.`;
    case 422:
      return `${parDefaut} Les données du document ne permettent pas cette opération (montants, date ou exercice comptable à vérifier).`;
    default:
      return e?.status && e.status >= 500
        ? `${parDefaut} Une erreur est survenue côté serveur, réessayez dans un instant.`
        : parDefaut;
  }
}
