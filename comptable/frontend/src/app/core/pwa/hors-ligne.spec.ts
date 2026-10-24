import { HttpClient, HttpContext, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TokenStorageService } from '../auth/token-storage.service';
import { cacheLecturesInterceptor, routeMiseEnCache } from './cache-lectures.interceptor';
import { NOM_CACHE_DONNEES, viderCachesDonnees } from './caches-donnees';
import { FileAttenteService, REJEU_FILE_ATTENTE } from './file-attente.service';
import { fileAttenteStore } from './file-attente.store';
import { horsLigneInterceptor } from './hors-ligne.interceptor';
import { HorsLigneService, MESSAGE_HORS_LIGNE_ECRITURE } from './hors-ligne.service';

describe('Mode hors ligne (PWA)', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  const enLigne = signal(true);
  const file = {
    enfilerDocument: jasmine.createSpy('enfilerDocument').and.resolveTo(),
    enfilerEcriture: jasmine.createSpy('enfilerEcriture').and.resolveTo(),
  };

  beforeEach(() => {
    enLigne.set(true);
    file.enfilerDocument.calls.reset();
    file.enfilerEcriture.calls.reset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([horsLigneInterceptor])),
        provideHttpClientTesting(),
        { provide: HorsLigneService, useValue: { enLigne } },
        { provide: FileAttenteService, useValue: file },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  it('hors ligne, une écriture est refusée tout de suite, sans requête réseau, avec un message clair', () => {
    enLigne.set(false);
    let erreur: HttpErrorResponse | null = null;
    client.post('/api/entreprises/e1/ecritures/e2/valider', {}).subscribe({ error: (e) => (erreur = e) });
    http.expectNone('/api/entreprises/e1/ecritures/e2/valider');
    expect(erreur!.error.code).toBe('HORS_LIGNE');
    expect(erreur!.error.message).toBe(MESSAGE_HORS_LIGNE_ECRITURE);
  });

  it('hors ligne, une écriture en brouillon est mise en file (202) au lieu d’être refusée ou envoyée', async () => {
    enLigne.set(false);
    let statut = 0;
    client.post('/api/entreprises/e1/ecritures', { libelle: 'x', lignes: [] }, { observe: 'response' }).subscribe((r) => (statut = r.status));
    await Promise.resolve();
    http.expectNone('/api/entreprises/e1/ecritures');
    expect(file.enfilerEcriture).toHaveBeenCalledWith('e1', { libelle: 'x', lignes: [] });
    expect(statut).toBe(202);
  });

  it('hors ligne, le dépôt d’une pièce est mis en file avec son fichier et son type', async () => {
    enLigne.set(false);
    const formData = new FormData();
    formData.append('typeDocument', 'FACTURE_ACHAT');
    formData.append('fichier', new File(['abc'], 'facture.pdf', { type: 'application/pdf' }));
    let statut = 0;
    client.post('/api/entreprises/e1/documents', formData, { observe: 'response' }).subscribe((r) => (statut = r.status));
    await Promise.resolve();
    http.expectNone('/api/entreprises/e1/documents');
    expect(file.enfilerDocument).toHaveBeenCalledWith('e1', 'FACTURE_ACHAT', jasmine.any(File));
    expect(statut).toBe(202);
  });

  it('une requête rejouée par la synchronisation n’est jamais remise en file', () => {
    enLigne.set(false);
    let code = '';
    client.post('/api/entreprises/e1/ecritures', { libelle: 'x' }, { context: new HttpContext().set(REJEU_FILE_ATTENTE, true) }).subscribe({ error: (e) => (code = e.error.code) });
    expect(code).toBe('HORS_LIGNE');
    expect(file.enfilerEcriture).not.toHaveBeenCalled();
  });

  it('hors ligne, validation, clôture, import et facturation restent refusés (jamais mis en file)', () => {
    enLigne.set(false);
    for (const url of ['/api/entreprises/e1/ecritures/e2/valider', '/api/entreprises/e1/exercices/x/cloturer', '/api/entreprises/e1/banque/imports', '/api/entreprises/e1/factures/f1/valider']) {
      let code = '';
      client.post(url, new FormData()).subscribe({ error: (e) => (code = e.error.code) });
      expect(code).withContext(url).toBe('HORS_LIGNE');
    }
  });

  it('toutes les méthodes d’écriture autres que la création sont refusées hors ligne', () => {
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

describe('Cache de lectures hors ligne', () => {
  it('reconnaît les pages de lecture d’un dossier, y compris celles téléchargées pour le hors ligne', () => {
    for (const url of ['/api/entreprises', '/api/entreprises/e1/tiers', '/api/entreprises/e1/plan-comptable', '/api/entreprises/e1/factures', '/api/entreprises/e1/ecritures/e2']) {
      expect(routeMiseEnCache(url)).withContext(url).toBeTrue();
    }
    expect(routeMiseEnCache('/api/auth/login')).toBeFalse();
    expect(routeMiseEnCache('/api/entreprises/e1/documents/d1/fichier')).toBeFalse();
  });

  it('le cache reste lisible sans réseau', async () => {
    const cache = await caches.open(NOM_CACHE_DONNEES);
    expect(cache).toBeTruthy();
    expect(typeof cacheLecturesInterceptor).toBe('function');
    await caches.delete(NOM_CACHE_DONNEES);
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

describe('FileAttenteService', () => {
  let http: HttpTestingController;
  let service: FileAttenteService;
  const enLigne = signal(true);
  const ecriture = {
    libelle: 'Loyer',
    lignes: [
      { debit: 1000, credit: 0 },
      { debit: 0, credit: 1000 },
    ],
  };
  const attendre = () => new Promise((resolve) => setTimeout(resolve, 40));

  beforeEach(async () => {
    enLigne.set(false); // le constructeur tente une synchronisation : hors ligne elle ne fait rien
    for (const e of await fileAttenteStore.lister()) {
      await fileAttenteStore.supprimer(e.id);
    }
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([horsLigneInterceptor])),
        provideHttpClientTesting(),
        { provide: HorsLigneService, useValue: { enLigne } },
        { provide: TokenStorageService, useValue: { getEmail: () => 'a@cabinet.test', getToken: () => 'jwt' } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(FileAttenteService);
    await service.charger();
  });

  it('envoie les éléments en attente, dans l’ordre, au retour de la connexion, puis vide la file', async () => {
    await service.enfilerEcriture('e1', ecriture);
    await service.enfilerDocument('e1', 'RECU', new File(['x'], 'recu.png', { type: 'image/png' }));
    expect(service.enAttente().length).toBe(2);
    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/ecritures').flush({ id: 'ec1' });
    await attendre();
    const doc = http.expectOne('/api/entreprises/e1/documents');
    expect((doc.request.body as FormData).get('typeDocument')).toBe('RECU');
    doc.flush({ id: 'd1' });
    await fin;
    expect(service.elements().length).toBe(0);
    expect(await fileAttenteStore.lister()).toEqual([]);
    expect(service.rapport()).toContain('2 élément(s) envoyé(s)');
  });

  it('un refus du serveur (ex. exercice clôturé) marque l’élément « refusé » avec son message, sans bloquer les suivants', async () => {
    await service.enfilerEcriture('e1', ecriture);
    await service.enfilerEcriture('e1', { ...ecriture, libelle: 'Autre' });
    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/ecritures').flush({ code: 'EXERCICE_CLOTURE', message: 'Exercice clôturé.' }, { status: 422, statusText: 'Unprocessable' });
    await attendre();
    http.expectOne('/api/entreprises/e1/ecritures').flush({ id: 'ec2' });
    await fin;
    expect(service.refuses().length).toBe(1);
    expect(service.refuses()[0].erreur).toBe('Exercice clôturé.');
    expect(service.enAttente().length).toBe(0);
  });

  it('si le réseau retombe pendant l’envoi, l’élément reste en attente et l’envoi s’arrête', async () => {
    await service.enfilerEcriture('e1', ecriture);
    await service.enfilerEcriture('e1', ecriture);
    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/ecritures').error(new ProgressEvent('error'), { status: 0 });
    await fin;
    http.expectNone('/api/entreprises/e1/ecritures');
    expect(service.enAttente().length).toBe(2);
    expect(service.rapport()).toContain('reprendra automatiquement');
  });

  it('ne fait rien hors ligne', async () => {
    await service.enfilerEcriture('e1', ecriture);
    await service.synchroniser();
    http.expectNone('/api/entreprises/e1/ecritures');
    expect(service.enAttente().length).toBe(1);
  });

  it('la connexion d’un autre utilisateur supprime la file de l’utilisateur précédent, pas celle du même utilisateur', async () => {
    await service.enfilerEcriture('e1', ecriture);
    await fileAttenteStore.purgerSauf('a@cabinet.test');
    expect((await fileAttenteStore.lister()).length).toBe(1);
    await fileAttenteStore.purgerSauf('autre@cabinet.test');
    expect((await fileAttenteStore.lister()).length).toBe(0);
  });
});
