import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { authGuard } from '../auth/auth.guard';
import { CodePinService, MAX_ESSAIS_PIN } from '../auth/code-pin.service';
import { FENETRE_HORS_LIGNE_MS, TokenStorageService } from '../auth/token-storage.service';
import { DossierHorsLigneService } from './dossier-hors-ligne.service';
import { HorsLigneService } from './hors-ligne.service';
import { FRAICHEUR_MS, MAX_DOSSIERS_AUTO, SynchronisationAutoService } from './synchronisation-auto.service';

function jeton(payload: object): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}

function nettoyer(): void {
  localStorage.clear();
  sessionStorage.clear();
}

describe('Session résiliente (expiration sans perte du travail hors ligne)', () => {
  beforeEach(nettoyer);
  afterEach(nettoyer);

  it('expirer() garde le jeton et les données : seule une marque est posée', () => {
    const tokens = TestBed.inject(TokenStorageService);
    tokens.setToken(jeton({ email: 'a@cabinet.test' }));
    expect(tokens.sessionExpiree()).toBeFalse();
    tokens.expirer();
    expect(tokens.getToken()).not.toBeNull();
    expect(tokens.sessionExpiree()).toBeTrue();
    expect(tokens.peutContinuerHorsLigne()).toBeTrue();
  });

  it('au-delà de 7 jours, l’accès hors ligne est refusé', () => {
    const tokens = TestBed.inject(TokenStorageService);
    tokens.setToken(jeton({ email: 'a@cabinet.test' }));
    tokens.expirer();
    localStorage.setItem('comptable.session-expiree', String(Date.now() - FENETRE_HORS_LIGNE_MS - 1000));
    expect(tokens.peutContinuerHorsLigne()).toBeFalse();
  });

  it('une nouvelle connexion lève la marque d’expiration', () => {
    const tokens = TestBed.inject(TokenStorageService);
    tokens.setToken(jeton({ email: 'a@cabinet.test' }));
    tokens.expirer();
    tokens.setToken(jeton({ email: 'a@cabinet.test' }));
    expect(tokens.sessionExpiree()).toBeFalse();
  });

  it('la déconnexion volontaire efface tout (jeton, marque, dernier utilisateur)', () => {
    const tokens = TestBed.inject(TokenStorageService);
    tokens.setToken(jeton({ email: 'a@cabinet.test' }));
    tokens.expirer();
    tokens.clear();
    expect(tokens.getToken()).toBeNull();
    expect(tokens.sessionExpiree()).toBeFalse();
    expect(localStorage.getItem('comptable.dernier-utilisateur')).toBeNull();
  });
});

describe('authGuard avec session expirée', () => {
  beforeEach(nettoyer);
  afterEach(nettoyer);

  const enLigne = signal(true);

  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { parseUrl: (u: string) => u as unknown as UrlTree } },
        { provide: HorsLigneService, useValue: { enLigne } },
      ],
    }),
  );

  function garde(horsLigne: boolean): boolean | UrlTree {
    enLigne.set(!horsLigne);
    return TestBed.runInInjectionContext(() => authGuard({} as never, {} as never)) as boolean | UrlTree;
  }

  function sessionExpiree(): void {
    const tokens = TestBed.inject(TokenStorageService);
    tokens.setToken(jeton({ email: 'a@cabinet.test', roles: ['ROLE_COLLABORATEUR'] }));
    tokens.expirer();
  }

  it('avec Internet : reconnexion obligatoire', () => {
    sessionExpiree();
    expect(garde(false)).toBe('/login' as unknown as UrlTree);
  });

  it('sans Internet, sans code PIN défini : accès conservé', () => {
    sessionExpiree();
    expect(garde(true)).toBeTrue();
  });

  it('sans Internet, avec un code PIN non saisi : retour à la connexion', async () => {
    sessionExpiree();
    await TestBed.inject(CodePinService).definir('1234');
    expect(garde(true)).toBe('/login' as unknown as UrlTree);
  });

  it('sans Internet, avec le code PIN saisi : accès', async () => {
    sessionExpiree();
    const pin = TestBed.inject(CodePinService);
    await pin.definir('1234');
    expect(await pin.verifier('1234')).toBe('ok');
    expect(garde(true)).toBeTrue();
  });
});

