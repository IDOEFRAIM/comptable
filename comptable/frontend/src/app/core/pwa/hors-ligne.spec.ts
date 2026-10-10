import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { cacheLecturesInterceptor, routeMiseEnCache } from './cache-lectures.interceptor';
import { NOM_CACHE_DONNEES, viderCachesDonnees } from './caches-donnees';
import { horsLigneInterceptor } from './hors-ligne.interceptor';
import { HorsLigneService, MESSAGE_HORS_LIGNE_ECRITURE } from './hors-ligne.service';

describe('Mode hors ligne (PWA)', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  const enLigne = signal(true);

  beforeEach(() => {
    enLigne.set(true);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([horsLigneInterceptor])), provideHttpClientTesting(), { provide: HorsLigneService, useValue: { enLigne } }],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  it('hors ligne, une écriture est refusée tout de suite, sans requête réseau, avec un message clair', () => {
    enLigne.set(false);
    let erreur: HttpErrorResponse | null = null;
    client.post('/api/entreprises/e1/ecritures', {}).subscribe({ error: (e) => (erreur = e) });
    http.expectNone('/api/entreprises/e1/ecritures');
    expect(erreur!.error.code).toBe('HORS_LIGNE');
    expect(erreur!.error.message).toBe(MESSAGE_HORS_LIGNE_ECRITURE);
  });

  it('toutes les méthodes d’écriture sont refusées hors ligne', () => {
    enLigne.set(false);
    for (const methode of ['PUT', 'PATCH', 'DELETE'] as const) {
      let code = '';
      client.request(methode, '/api/x/1', { body: {} }).subscribe({ error: (e) => (code = e.error.code) });
      expect(code).withContext(methode).toBe('HORS_LIGNE');
    }
  });

  it('hors ligne, les lectures partent normalement (le service worker répond depuis le cache)', () => {
    enLigne.set(false);
    client.get('/api/entreprises').subscribe();
    http.expectOne('/api/entreprises').flush([]);
  });

  it('hors ligne, la connexion n’est pas bloquée par l’intercepteur (elle échouera d’elle-même, message réseau)', () => {
    enLigne.set(false);
    client.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    http.expectOne('/api/auth/login').flush({}, { status: 0, statusText: 'x' });
  });

  it('en ligne, les écritures passent', () => {
    client.post('/api/entreprises', {}).subscribe();
    http.expectOne('/api/entreprises').flush({});
  });

  it('vider les caches de données ne casse rien, avec ou sans service worker', () => {
    expect(() => viderCachesDonnees()).not.toThrow();
  });
});

describe('HorsLigneService', () => {
  it('suit les évènements online / offline du navigateur et propose l’installation quand le navigateur la signale', () => {
    const service = TestBed.inject(HorsLigneService);
    window.dispatchEvent(new Event('offline'));
    expect(service.enLigne()).toBeFalse();
    window.dispatchEvent(new Event('online'));
    expect(service.enLigne()).toBeTrue();
    expect(service.installable()).toBeFalse();
    const evenement = new Event('beforeinstallprompt', { cancelable: true });
    window.dispatchEvent(evenement);
    expect(service.installable()).toBeTrue();
    expect(evenement.defaultPrevented).toBeTrue();
    window.dispatchEvent(new Event('appinstalled'));
    expect(service.installable()).toBeFalse();
  });
});

describe('Cache des lectures pour le hors ligne', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  const enLigne = signal(true);
  const attendre = () => new Promise((resolve) => setTimeout(resolve, 150));

  beforeEach(async () => {
    enLigne.set(true);
    await caches.delete(NOM_CACHE_DONNEES);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([horsLigneInterceptor, cacheLecturesInterceptor])), provideHttpClientTesting(), { provide: HorsLigneService, useValue: { enLigne } }],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(async () => caches.delete(NOM_CACHE_DONNEES));

  it('seules les lectures listées sont gardées : jamais un téléchargement de fichier, une route d’authentification ou une route inconnue', () => {
    expect(routeMiseEnCache('/api/cabinet/equipe')).toBeTrue();
    expect(routeMiseEnCache('/api/entreprises')).toBeTrue();
    expect(routeMiseEnCache('/api/entreprises/e1/etats-financiers-syscohada/bilan?exerciceId=x')).toBeTrue();
    expect(routeMiseEnCache('/api/entreprises/e1/documents/d1/fichier')).toBeFalse();
    expect(routeMiseEnCache('/api/entreprises/e1/etats-financiers-syscohada/DOSSIER_COMPLET/export')).toBeFalse();
    expect(routeMiseEnCache('/api/auth/login')).toBeFalse();
    expect(routeMiseEnCache('/api/depot/jeton')).toBeFalse();
    expect(routeMiseEnCache('/api/inconnu')).toBeFalse();
  });

  it('une lecture réussie est gardée puis relue hors ligne, sans requête réseau', async () => {
    let premiere: unknown;
    client.get('/api/cabinet/equipe').subscribe((r) => (premiere = r));
    http.expectOne('/api/cabinet/equipe').flush([{ id: 1, nom: 'Awa' }]);
    await attendre();
    expect(premiere).toEqual([{ id: 1, nom: 'Awa' }]);

    enLigne.set(false);
    let horsLigne: unknown;
    client.get('/api/cabinet/equipe').subscribe((r) => (horsLigne = r));
    await attendre();
    http.expectNone('/api/cabinet/equipe');
    expect(horsLigne).toEqual([{ id: 1, nom: 'Awa' }]);
  });

  it('serveur injoignable en ligne : la dernière version gardée est servie', async () => {
    client.get('/api/entreprises').subscribe();
    http.expectOne('/api/entreprises').flush([{ id: 'e1' }]);
    await attendre();
    let recu: unknown;
    client.get('/api/entreprises').subscribe((r) => (recu = r));
    http.expectOne('/api/entreprises').error(new ProgressEvent('error'), { status: 0 });
    await attendre();
    expect(recu).toEqual([{ id: 'e1' }]);
  });

  it('hors ligne sans copie gardée : message clair « hors ligne », jamais une page blanche muette', async () => {
    enLigne.set(false);
    let erreur: HttpErrorResponse | null = null;
    client.get('/api/entreprises/e9/comptes').subscribe({ error: (e) => (erreur = e) });
    await attendre();
    expect(erreur).not.toBeNull();
    expect(erreur!.status).toBe(0);
  });

  it('une erreur métier (4xx) n’est jamais remplacée par une copie gardée', async () => {
    client.get('/api/entreprises').subscribe();
    http.expectOne('/api/entreprises').flush([{ id: 'e1' }]);
    await attendre();
    let statut = 0;
    client.get('/api/entreprises').subscribe({ error: (e) => (statut = e.status) });
    http.expectOne('/api/entreprises').flush({}, { status: 403, statusText: 'Forbidden' });
    await attendre();
    expect(statut).toBe(403);
  });

  it('un changement de session vide les données gardées', async () => {
    client.get('/api/entreprises').subscribe();
    http.expectOne('/api/entreprises').flush([{ id: 'e1' }]);
    await attendre();
    expect(await caches.has(NOM_CACHE_DONNEES)).toBeTrue();
    viderCachesDonnees();
    await attendre();
    expect(await caches.has(NOM_CACHE_DONNEES)).toBeFalse();
  });
});
