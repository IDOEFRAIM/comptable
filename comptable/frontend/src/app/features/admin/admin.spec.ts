import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { accueilPourRoles, adminGuard } from '../../core/auth/admin.guard';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { AdminService, CabinetAdmin, UtilisateurAdmin } from './admin.service';
import { AdminCabinetsComponent } from './admin-cabinets.component';
import { AdminUtilisateursComponent } from './admin-utilisateurs.component';
import { AdminAuditComponent } from './admin-audit.component';
import { AdminIaComponent } from './admin-ia.component';
import { AdminSanteComponent } from './admin-sante.component';

const utilisateur = (extra: Partial<UtilisateurAdmin> = {}): UtilisateurAdmin => ({
  id: 'u1', email: 'expert@cabinet.test', nom: 'Kone', prenom: 'Awa', statut: 'ACTIF', cabinetId: 'c1', cabinet: 'Cabinet Kone', roles: ['EXPERT_COMPTABLE'], creeLe: '2026-11-01T10:00:00Z',
  derniereConnexion: null, verrouilleJusqua: null, verrouille: false, echecsConsecutifs: 0, motDePasseAChanger: false, ...extra,
});
const page = <T>(elements: T[]) => ({ elements, total: elements.length, page: 0, taille: 25 });

describe('accès au mode administrateur', () => {
  it('chaque rôle arrive au bon endroit après la connexion', () => {
    expect(accueilPourRoles(['ROLE_SUPER_ADMIN'])).toBe('/admin');
    expect(accueilPourRoles(['ROLE_EXPERT_COMPTABLE'])).toBe('/cabinet');
    expect(accueilPourRoles(['ROLE_COLLABORATEUR'])).toBe('/cabinet');
    expect(accueilPourRoles(['ROLE_CLIENT'])).toBe('/entreprises');
  });

  function garde(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: TokenStorageService, useValue: { getRoles: () => roles } }] });
    return TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never));
  }

  it('le garde laisse entrer l’administrateur', () => {
    expect(garde(['ROLE_SUPER_ADMIN'])).toBeTrue();
  });

  it('le garde renvoie un membre de cabinet vers son espace', () => {
    const refus = garde(['ROLE_EXPERT_COMPTABLE']);
    expect(TestBed.inject(Router).serializeUrl(refus as never)).toBe('/cabinet');
  });
});

describe('AdminService', () => {
  it('n’envoie que les filtres renseignés', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(AdminService).utilisateurs({ q: 'kone', statut: '', page: 0, taille: 25 }).subscribe();
    const r = http.expectOne((req) => req.url === '/api/admin/utilisateurs');
    expect(r.request.params.get('q')).toBe('kone');
    expect(r.request.params.has('statut')).toBeFalse();
    expect(r.request.params.get('page')).toBe('0');
    r.flush(page([]));
  });
});

