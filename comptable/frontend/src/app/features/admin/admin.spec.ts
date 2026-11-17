import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { accueilPourRoles, adminGuard } from '../../core/auth/admin.guard';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { AdminService, UtilisateurAdmin } from './admin.service';
import { AdminUtilisateursComponent } from './admin-utilisateurs.component';
import { AdminAuditComponent } from './admin-audit.component';

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
