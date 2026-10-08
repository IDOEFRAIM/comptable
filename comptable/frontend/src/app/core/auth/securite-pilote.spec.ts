import { HttpClient, HttpErrorResponse, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { messageParDefaut, normaliserErreur } from '../http/messages-erreur';
import { authGuard } from './auth.guard';
import { errorInterceptor } from './error.interceptor';
import { TokenStorageService } from './token-storage.service';

/** Jeton factice (la signature n'est jamais vérifiée côté navigateur) portant les revendications données. */
export function jeton(payload: Record<string, unknown>): string {
  const encode = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'HS256' })}.${encode(payload)}.signature`;
}

describe('Messages d’erreur HTTP (jamais de JSON brut ni de trace)', () => {
  it('une panne réseau (statut 0) devient un message en français', () => {
    const e = normaliserErreur(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }));
    expect(e.error.message).toContain('injoignable');
  });

  it('une réponse HTML d’un proxy (502) ne montre jamais le HTML : message générique + référence de support', () => {
    const e = normaliserErreur(new HttpErrorResponse({ status: 502, error: '<html>Bad gateway</html>', headers: new HttpHeaders({ 'X-Request-Id': 'req-abcdef0123456789' }) }));
    expect(e.error.message).toContain('erreur est survenue côté serveur');
    expect(e.error.message).toContain('req-abcdef0123456789');
    expect(e.error.message).not.toContain('<html>');
  });

  it('une erreur 5xx du serveur affiche un message générique et la référence de corrélation', () => {
    const e = normaliserErreur(new HttpErrorResponse({ status: 500, error: { code: 'INTERNAL_ERROR', message: 'peu importe', correlationId: 'abc-0123456789abcdef' } }));
    expect(e.error.message).toContain('(réf. abc-0123456789abcdef)');
    expect(e.error.message).not.toContain('peu importe');
    expect(e.error.code).toBe('INTERNAL_ERROR');
  });

  it('un message métier du serveur (4xx) est conservé tel quel', () => {
    expect(normaliserErreur(new HttpErrorResponse({ status: 422, error: { code: 'UNPROCESSABLE_ENTITY', message: 'La part déductible est un pourcentage' } })).error.message).toBe('La part déductible est un pourcentage');
    expect(normaliserErreur(new HttpErrorResponse({ status: 409, error: { message: 'Cette période a été modifiée par un autre utilisateur' } })).error.message).toContain('autre utilisateur');
  });

  it('chaque statut courant a un message par défaut lisible', () => {
    for (const s of [0, 400, 401, 403, 404, 409, 413, 422, 429, 500, 503]) {
      expect(messageParDefaut(s).length).toBeGreaterThan(15);
    }
    expect(messageParDefaut(429)).toContain('Trop de tentatives');
  });
});

describe('errorInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let tokens: jasmine.SpyObj<TokenStorageService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    tokens = jasmine.createSpyObj<TokenStorageService>('TokenStorageService', ['clear', 'getToken']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([errorInterceptor])), provideHttpClientTesting(), { provide: TokenStorageService, useValue: tokens }, { provide: Router, useValue: router }],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  it('401 sur l’API : session nettoyée et retour à la connexion', () => {
    client.get('/api/entreprises').subscribe({ error: () => undefined });
    http.expectOne('/api/entreprises').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(tokens.clear).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('401 sur la connexion elle-même : la page de connexion gère son erreur, aucune redirection', () => {
    client.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    http.expectOne('/api/auth/login').flush({ message: 'Identifiants invalides' }, { status: 401, statusText: 'Unauthorized' });
    expect(tokens.clear).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('403 PASSWORD_CHANGE_REQUIRED : redirection vers le changement de mot de passe', () => {
    client.get('/api/cabinet/equipe').subscribe({ error: () => undefined });
    http.expectOne('/api/cabinet/equipe').flush({ code: 'PASSWORD_CHANGE_REQUIRED', message: 'x' }, { status: 403, statusText: 'Forbidden' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/changer-mot-de-passe');
  });

  it('une erreur 500 arrive normalisée dans les composants (message lisible, jamais le corps brut)', () => {
    let recu: HttpErrorResponse | null = null;
    client.get('/api/x').subscribe({ error: (e) => (recu = e) });
    http.expectOne('/api/x').flush('<html>boom</html>', { status: 500, statusText: 'Server Error' });
    expect(recu!.error.message).toContain('erreur est survenue côté serveur');
    expect(recu!.error.message).not.toContain('<html>');
  });

  it('un 429 garde le message du serveur (trop de tentatives)', () => {
    let recu: HttpErrorResponse | null = null;
    client.post('/api/auth/login', {}).subscribe({ error: (e) => (recu = e) });
    http.expectOne('/api/auth/login').flush({ code: 'TOO_MANY_REQUESTS', message: 'Trop de tentatives. Réessayez dans quelques minutes.' }, { status: 429, statusText: 'Too Many Requests' });
    expect(recu!.error.message).toContain('Trop de tentatives');
  });
});

describe('authGuard (mot de passe temporaire)', () => {
  function garde(token: string | null, doitChanger: boolean): boolean | UrlTree {
    TestBed.configureTestingModule({
      providers: [
        { provide: TokenStorageService, useValue: { getToken: () => token, doitChangerMotDePasse: () => doitChanger } },
        { provide: Router, useValue: { parseUrl: (u: string) => u as unknown as UrlTree } },
      ],
    });
    return TestBed.runInInjectionContext(() => authGuard({} as never, {} as never)) as boolean | UrlTree;
  }

  it('sans jeton : retour à la connexion', () => {
    expect(garde(null, false)).toBe('/login' as unknown as UrlTree);
  });

  it('jeton de mot de passe temporaire : aucune page de travail, changement de mot de passe d’abord', () => {
    expect(garde(jeton({ mcp: true }), true)).toBe('/changer-mot-de-passe' as unknown as UrlTree);
  });

  it('jeton normal : accès', () => {
    expect(garde(jeton({}), false)).toBeTrue();
  });
});

describe('TokenStorageService.doitChangerMotDePasse', () => {
  it('lit la revendication mcp du jeton', () => {
    const service = TestBed.inject(TokenStorageService);
    service.setToken(jeton({ mcp: true, roles: ['ROLE_COLLABORATEUR'] }));
    expect(service.doitChangerMotDePasse()).toBeTrue();
    service.setToken(jeton({ roles: ['ROLE_COLLABORATEUR'] }));
    expect(service.doitChangerMotDePasse()).toBeFalse();
    service.clear();
    expect(service.doitChangerMotDePasse()).toBeFalse();
  });
});