describe('AdminUtilisateursComponent', () => {
  let http: HttpTestingController;

  function ouvrir(elements = [utilisateur()]) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AdminUtilisateursComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/utilisateurs').flush(page(elements));
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: ReturnType<typeof ouvrir>, selecteur: string) => f.nativeElement.querySelector(selecteur) as HTMLElement | null;

  /** Ouvre le panneau d'une action et saisit le motif (le champ se branche au modèle de façon asynchrone : on attend qu'il soit prêt). */
  async function saisir(f: ReturnType<typeof ouvrir>, bouton: string, texte: string) {
    el(f, bouton)!.click();
    f.detectChanges();
    await f.whenStable();
    const motif = el(f, '[data-zone="motif-action"]') as HTMLTextAreaElement;
    motif.value = texte;
    motif.dispatchEvent(new Event('input'));
    f.detectChanges();
  }

  it('affiche les comptes avec leur cabinet, leur rôle et leur état', () => {
    const f = ouvrir([utilisateur(), utilisateur({ id: 'u2', email: 'bloque@cabinet.test', verrouille: true, verrouilleJusqua: '2026-11-16T23:59:00Z' }), utilisateur({ id: 'u3', email: 'susp@cabinet.test', statut: 'INACTIF' })]);
    const texte = el(f, '[data-zone="table-utilisateurs"]')!.textContent!;
    expect(texte).toContain('Cabinet Kone');
    expect(texte).toContain('Expert-comptable');
    expect(texte).toContain('Verrouillé');
    expect(texte).toContain('Suspendu');
    expect(texte).toContain('Jamais');
  });

  it('un compte administrateur n’offre aucune action', () => {
    const f = ouvrir([utilisateur({ roles: ['SUPER_ADMIN'], cabinetId: null, cabinet: null })]);
    expect(el(f, '[data-action="suspendre"]')).toBeNull();
    expect(f.nativeElement.textContent).toContain('Compte administrateur');
  });

  it('une action exige un motif de 5 caractères, puis met la ligne à jour sans tout recharger', async () => {
    const f = ouvrir();
    await saisir(f, '[data-action="suspendre"]', 'abc');
    expect((el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).disabled).withContext('motif trop court').toBeTrue();
    await saisir(f, '[data-action="suspendre"]', 'Impayé');
    expect((el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).disabled).toBeFalse();

    (el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).click();
    const requete = http.expectOne('/api/admin/utilisateurs/u1/suspendre');
    expect(requete.request.body).toEqual({ motif: 'Impayé' });
    requete.flush(utilisateur({ statut: 'INACTIF' }));
    f.detectChanges();
    expect(el(f, '[data-zone="confirmation-action"]')).toBeNull();
    expect(el(f, '[data-action="reactiver"]')).not.toBeNull();
    http.expectNone((r) => r.url === '/api/admin/utilisateurs');
  });

  it('le mot de passe temporaire s’affiche une fois, avec sa mise en garde', async () => {
    const f = ouvrir();
    await saisir(f, '[data-action="reinitialiser"]', 'Mot de passe oublié');
    (el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).click();
    http.expectOne('/api/admin/utilisateurs/u1/reinitialiser-mot-de-passe').flush({ utilisateur: utilisateur({ motDePasseAChanger: true }), motDePasseTemporaire: 'Ab3dEfGh5jKmNp7q' });
    f.detectChanges();
    expect(el(f, '[data-zone="mot-de-passe"]')!.textContent).toContain('Ab3dEfGh5jKmNp7q');
    expect(el(f, '[data-zone="mot-de-passe-temporaire"]')!.textContent).toContain('plus jamais affiché');
    (el(f, '[data-action="fermer-mot-de-passe"]') as HTMLButtonElement).click();
    f.detectChanges();
    expect(el(f, '[data-zone="mot-de-passe"]')).toBeNull();
  });

  it('un refus du serveur s’affiche dans le panneau, sans fermer l’action', async () => {
    const f = ouvrir();
    await saisir(f, '[data-action="suspendre"]', 'Test de refus');
    (el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).click();
    http.expectOne('/api/admin/utilisateurs/u1/suspendre').flush({ message: 'Un compte administrateur ne se gère pas depuis cet écran' }, { status: 422, statusText: 'Unprocessable' });
    f.detectChanges();
    expect(el(f, '[data-zone="confirmation-action"]')!.textContent).toContain('ne se gère pas');
  });
});

describe('AdminAuditComponent', () => {
  it('affiche le journal avec des libellés lisibles, le motif et l’acteur', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(AdminAuditComponent);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/audit').flush(
      page([{ id: 1, date: '2026-11-16T10:00:00Z', action: 'ADMIN_UTILISATEUR_SUSPENDU', entite: 'UTILISATEUR', entiteId: 'u1', details: { motif: 'Impayé', cible: 'expert@cabinet.test' }, cabinetId: 'c1', cabinet: 'Cabinet Kone', acteurId: 'a1', acteur: 'admin@warimara.test' }]),
    );
    f.detectChanges();
    const texte = (f.nativeElement.querySelector('[data-zone="table-audit"]') as HTMLElement).textContent!;
    expect(texte).toContain('Compte suspendu');
    expect(texte).toContain('motif : Impayé');
    expect(texte).toContain('admin@warimara.test');
    expect(texte).toContain('Cabinet Kone');
  });
});

const cabinet = (extra: Partial<CabinetAdmin> = {}): CabinetAdmin => ({
  id: 'c1', raisonSociale: 'Cabinet Kone', slug: 'cabinet-kone', pays: 'BF', plan: 'ESSAI', statut: 'ACTIF', finEssai: '2026-10-01', essaiExpire: true, quotaUtilisateurs: 3, quotaDossiers: null,
  membresActifs: 3, utilisateursTotal: 5, dossiersActifs: 12, creeLe: '2026-09-01T10:00:00Z', derniereActivite: '2026-11-16T10:00:00Z', motifSuspension: null, suspenduLe: null, ...extra,
});

