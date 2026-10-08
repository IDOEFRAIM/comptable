import { HttpErrorResponse } from '@angular/common/http';

/** Message lisible par défaut selon le statut HTTP (jamais de JSON brut, de trace ni de code technique). */
export function messageParDefaut(statut: number): string {
  switch (statut) {
    case 0:
      return 'Le serveur est injoignable. Vérifiez votre connexion puis réessayez.';
    case 400:
      return 'La demande est invalide. Vérifiez les informations saisies.';
    case 401:
      return 'Votre session a expiré. Reconnectez-vous.';
    case 403:
      return 'Vous n’avez pas le droit d’effectuer cette action.';
    case 404:
      return 'Cette ressource est introuvable.';
    case 409:
      return 'Cette opération est en conflit avec l’état actuel. Rechargez la page puis réessayez.';
    case 413:
      return 'Le fichier dépasse la taille maximale autorisée.';
    case 422:
      return 'Certaines informations ne sont pas acceptées. Corrigez-les puis réessayez.';
    case 429:
      return 'Trop de tentatives. Patientez quelques minutes avant de réessayer.';
    default:
      return statut >= 500 ? 'Une erreur est survenue côté serveur. Réessayez ; si elle persiste, communiquez l’identifiant ci-dessous au support.' : 'Une erreur est survenue.';
  }
}

/**
 * Normalise toute erreur HTTP au format {code, message, correlationId} : si le serveur n'a pas renvoyé de message exploitable (réponse HTML d'un proxy, panne réseau, 5xx), un message en français est fourni ;
 * l'identifiant de corrélation (en-tête X-Request-Id) est conservé pour le support. Les composants continuent de lire `err.error?.message`.
 */
export function normaliserErreur(erreur: HttpErrorResponse): HttpErrorResponse {
  const corps = erreur.error && typeof erreur.error === 'object' ? (erreur.error as Record<string, unknown>) : null;
  const messageServeur = corps && typeof corps['message'] === 'string' && (corps['message'] as string).trim() ? (corps['message'] as string) : null;
  const correlationId = (corps && typeof corps['correlationId'] === 'string' ? (corps['correlationId'] as string) : null) ?? erreur.headers?.get('X-Request-Id') ?? null;
  // un 5xx ne montre jamais le texte du serveur tel quel (il est générique) : on y ajoute l'identifiant de corrélation
  const message = erreur.status >= 500 || !messageServeur ? messageParDefaut(erreur.status) : messageServeur;
  const suffixe = erreur.status >= 500 && correlationId ? ` (réf. ${correlationId})` : '';
  return new HttpErrorResponse({
    error: { ...(corps ?? {}), code: (corps?.['code'] as string | undefined) ?? 'ERREUR', message: message + suffixe, correlationId },
    headers: erreur.headers,
    status: erreur.status,
    statusText: erreur.statusText,
    url: erreur.url ?? undefined,
  });
}
