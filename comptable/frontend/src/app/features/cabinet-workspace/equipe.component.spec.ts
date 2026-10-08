import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { Membre } from './cabinet-workspace.model';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { EquipeComponent } from './equipe.component';

function membre(surcharge: Partial<Membre> = {}): Membre {
  return { id: 'm-1', nom: 'Kone', prenom: 'Fatou', email: 'fatou@cabinet.ci', role: 'COLLABORATEUR', statut: 'ACTIF', dossiersAffectes: 3, derniereActivite: new Date(Date.now() - 2 * 3600_000).toISOString(), ...surcharge };
}

const EXPERT = membre({ id: 'm-0', nom: 'Diallo', prenom: 'Moussa', email: 'expert@cabinet.ci', role: 'EXPERT_COMPTABLE', dossiersAffectes: 0 });

describe('EquipeComponent (équipe du cabinet)', () => {
  let service: jasmine.SpyObj<CabinetWorkspaceService>;
  let fixture: ComponentFixture<EquipeComponent>;

  function ouvrir(roles: string[], liste: Membre[] = [EXPERT, membre()]): void {
    service.equipe.and.returnValue(of(liste));
    TestBed.configureTestingModule({
      imports: [EquipeComponent],
      providers: [
        { provide: CabinetWorkspaceService, useValue: service },
        { provide: TokenStorageService, useValue: { getRoles: () => roles, getEmail: () => 'expert@cabinet.ci' } },
      ],
    });
    fixture = TestBed.createComponent(EquipeComponent);
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);
  async function ouvrirFormulaire(): Promise<void> {
    (el('[data-action="ajouter-membre"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();                                      // les champs ngModel d'un formulaire s'enregistrent de façon asynchrone
    fixture.detectChanges();
  }

  function saisir(sel: string, valeur: string): void {
    const champ = el(sel) as HTMLInputElement | HTMLSelectElement;
    champ.value = valeur;
    champ.dispatchEvent(new Event(champ.tagName === 'SELECT' ? 'change' : 'input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['equipe', 'ajouterMembre', 'modifierMembre', 'reinitialiserMotDePasse']);
  });

  it('liste les membres : nom, e-mail, rôle, statut, dossiers affectés, dernière activité', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    expect(el('[data-champ="nb-membres"]')!.innerText).toBe('2 membres');
    const t = el('[data-membre="fatou@cabinet.ci"]')!.innerText;
    for (const attendu of ['Fatou Kone', 'fatou@cabinet.ci', 'Actif', '3', 'il y a 2 h']) {
      expect(t).toContain(attendu);
    }
    expect(el('[data-membre="expert@cabinet.ci"]')!.innerText).toContain('(vous)');
  });

  it('expert : bouton Ajouter, changement de rôle et désactivation sur les autres membres, jamais sur soi', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    expect(el('[data-action="ajouter-membre"]')).not.toBeNull();
    expect(el('[data-membre="fatou@cabinet.ci"] [data-action="changer-role"]')).not.toBeNull();
    expect(el('[data-membre="fatou@cabinet.ci"] [data-action="desactiver-membre"]')).not.toBeNull();
    expect(el('[data-membre="expert@cabinet.ci"] [data-action="changer-role"]')).toBeNull();
    expect(el('[data-membre="expert@cabinet.ci"] [data-action="desactiver-membre"]')).toBeNull();
  });

  it('collaborateur : lecture seule, aucune action de mutation', () => {
    ouvrir(['ROLE_COLLABORATEUR']);
    expect(el('[data-membre="fatou@cabinet.ci"]')).not.toBeNull();
    expect(el('[data-action="ajouter-membre"]')).toBeNull();
    expect(el('[data-action="changer-role"]')).toBeNull();
    expect(el('[data-action="desactiver-membre"]')).toBeNull();
  });

  it('formulaire : champs obligatoires et e-mail valide avant d\'activer Ajouter', async () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    await ouvrirFormulaire();
    const bouton = (): HTMLButtonElement => el('[data-action="enregistrer-membre"]') as HTMLButtonElement;
    expect(bouton().disabled).toBeTrue();
    saisir('[data-champ="membre-prenom"]', 'Awa');
    saisir('[data-champ="membre-nom"]', 'Traore');
    saisir('[data-champ="membre-email"]', 'pas-un-mail');
    expect(el('[data-champ="erreur-email"]')).not.toBeNull();
    expect(bouton().disabled).toBeTrue();
    saisir('[data-champ="membre-email"]', 'awa@cabinet.ci');
    expect(el('[data-champ="erreur-email"]')).toBeNull();
    expect(bouton().disabled).toBeFalse();
  });

  it('ajout : requête, mot de passe temporaire affiché une fois, liste rechargée', async () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    await ouvrirFormulaire();
    saisir('[data-champ="membre-prenom"]', 'Awa');
    saisir('[data-champ="membre-nom"]', 'Traore');
    saisir('[data-champ="membre-email"]', 'awa@cabinet.ci');
    saisir('[data-champ="membre-role"]', 'EXPERT_COMPTABLE');
    const cree = membre({ id: 'm-2', prenom: 'Awa', nom: 'Traore', email: 'awa@cabinet.ci', role: 'EXPERT_COMPTABLE', dossiersAffectes: 0 });
    service.ajouterMembre.and.returnValue(of({ membre: cree, motDePasseTemporaire: 'Abcd2345Efgh' }));
    service.equipe.and.returnValue(of([EXPERT, membre(), cree]));
    (el('[data-action="enregistrer-membre"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.ajouterMembre).toHaveBeenCalledWith({ prenom: 'Awa', nom: 'Traore', email: 'awa@cabinet.ci', role: 'EXPERT_COMPTABLE' });
    expect(el('[data-champ="mot-de-passe-temporaire"]')!.innerText).toContain('Abcd2345Efgh');
    expect(el('[data-membre="awa@cabinet.ci"]')).not.toBeNull();
    expect(el('[data-zone="formulaire-membre"]')).toBeNull();
  });

  it('erreur métier du backend (doublon) affichée telle quelle, sans perdre la saisie', async () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    await ouvrirFormulaire();
    saisir('[data-champ="membre-prenom"]', 'Awa');
    saisir('[data-champ="membre-nom"]', 'Traore');
    saisir('[data-champ="membre-email"]', 'fatou@cabinet.ci');
    service.ajouterMembre.and.returnValue(throwError(() => ({ error: { message: 'Cette adresse e-mail est déjà utilisée' } })));
    (el('[data-action="enregistrer-membre"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-champ="erreur-equipe"]')!.innerText).toContain('déjà utilisée');
    expect((el('[data-champ="membre-nom"]') as HTMLInputElement).value).toBe('Traore');
  });

  it('désactiver et changer de rôle appellent le serveur puis rechargent la liste', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    service.modifierMembre.and.returnValue(of(membre({ statut: 'INACTIF' })));
    (el('[data-membre="fatou@cabinet.ci"] [data-action="desactiver-membre"]') as HTMLButtonElement).click();
    expect(service.modifierMembre).toHaveBeenCalledWith('m-1', { statut: 'INACTIF' });
    expect(service.equipe.calls.count()).toBe(2);
    saisir('[data-membre="fatou@cabinet.ci"] [data-action="changer-role"]', 'EXPERT_COMPTABLE');
    expect(service.modifierMembre).toHaveBeenCalledWith('m-1', { role: 'EXPERT_COMPTABLE' });
  });

  it('un membre désactivé est signalé et réactivable', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE'], [EXPERT, membre({ statut: 'INACTIF' })]);
    expect(el('[data-membre="fatou@cabinet.ci"]')!.innerText).toContain('Désactivé');
    expect(el('[data-membre="fatou@cabinet.ci"] [data-action="reactiver-membre"]')).not.toBeNull();
  });

  it('état vide explicite quand il n\'y a aucun collaborateur', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE'], [EXPERT]);
    expect(el('[data-champ="aucun-collaborateur"]')!.innerText).toContain('Aucun collaborateur pour le moment.');
    expect(el('[data-champ="aucun-collaborateur"]')!.innerText).toContain('Ajoutez un membre pour commencer à répartir les dossiers.');
  });

  it('expert : « Réinitialiser le mot de passe » affiche UNE fois un lien à usage unique, jamais le mot de passe ; absent pour soi-même et pour un collaborateur', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    expect(el('[data-membre="expert@cabinet.ci"] [data-action="reinitialiser-mot-de-passe"]')).toBeNull();
    service.reinitialiserMotDePasse.and.returnValue(of({ jeton: 'jeton-secret-0123456789', expireAt: '2026-10-09T10:00:00Z', chemin: '/reinitialiser-mot-de-passe?jeton=jeton-secret-0123456789' }));
    (el('[data-membre="fatou@cabinet.ci"] [data-action="reinitialiser-mot-de-passe"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.reinitialiserMotDePasse).toHaveBeenCalledWith('m-1');
    const notice = el('[data-champ="lien-reinitialisation"]')!.innerText;
    expect(notice).toContain('Fatou Kone');
    expect(notice).toContain('/reinitialiser-mot-de-passe?jeton=jeton-secret-0123456789');
    expect(notice).toContain('usage unique');
  });

  it('un collaborateur ne peut pas réinitialiser le mot de passe d’un membre', () => {
    ouvrir(['ROLE_COLLABORATEUR']);
    expect(el('[data-action="reinitialiser-mot-de-passe"]')).toBeNull();
  });

  it('un refus du serveur à l’émission du lien est affiché', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    service.reinitialiserMotDePasse.and.returnValue(throwError(() => ({ error: { message: 'Membre introuvable' } })));
    (el('[data-membre="fatou@cabinet.ci"] [data-action="reinitialiser-mot-de-passe"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-champ="erreur-equipe"]')!.innerText).toContain('Membre introuvable');
    expect(el('[data-champ="lien-reinitialisation"]')).toBeNull();
  });
});
