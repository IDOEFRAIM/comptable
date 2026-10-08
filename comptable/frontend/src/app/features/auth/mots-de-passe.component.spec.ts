import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { ChangerMotDePasseComponent } from './changer-mot-de-passe.component';
import { ReinitialiserMotDePasseComponent } from './reinitialiser-mot-de-passe.component';

function saisir(fixture: ComponentFixture<unknown>, selecteur: string, valeur: string): void {
  const champ = (fixture.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

describe('ChangerMotDePasseComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let fixture: ComponentFixture<ChangerMotDePasseComponent>;
  let router: Router;
  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  function ouvrir(obligatoire: boolean, roles: string[] = ['ROLE_COLLABORATEUR']): void {
    TestBed.configureTestingModule({
      imports: [ChangerMotDePasseComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }, { provide: TokenStorageService, useValue: { doitChangerMotDePasse: () => obligatoire, getRoles: () => roles } }],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture = TestBed.createComponent(ChangerMotDePasseComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['changerMotDePasse']);
  });

  it('mot de passe temporaire : le motif est expliqué et le bouton reste inactif tant que la saisie est invalide', () => {
    ouvrir(true);
    expect(el('[data-champ="motif-obligatoire"]')!.innerText).toContain('temporaire');
    expect((el('[data-action="changer-mot-de-passe"]') as HTMLButtonElement).disabled).toBeTrue();
    saisir(fixture, '[data-champ="actuel"]', 'Temporaire-1');
    saisir(fixture, '[data-champ="nouveau"]', 'court');
    saisir(fixture, '[data-champ="confirmation"]', 'court');
    fixture.componentInstance.form.markAllAsTouched();
    fixture.detectChanges();
    expect(el('[data-champ="erreur-longueur"]')!.innerText).toContain('12 caractères');
    expect((el('[data-action="changer-mot-de-passe"]') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('une confirmation différente est signalée et empêche l’envoi', () => {
    ouvrir(false);
    expect(el('[data-champ="motif-obligatoire"]')).toBeNull();
    saisir(fixture, '[data-champ="actuel"]', 'Actuel-12345678');
    saisir(fixture, '[data-champ="nouveau"]', 'Nouveau-Secret-2026');
    saisir(fixture, '[data-champ="confirmation"]', 'Autre-Secret-2026!');
    fixture.componentInstance.form.markAllAsTouched();
    fixture.detectChanges();
    expect(el('[data-champ="erreur-confirmation"]')).not.toBeNull();
    expect((el('[data-action="changer-mot-de-passe"]') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('succès : le nouveau jeton remplace l’ancien et l’utilisateur du cabinet arrive sur son espace', () => {
    ouvrir(true);
    auth.changerMotDePasse.and.returnValue(of({ token: 't', utilisateurId: 'u', tenantId: 'c', email: 'a@b.cd', roles: ['ROLE_COLLABORATEUR'], mustChangePassword: false }));
    saisir(fixture, '[data-champ="actuel"]', 'Temporaire-1');
    saisir(fixture, '[data-champ="nouveau"]', 'Nouveau-Secret-2026');
    saisir(fixture, '[data-champ="confirmation"]', 'Nouveau-Secret-2026');
    (el('[data-action="changer-mot-de-passe"]') as HTMLButtonElement).click();
    expect(auth.changerMotDePasse).toHaveBeenCalledWith('Temporaire-1', 'Nouveau-Secret-2026');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/cabinet');
  });

  it('refus du serveur (politique, mot de passe actuel faux) : message affiché, pas de redirection', () => {
    ouvrir(true);
    auth.changerMotDePasse.and.returnValue(throwError(() => ({ error: { message: 'Le mot de passe actuel est incorrect' } })));
    saisir(fixture, '[data-champ="actuel"]', 'Mauvais-1234567');
    saisir(fixture, '[data-champ="nouveau"]', 'Nouveau-Secret-2026');
    saisir(fixture, '[data-champ="confirmation"]', 'Nouveau-Secret-2026');
    (el('[data-action="changer-mot-de-passe"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-champ="erreur"]')!.innerText).toContain('actuel est incorrect');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});

describe('ReinitialiserMotDePasseComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let fixture: ComponentFixture<ReinitialiserMotDePasseComponent>;
  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  function ouvrir(jeton: string | null): void {
    TestBed.configureTestingModule({
      imports: [ReinitialiserMotDePasseComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }, { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(jeton ? { jeton } : {}) } } }],
    });
    fixture = TestBed.createComponent(ReinitialiserMotDePasseComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['reinitialiserMotDePasse']);
  });

  it('lien sans jeton : message clair, aucun envoi possible', () => {
    ouvrir(null);
    expect(el('[data-champ="jeton-absent"]')!.innerText).toContain('incomplet');
    saisir(fixture, '[data-champ="nouveau"]', 'Nouveau-Secret-2026');
    saisir(fixture, '[data-champ="confirmation"]', 'Nouveau-Secret-2026');
    expect((el('[data-action="reinitialiser"]') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('succès : confirmation et lien vers la connexion', () => {
    ouvrir('jeton-recu');
    auth.reinitialiserMotDePasse.and.returnValue(of(undefined));
    saisir(fixture, '[data-champ="nouveau"]', 'Nouveau-Secret-2026');
    saisir(fixture, '[data-champ="confirmation"]', 'Nouveau-Secret-2026');
    (el('[data-action="reinitialiser"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(auth.reinitialiserMotDePasse).toHaveBeenCalledWith('jeton-recu', 'Nouveau-Secret-2026');
    expect(el('[data-champ="reinitialisation-reussie"]')!.innerText).toContain('Mot de passe modifié');
  });

  it('jeton expiré, déjà utilisé ou inconnu : même message générique du serveur', () => {
    ouvrir('jeton-perime');
    auth.reinitialiserMotDePasse.and.returnValue(throwError(() => ({ error: { message: 'Lien de réinitialisation invalide ou expiré' } })));
    saisir(fixture, '[data-champ="nouveau"]', 'Nouveau-Secret-2026');
    saisir(fixture, '[data-champ="confirmation"]', 'Nouveau-Secret-2026');
    (el('[data-action="reinitialiser"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-champ="erreur"]')!.innerText).toContain('invalide ou expiré');
    expect(el('[data-champ="reinitialisation-reussie"]')).toBeNull();
  });
});