describe('Code PIN de l’appareil', () => {
  beforeEach(() => {
    nettoyer();
    TestBed.inject(TokenStorageService).setToken(jeton({ email: 'a@cabinet.test' }));
  });
  afterEach(nettoyer);

  it('refuse un code qui n’a pas 4 à 8 chiffres', async () => {
    await expectAsync(TestBed.inject(CodePinService).definir('12')).toBeRejected();
    await expectAsync(TestBed.inject(CodePinService).definir('abcd')).toBeRejected();
  });

  it('ne garde jamais le code en clair', async () => {
    await TestBed.inject(CodePinService).definir('4821');
    expect(JSON.stringify({ ...localStorage })).not.toContain('4821');
  });

  it('code correct : ok ; code faux : décompte des essais puis blocage', async () => {
    const pin = TestBed.inject(CodePinService);
    await pin.definir('4821');
    expect(await pin.verifier('0000')).toBe('incorrect');
    expect(pin.essaisRestants()).toBe(MAX_ESSAIS_PIN - 1);
    for (let i = 0; i < MAX_ESSAIS_PIN - 2; i++) {
      await pin.verifier('0000');
    }
    expect(await pin.verifier('0000')).toBe('bloque');
    expect(await pin.verifier('4821')).toBe('bloque');
  });

  it('un code correct remet le compteur à zéro', async () => {
    const pin = TestBed.inject(CodePinService);
    await pin.definir('4821');
    await pin.verifier('0000');
    expect(await pin.verifier('4821')).toBe('ok');
    expect(pin.essaisRestants()).toBe(MAX_ESSAIS_PIN);
  });
});

describe('Mise à jour automatique des dossiers récents', () => {
  const enLigne = signal(true);
  const dates = new Map<string, number>();
  const telecharger = jasmine.createSpy('telecharger');
  const enCours = signal(false);

  function service(): SynchronisationAutoService {
    TestBed.configureTestingModule({
      providers: [
        { provide: HorsLigneService, useValue: { enLigne } },
        { provide: DossierHorsLigneService, useValue: { enCours, derniereDate: (id: string) => dates.get(id) ?? null, telecharger } },
      ],
    });
    TestBed.inject(TokenStorageService).setToken(jeton({ email: 'a@cabinet.test' }));
    return TestBed.inject(SynchronisationAutoService);
  }

  beforeEach(() => {
    nettoyer();
    enLigne.set(true);
    enCours.set(false);
    dates.clear();
    telecharger.calls.reset();
    telecharger.and.callFake(async (id: string) => {
      dates.set(id, Date.now());
      return { reussies: 1, echecs: 0, date: Date.now() };
    });
  });
  afterEach(nettoyer);

  it('un dossier jamais téléchargé est téléchargé à l’ouverture', async () => {
    const s = service();
    s.noterOuverture('d1');
    await s.synchroniser();
    expect(telecharger).toHaveBeenCalledWith('d1');
  });

  it('un dossier encore frais n’est pas retéléchargé (économie de data)', async () => {
    const s = service();
    dates.set('d1', Date.now() - FRAICHEUR_MS / 2);
    s.noterOuverture('d1');
    await s.synchroniser();
    expect(telecharger).not.toHaveBeenCalled();
  });

  it('un dossier périmé est retéléchargé', async () => {
    const s = service();
    dates.set('d1', Date.now() - FRAICHEUR_MS - 1000);
    s.noterOuverture('d1');
    await s.synchroniser();
    expect(telecharger).toHaveBeenCalledWith('d1');
  });

  it('hors ligne : rien n’est tenté', async () => {
    const s = service();
    enLigne.set(false);
    s.noterOuverture('d1');
    await s.synchroniser();
    expect(telecharger).not.toHaveBeenCalled();
  });

  it('session expirée : rien n’est tenté', async () => {
    const s = service();
    TestBed.inject(TokenStorageService).expirer();
    s.noterOuverture('d1');
    await s.synchroniser();
    expect(telecharger).not.toHaveBeenCalled();
  });

  it('ne garde que les dossiers les plus récents', () => {
    const s = service();
    enLigne.set(false);
    for (const id of ['a', 'b', 'c', 'd', 'e']) {
      s.noterOuverture(id);
    }
    expect(s.recents()).toEqual(['e', 'd', 'c'].slice(0, MAX_DOSSIERS_AUTO));
  });

  it('la liste des dossiers récents est propre à chaque utilisateur', () => {
    const s = service();
    enLigne.set(false);
    s.noterOuverture('d1');
    TestBed.inject(TokenStorageService).setToken(jeton({ email: 'autre@cabinet.test' }));
    expect(s.recents()).toEqual([]);
  });
});
