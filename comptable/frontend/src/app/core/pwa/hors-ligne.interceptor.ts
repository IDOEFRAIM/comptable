import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, map, throwError } from 'rxjs';
import { FileAttenteService, REJEU_FILE_ATTENTE } from './file-attente.service';
import { HorsLigneService, MESSAGE_HORS_LIGNE_ECRITURE } from './hors-ligne.service';

const ROUTE_DOCUMENT = /^\/api\/entreprises\/([^/]+)\/documents$/;
const ROUTE_ECRITURE = /^\/api\/entreprises\/([^/]+)\/ecritures$/;

/** Les seules écritures acceptées hors ligne (voir {@link FileAttenteService}) : dépôt d'une pièce et création d'une écriture en brouillon. */
function actionEnfilable(req: HttpRequest<unknown>): { entrepriseId: string; type: 'DOCUMENT' | 'ECRITURE' } | null {
  if (req.method !== 'POST' || req.context.get(REJEU_FILE_ATTENTE)) {
    return null;
  }
  const chemin = req.url.split('?')[0];
  const document = ROUTE_DOCUMENT.exec(chemin);
  if (document && req.body instanceof FormData) {
    return { entrepriseId: document[1], type: 'DOCUMENT' };
  }
  const ecriture = ROUTE_ECRITURE.exec(chemin);
  if (ecriture && req.body && typeof req.body === 'object') {
    return { entrepriseId: ecriture[1], type: 'ECRITURE' };
  }
  return null;
}

/**
 * Hors ligne, l'application est en LECTURE SEULE, sauf deux saisies sûres à rejouer plus tard qui sont mises en file d'attente sur l'appareil puis envoyées au retour d'Internet (dépôt d'une pièce,
 * écriture en brouillon : voir {@link FileAttenteService}). Toute autre écriture vers l'API est refusée immédiatement avec un message clair, au lieu d'attendre un délai réseau ou d'être perdue en
 * silence : en comptabilité, rejouer plus tard une validation, une clôture ou une saisie dépendant de l'état du serveur serait dangereux (période clôturée, version modifiée par un collègue). À placer
 * EN PREMIER dans la chaîne d'intercepteurs pour que ce message ne soit pas remplacé par le message générique « serveur injoignable ».
 */
export const horsLigneInterceptor: HttpInterceptorFn = (req, next) => {
  const horsLigne = inject(HorsLigneService);
  const lecture = req.method === 'GET' || req.method === 'HEAD';
  if (horsLigne.enLigne() || lecture || !req.url.startsWith('/api/') || req.url.startsWith('/api/auth/')) {
    return next(req);
  }
  const action = actionEnfilable(req);
  if (action) {
    const file = inject(FileAttenteService);
    const mise = action.type === 'DOCUMENT' ? mettreDocumentEnFile(file, action.entrepriseId, req.body as FormData) : file.enfilerEcriture(action.entrepriseId, req.body as never);
    // 202 Accepted : la saisie est enregistrée sur l'appareil, pas encore sur le serveur
    return from(mise).pipe(map(() => new HttpResponse({ status: 202, statusText: 'Accepted', url: req.url, body: { enFileAttente: true } })));
  }
  return throwError(() => new HttpErrorResponse({ status: 0, url: req.url, error: { code: 'HORS_LIGNE', message: MESSAGE_HORS_LIGNE_ECRITURE } }));
};

function mettreDocumentEnFile(file: FileAttenteService, entrepriseId: string, formData: FormData): Promise<void> {
  const fichier = formData.get('fichier');
  const type = formData.get('typeDocument');
  if (!(fichier instanceof File) || typeof type !== 'string') {
    return Promise.reject(new Error('Dépôt invalide'));
  }
  return file.enfilerDocument(entrepriseId, type, fichier);
}