describe('AdminCabinetsComponent', () => {
  let http: HttpTestingController;

  function ouvrir(elements = [cabinet()]) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AdminCabinetsComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/cabinets').flush(page(elements));
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: ReturnType<typeof ouvrir>, selecteur: string) => f.nativeElement.querySelector(selecteur) as HTMLElement | null;
  async function ecrire(f: ReturnType<typeof ouvrir>, selecteur: string, texte: string) {
    await f.whenStable();
    const champ = el(f, selecteur) as HTMLInputElement | HTMLTextAreaElement;
    champ.value = texte;
    champ.dispatchEvent(new Event('input'));
    f.detectChanges();
  }

  it('montre l’abonnement, l’utilisation face aux limites, l’essai expiré et l’état', () => {
    const f = ouvrir([cabinet(), cabinet({ id: 'c2', raisonSociale: 'Cabinet Sow', statut: 'SUSPENDU', motifSuspension: 'Impayé', plan: 'STANDARD', essaiExpire: false, finEssai: null, quotaUtilisateurs: null })]);
    const texte = el(f, '[data-zone="table-cabinets"]')!.textContent!;
    expect(texte).toContain('3 / 3 membres');
    expect(texte).toContain('12 / ∞ dossiers');
    expect(texte).toContain('Essai expiré');
    expect(texte).toContain('Suspendu');
    expect(texte).toContain('Impayé');
    expect(el(f, '.limite')).not.toBeNull(); // la limite de membres est atteinte : mise en évidence
  });

  it('suspendre exige un motif, envoie la demande et met la ligne à jour', async () => {
    const f = ouvrir();
    el(f, '[data-action="suspendre-cabinet"]')!.click();
    f.detectChanges();
    await ecrire(f, '[data-zone="motif-action"]', 'abc');
    expect((el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).disabled).toBeTrue();
    await ecrire(f, '[data-zone="motif-action"]', 'Abonnement impayé');
    (el(f, '[data-action="confirmer-action"]') as HTMLButtonElement).click();
    const requete = http.expectOne('/api/admin/cabinets/c1/suspendre');
    expect(requete.request.body).toEqual({ motif: 'Abonnement impayé' });
    requete.flush(cabinet({ statut: 'SUSPENDU', motifSuspension: 'Abonnement impayé' }));
    f.detectChanges();
    expect(el(f, '[data-action="reactiver-cabinet"]')).not.toBeNull();
    expect(el(f, '[data-zone="confirmation-action"]')).toBeNull();
  });

  it('l’abonnement s’envoie avec des limites vides = null (pas de limite)', async () => {
    const f = ouvrir();
    el(f, '[data-action="abonnement"]')!.click();
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const quota = el(f, '[data-zone="champ-quota-utilisateurs"]') as HTMLInputElement;
    expect(quota.value).toBe('3');
    quota.value = '';
    quota.dispatchEvent(new Event('input'));
    await ecrire(f, '[data-zone="motif-action"]', 'Limite levée par accord');
    (el(f, '[data-action="confirmer-abonnement"]') as HTMLButtonElement).click();
    const requete = http.expectOne('/api/admin/cabinets/c1/abonnement');
    expect(requete.request.body).toEqual({ plan: 'ESSAI', finEssai: '2026-10-01', quotaUtilisateurs: null, quotaDossiers: null, motif: 'Limite levée par accord' });
    requete.flush(cabinet({ quotaUtilisateurs: null }));
  });

  it('un cabinet créé affiche son mot de passe temporaire une seule fois', async () => {
    const f = ouvrir([]);
    el(f, '[data-action="nouveau-cabinet"]')!.click();
    f.detectChanges();
    await ecrire(f, '[data-zone="champ-raison-sociale"]', 'Cabinet Nouveau');
    (el(f, '[data-action="confirmer-creation"]') as HTMLButtonElement).click();
    const requete = http.expectOne((r) => r.url === '/api/admin/cabinets' && r.method === 'POST');
    expect((requete.request.body as { raisonSociale: string }).raisonSociale).toBe('Cabinet Nouveau');
    requete.flush({ cabinet: cabinet({ raisonSociale: 'Cabinet Nouveau' }), emailExpert: 'expert@nouveau.test', motDePasseTemporaire: 'Zx9KmPq3VbNc7Rtw' });
    http.expectOne((r) => r.url === '/api/admin/cabinets' && r.method === 'GET').flush(page([cabinet({ raisonSociale: 'Cabinet Nouveau' })]));
    f.detectChanges();
    expect(el(f, '[data-zone="mot-de-passe"]')!.textContent).toContain('Zx9KmPq3VbNc7Rtw');
    expect(el(f, '[data-zone="cabinet-cree"]')!.textContent).toContain('une seule fois');
    (el(f, '[data-action="fermer-cabinet-cree"]') as HTMLButtonElement).click();
    f.detectChanges();
    expect(el(f, '[data-zone="mot-de-passe"]')).toBeNull();
  });

  it('un refus du serveur (ex. e-mail déjà utilisé) s’affiche dans le panneau', async () => {
    const f = ouvrir([]);
    el(f, '[data-action="nouveau-cabinet"]')!.click();
    f.detectChanges();
    await f.whenStable();
    (el(f, '[data-action="confirmer-creation"]') as HTMLButtonElement).click();
    http.expectOne((r) => r.url === '/api/admin/cabinets' && r.method === 'POST').flush({ message: 'Cette adresse e-mail est déjà utilisée' }, { status: 409, statusText: 'Conflict' });
    f.detectChanges();
    expect(el(f, '[data-zone="formulaire-creation"]')!.textContent).toContain('déjà utilisée');
  });
});

