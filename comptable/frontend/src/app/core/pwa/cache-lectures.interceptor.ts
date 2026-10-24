import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, of, switchMap, tap, throwError } from 'rxjs';
import { normaliserErreur } from '../http/messages-erreur';
import { NOM_CACHE_DONNEES } from './caches-donnees';
import { HorsLigneService } from './hors-ligne.service';

// 30 jours / 800 réponses : un dossier téléchargé pour le hors ligne (voir DossierHorsLigneService) doit rester lisible pendant un déplacement
const DUREE_MAX_MS = 30 * 24 * 3600 * 1000;
const TAILLE_MAX = 800;
const ENTETE_DATE = 'x-warimara-mis-en-cache-le';

/**
 * Lectures d'API gardées pour le hors ligne (liste blanche, JSON uniquement). Volontairement exclus : les téléchargements de fichiers (pièces, exports), les routes d'authentification, le dépôt public et tout ce qui
 * n'est pas listé ici. Le cache est vidé à chaque changement de session (voir caches-donnees.ts).
 */
const ROUTES_EN_CACHE: RegExp[] = [
  /^\/api\/cabinet(\/|$)/,
  /^\/api\/entreprises$/,
  /^\/api\/entreprises\/[^/]+$/,
  /^\/api\/entreprises\/[^/]+\/(tableau-de-bord|documents|comptes|journaux|exercices|tiers|demandes-pieces|ecritures)$/,
  /^\/api\/entreprises\/[^/]+\/(plan-comptable|plan-comptable\/arbre|articles|commandes-achat|factures|factures-avoir|devis|immobilisations|inventaires|regularisations|documents\/hub|documents\/hub\/synthese|demandes-pieces\/collecte|demandes-pieces\/collecte\/a-demander|demandes-pieces\/collecte\/synthese|recouvrement\/risques|tresorerie\/position|tresorerie\/prevision)$/,
  /^\/api\/entreprises\/[^/]+\/ecritures\/[^/]+$/,
  /^\/api\/entreprises\/[^/]+\/revue\/[^/]+(\/[^/]+)?$/,
  /^\/api\/entreprises\/[^/]+\/rapports\/[^/]+$/,
  /^\/api\/entreprises\/[^/]+\/etats-financiers-syscohada\/(dossier-financier|compte-resultat|bilan|tft|notes)$/,
  /^\/api\/entreprises\/[^/]+\/banque\/(synthese|transactions)$/,
  /^\/api\/entreprises\/[^/]+\/fiscalite\/tva\/periodes(\/[^/]+)?$/,
];

export function routeMiseEnCache(url: string): boolean {
  const chemin = url.split('?')[0];
  return ROUTES_EN_CACHE.some((motif) => motif.test(chemin));
}

async function ecrire(cle: string, corps: unknown): Promise<void> {
  const cache = await caches.open(NOM_CACHE_DONNEES);
  await cache.put(cle, new Response(JSON.stringify(corps), { headers: { 'content-type': 'application/json', [ENTETE_DATE]: String(Date.now()) } }));
  const cles = await cache.keys();
  for (const ancienne of cles.slice(0, Math.max(0, cles.length - TAILLE_MAX))) {
    await cache.delete(ancienne);
  }
}

async function lire(cle: string): Promise<unknown | undefined> {
  const cache = await caches.open(NOM_CACHE_DONNEES);
  const reponse = await cache.match(cle);
  if (!reponse) {
    return undefined;
  }
  if (Date.now() - Number(reponse.headers.get(ENTETE_DATE) ?? 0) > DUREE_MAX_MS) {
    await cache.delete(cle);
    return undefined;
  }
  return reponse.json();
}

/**
 * Réseau d'abord : chaque lecture réussie est gardée ; sans réseau (hors ligne, serveur injoignable ou indisponible), la dernière version gardée est servie, sinon l'erreur normale (message « hors ligne » lisible).
 * Les écritures hors ligne sont refusées par {@link horsLigneInterceptor} : l'application est en lecture seule sans réseau.
 */
export const cacheLecturesInterceptor: HttpInterceptorFn = (req, next) => {
  if (typeof caches === 'undefined' || req.method !== 'GET' || req.responseType !== 'json' || !routeMiseEnCache(req.url)) {
    return next(req);
  }
  const horsLigne = inject(HorsLigneService);
  const cle = req.urlWithParams;
  const depuisCache = (erreur: HttpErrorResponse) =>
    from(lire(cle).catch(() => undefined)).pipe(
      switchMap((corps) => (corps === undefined ? throwError(() => erreur) : of(new HttpResponse({ body: corps, status: 200, url: req.url })))),
    );
  if (!horsLigne.enLigne()) {
    return depuisCache(normaliserErreur(new HttpErrorResponse({ status: 0, url: req.url })));
  }
  return next(req).pipe(
    tap((evenement) => {
      if (evenement instanceof HttpResponse && evenement.status === 200 && evenement.body !== null) {
        ecrire(cle, evenement.body).catch(() => undefined);
      }
    }),
    catchError((erreur: HttpErrorResponse) => (erreur.status === 0 || erreur.status === 502 || erreur.status === 503 || erreur.status === 504 ? depuisCache(erreur) : throwError(() => erreur))),
  );
};
