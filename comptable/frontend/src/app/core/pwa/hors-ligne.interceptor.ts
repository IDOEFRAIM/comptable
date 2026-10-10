import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { throwError } from 'rxjs';
import { HorsLigneService, MESSAGE_HORS_LIGNE_ECRITURE } from './hors-ligne.service';

/**
 * Hors ligne, l'application est en LECTURE SEULE : toute écriture vers l'API est refusée immédiatement avec un message clair, au lieu d'attendre un délai réseau ou d'être perdue en silence. Il n'existe
 * volontairement pas de file d'attente d'écritures différées : en comptabilité, rejouer plus tard une saisie sur des données qui ont pu changer (période clôturée, version modifiée par un collègue) serait
 * dangereux. À placer EN PREMIER dans la chaîne d'intercepteurs pour que ce message ne soit pas remplacé par le message générique « serveur injoignable ».
 */
export const horsLigneInterceptor: HttpInterceptorFn = (req, next) => {
  const horsLigne = inject(HorsLigneService);
  const lecture = req.method === 'GET' || req.method === 'HEAD';
  if (!horsLigne.enLigne() && !lecture && req.url.startsWith('/api/') && !req.url.startsWith('/api/auth/')) {
    return throwError(() => new HttpErrorResponse({ status: 0, url: req.url, error: { code: 'HORS_LIGNE', message: MESSAGE_HORS_LIGNE_ECRITURE } }));
  }
  return next(req);
};
