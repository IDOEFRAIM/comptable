import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, map, throwError } from 'rxjs';
import { FileAttenteService, REJEU_FILE_ATTENTE } from './file-attente.service';
import { HorsLigneService, MESSAGE_HORS_LIGNE_ECRITURE } from './hors-ligne.service';

const ROUTE_DOCUMENT = /^\/api\/entreprises\/([^/]+)\/documents$/;
const ROUTE_ECRITURE = /^\/api\/entreprises\/([^/]+)\/ecritures$/;
const ROUTE_OPERATION = /^\/api\/entreprises\/([^/]+)\/(operations\/(achat|vente|reglement|avoir)\/proposition)$/;

const LIBELLES_OPERATION: Record<string, string> = { achat: 'Achat', vente: 'Vente', reglement: 'Règlement', avoir: 'Avoir' };

/** Les seules écritures acceptées hors ligne (voir {@link FileAttenteService}) : dépôt d'une pièce, création d'une écriture en brouillon et opération de Saisie rapide. */
const ROUTE_TIERS = /^\/api\/entreprises\/([^/]+)\/tiers$/;

function actionEnfilable(req: HttpRequest<unknown>): { entrepriseId: string; type: 'DOCUMENT' | 'ECRITURE' | 'OPERATION' | 'TIERS'; chemin?: string; categorie?: string } | null {
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
  const tiers = ROUTE_TIERS.exec(chemin);
  if (tiers && req.body && typeof req.body === 'object') {
    return { entrepriseId: tiers[1], type: 'TIERS' };
  }
  const operation = ROUTE_OPERATION.exec(chemin);
  if (operation && req.body && typeof req.body === 'object') {
    return { entrepriseId: operation[1], type: 'OPERATION', chemin: operation[2], categorie: operation[3] };
  }
  return null;
}

/** « Achat : Transport sacs de riz (45 000) » : ce que le comptable reconnaît dans la liste d'attente. */
function libelleOperation(categorie: string, corps: { libelle?: string; montantHt?: number; montant?: number }): string {
  const montant = Number(corps.montantHt ?? corps.montant ?? 0);
  return `${LIBELLES_OPERATION[categorie] ?? 'Opération'} : ${corps.libelle || 'sans libellé'} (${montant.toLocaleString('fr-FR')})`;
}

/**
 * Hors ligne, l'application est en LECTURE SEULE, sauf trois saisies sûres à rejouer plus tard qui sont mises en file d'attente sur l'appareil puis envoyées au retour d'Internet (dépôt d'une pièce,
 * écriture en brouillon, opération de Saisie rapide : voir {@link FileAttenteService}). Toute autre écriture vers l'API est refusée immédiatement avec un message clair, au lieu d'attendre un délai réseau ou d'être perdue en
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
  if (action?.type === 'TIERS') {
    // le tiers créé hors ligne est rendu tout de suite (201), avec un identifiant provisoire : la page l'affiche et les saisies suivantes peuvent l'utiliser
    return from(inject(FileAttenteService).enfilerTiers(action.entrepriseId, req.body as never)).pipe(map((tiers) => new HttpResponse({ status: 201, statusText: 'Created', url: req.url, body: tiers })));
  }
  if (action) {
    const file = inject(FileAttenteService);
    const mise =
      action.type === 'DOCUMENT'
        ? mettreDocumentEnFile(file, action.entrepriseId, req.body as FormData)
        : action.type === 'OPERATION'
          ? file.enfilerOperation(action.entrepriseId, action.chemin!, req.body, libelleOperation(action.categorie!, req.body as never))
          : file.enfilerEcriture(action.entrepriseId, req.body as never);
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