describe('AdminIaComponent', () => {
  let http: HttpTestingController;
  const usage = (cabinets: object[] = []) => ({ mois: '2026-11', appels: 12, echecs: 2, refusesPlafond: 3, appelsSansCabinet: 1, cabinets, operations: [{ operation: 'extraction', appels: 10, echecs: 2, latenceMoyenneMs: 850 }] });
  const ligne = (extra: object = {}) => ({ cabinetId: 'c1', cabinet: 'Cabinet Kone', appels: 10, echecs: 2, refusesPlafond: 3, latenceMoyenneMs: 850, plafond: 10, dernierAppel: '2026-11-17T10:00:00Z', ...extra });

  function ouvrir(cabinets: object[] = [ligne()]) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(AdminIaComponent);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/ia/usage').flush(usage(cabinets));
    f.detectChanges();
    return f;
  }
  const el = (f: ReturnType<typeof ouvrir>, selecteur: string) => f.nativeElement.querySelector(selecteur) as HTMLElement | null;

  it('dit clairement que le coût et les jetons ne sont pas mesurés, et montre les appels par cabinet', () => {
    const f = ouvrir();
    expect(f.nativeElement.textContent).toContain('ne sont pas mesurés');
    const texte = el(f, '[data-zone="table-usage-ia"]')!.textContent!;
    expect(texte).toContain('Cabinet Kone');
    expect(texte).toContain('850 ms');
    expect(el(f, '[data-zone="plafond-atteint"]')).not.toBeNull(); // 10 appels sur 10
    expect(el(f, '[data-zone="table-operations-ia"]')!.textContent).toContain('extraction');
  });

  it('modifier le plafond exige un motif puis envoie la demande', async () => {
    const f = ouvrir();
    el(f, '[data-action="plafond"]')!.click();
    f.detectChanges();
    await f.whenStable();
    const motif = el(f, '[data-zone="motif-action"]') as HTMLTextAreaElement;
    motif.value = 'abc';
    motif.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect((el(f, '[data-action="confirmer-plafond"]') as HTMLButtonElement).disabled).toBeTrue();
    motif.value = 'Offre de départ';
    motif.dispatchEvent(new Event('input'));
    f.detectChanges();
    (el(f, '[data-action="confirmer-plafond"]') as HTMLButtonElement).click();
    const requete = http.expectOne('/api/admin/cabinets/c1/plafond-ia');
    expect(requete.request.body.motif).toBe('Offre de départ');
    expect(requete.request.body.plafond).toBe(10);
    requete.flush(ligne());
    http.expectOne((r) => r.url === '/api/admin/ia/usage').flush(usage([ligne()]));
  });
});

describe('AdminSanteComponent', () => {
  const sante = (extra: object = {}) => ({
    mesureLe: '2026-11-17T10:00:00Z',
    services: [
      { nom: 'Serveur Warimara', etat: 'OK', latenceMs: null, detail: 'Répond.' },
      { nom: 'Microservice IA', etat: 'HORS_SERVICE', latenceMs: null, detail: 'Ne répond pas.' },
    ],
    sauvegarde: { etat: 'NON_SURVEILLEE', derniere: null, ageHeures: null, detail: 'Aucun dossier configuré.' },
    memoireUtiliseeMo: 300, memoireMaxMo: 900, disqueLibreMo: 1000, disqueTotalMo: 20000, versionBase: '72', demarreDepuisMinutes: 90, ...extra,
  });

  it('montre chaque service avec son état réel, une sauvegarde non surveillée comme une alerte, et la version de la base', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(AdminSanteComponent);
    f.detectChanges();
    http.expectOne('/api/admin/sante').flush(sante());
    f.detectChanges();
    const racine = f.nativeElement as HTMLElement;
    expect(racine.querySelector('[data-etat="HORS_SERVICE"]')!.textContent).toContain('Microservice IA');
    expect(racine.querySelector('[data-zone="sauvegarde"]')!.textContent).toContain('Non surveillée');
    expect(racine.querySelector('[data-zone="sauvegarde"]')!.className).toContain('alerte');
    expect(racine.querySelector('[data-zone="ressources"]')!.textContent).toContain('V72');
    expect(racine.querySelector('[data-zone="ressources"]')!.textContent).toContain('1 h');
  });
});
