import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { CabinetWorkspaceComponent } from './cabinet-workspace.component';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { dashboard, dossier, echeance, item, page } from './fixtures.spec-util';

describe('CabinetWorkspaceComponent (tableau de bord du cabinet)', () => {
  let service: jasmine.SpyObj<CabinetWorkspaceService>;
  let fixture: ComponentFixture<CabinetWorkspaceComponent>;

  async function ouvrir(roles: string[], reponse = dashboard()): Promise<void> {
    TestBed.configureTestingModule({
      imports: [CabinetWorkspaceComponent],
      providers: [provideRouter([]), { provide: CabinetWorkspaceService, useValue: service }, { provide: TokenStorageService, useValue: { getRoles: () => roles } }],
    });
    service.dashboard.and.returnValue(of(reponse));
    fixture = TestBed.createComponent(CabinetWorkspaceComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);
  const texte = (): string => (fixture.nativeElement as HTMLElement).innerText;

  beforeEach(() => {
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['dashboard']);
  });

  it('affiche cinq indicateurs sobres fournis par le backend, jamais recalculés', async () => {
    await ouvrir(['ROLE_EXPERT_COMPTABLE']);
    expect(el('[data-champ="resume-actifs"]')!.innerText).toContain('42');
    expect(el('[data-champ="resume-a-traiter"]')!.innerText).toContain('12');
    expect(el('[data-champ="resume-attente-client"]')!.innerText).toContain('7');
    expect(el('[data-champ="resume-prets-revue"]')!.innerText).toContain('3');
    expect(el('[data-champ="resume-echeances"]')!.innerText).toContain('4');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('[data-zone="resume"] > a').length).toBe(5);
  });

  it('la file À traiter montre entreprise, problème, domaine, impact, ancienneté, responsable et une action Ouvrir vers la source', async () => {
    await ouvrir(['ROLE_COLLABORATEUR']);
    const ligne = el('[data-item="f-1"]')!;
    const t = ligne.innerText;
    for (const attendu of ['ABC SARL', 'Facture fournisseur manquante', 'Documents', 'Bloque la clôture', 'Depuis 5 jours', 'Fatou Kone']) {
      expect(t).toContain(attendu);
    }
    expect((ligne.querySelector('[data-action="ouvrir-item"]') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e-1/revision');
  });

  it('une pièce reçue ouvre les demandes, une anomalie bancaire la banque : la cible vient du backend', async () => {
    await ouvrir(['ROLE_EXPERT_COMPTABLE'], dashboard({ workQueue: page([item({ id: 'd-1', kind: 'DEMANDE_PIECE', cible: 'DEMANDES' }), item({ id: 'b-1', cible: 'BANQUE' })]) }));
    expect((el('[data-item="d-1"] a') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e-1/demandes-pieces');
    expect((el('[data-item="b-1"] a') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e-1/rapprochement');
  });

  it('une anomalie ou une déclaration fiscale ouvre l’espace TVA : la cible vient du backend', async () => {
    await ouvrir(['ROLE_EXPERT_COMPTABLE'], dashboard({ workQueue: page([item({ id: 't-1', kind: 'TAX_REVIEW', cible: 'FISCALITE', titre: 'Déclaration TVA prête pour revue', domaine: 'Fiscalité', impact: 'À revoir par l’expert'})]) }));
    expect((el('[data-item="t-1"] a') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e-1/fiscalite/tva');
    expect(el('[data-item="t-1"]')!.innerText).toContain('Fiscalité');
  });

  it('un collaborateur arrive sur ses dossiers ; un expert sur tout le cabinet', async () => {
    await ouvrir(['ROLE_COLLABORATEUR']);
    expect(service.dashboard).toHaveBeenCalledWith(true);
    expect(el('[data-perimetre="MES_DOSSIERS"]')!.getAttribute('aria-pressed')).toBe('true');
    TestBed.resetTestingModule();
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['dashboard']);
    await ouvrir(['ROLE_EXPERT_COMPTABLE']);
    expect(service.dashboard).toHaveBeenCalledWith(false);
  });

  it('changer de périmètre recharge côté serveur', async () => {
    await ouvrir(['ROLE_COLLABORATEUR']);
    (el('[data-perimetre="CABINET"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.dashboard).toHaveBeenCalledWith(false);
  });

  it('la file À revoir n\'existe que pour l\'expert ; le collaborateur ne la voit pas', async () => {
    const aRevoir = dashboard({ reviewQueue: [dossier({ statut: 'PRET_POUR_REVUE', statutLibelle: 'Prêt pour revue' })] });
    await ouvrir(['ROLE_EXPERT_COMPTABLE'], aRevoir);
    expect(el('[data-zone="a-revoir"]')!.innerText).toContain('ABC SARL');
    expect((el('[data-action="ouvrir-revue"]') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e-1/etats-financiers');
    TestBed.resetTestingModule();
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['dashboard']);
    await ouvrir(['ROLE_COLLABORATEUR'], aRevoir);
    expect(el('[data-zone="a-revoir"]')).toBeNull();
  });

  it('états vides explicites : rien à traiter, aucune échéance', async () => {
    await ouvrir(['ROLE_EXPERT_COMPTABLE'], dashboard({ workQueue: page([]), deadlines: [] }));
    expect(el('[data-champ="rien-a-traiter"]')!.innerText).toContain('Rien à traiter');
    expect(el('[data-zone="echeances"]')!.innerText).toContain('Aucune échéance');
    expect(el('[data-zone="a-revoir"]')!.innerText).toContain('Aucun dossier à revoir');
  });

  it('les échéances en retard sont signalées et le reste de la file est annoncé', async () => {
    await ouvrir(['ROLE_EXPERT_COMPTABLE'], dashboard({ workQueue: page([item()], 13), deadlines: [echeance({ periode: 'EN_RETARD', date: '2026-09-30' })] }));
    expect(el('[data-zone="echeances"] .en-retard')!.innerText).toBe('30/09/2026');
    expect(texte()).toContain('12 autres éléments');
  });

  it('une erreur du serveur est affichée sans casser l\'écran', async () => {
    TestBed.configureTestingModule({
      imports: [CabinetWorkspaceComponent],
      providers: [provideRouter([]), { provide: CabinetWorkspaceService, useValue: service }, { provide: TokenStorageService, useValue: { getRoles: () => ['ROLE_EXPERT_COMPTABLE'] } }],
    });
    service.dashboard.and.returnValue(throwError(() => ({ error: { message: 'Indisponible' } })));
    fixture = TestBed.createComponent(CabinetWorkspaceComponent);
    fixture.detectChanges();
    expect(el('[role="alert"]')!.innerText).toContain('Indisponible');
  });
});
