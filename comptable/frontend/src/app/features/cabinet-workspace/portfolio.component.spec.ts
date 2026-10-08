import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { PortfolioComponent } from './portfolio.component';
import { dossier, page } from './fixtures.spec-util';

describe('PortfolioComponent (Mes dossiers)', () => {
  let service: jasmine.SpyObj<CabinetWorkspaceService>;
  let fixture: ComponentFixture<PortfolioComponent>;

  function ouvrir(roles: string[], params: Record<string, string> = {}, reponse = page([dossier()])): void {
    service.dossiers.and.returnValue(of(reponse));
    service.membres.and.returnValue(of([{ id: 'u-1', nom: 'Fatou Kone' }, { id: 'u-2', nom: 'Moussa Diallo' }]));
    TestBed.configureTestingModule({
      imports: [PortfolioComponent],
      providers: [
        provideRouter([]),
        { provide: CabinetWorkspaceService, useValue: service },
        { provide: TokenStorageService, useValue: { getRoles: () => roles } },
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap(params)) } },
      ],
    });
    fixture = TestBed.createComponent(PortfolioComponent);
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);
  const dernier = (): Record<string, unknown> => service.dossiers.calls.mostRecent().args[0] as Record<string, unknown>;

  beforeEach(() => {
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['dossiers', 'membres', 'affecterEnMasse']);
  });

  it('affiche les colonnes utiles avec statut, étapes, pièces, banque, contrôles et activité fournis par le backend', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE'], {}, page([dossier({ statut: 'ATTENTION_REQUISE', statutLibelle: 'Attention requise', piecesManquantes: 2, banqueExceptions: 3, findingsActifs: 4, findingsBloquants: 1 })]));
    const ligne = el('[data-dossier="ABC SARL"]')!;
    const t = ligne.innerText;
    for (const attendu of ['ABC SARL', 'Exercice 2026', 'Fatou Kone', 'Attention requise', '5 / 7', 'il y a 2 h']) {
      expect(t).toContain(attendu);
    }
    expect(ligne.getAttribute('data-statut-dossier')).toBe('ATTENTION_REQUISE');
    expect(t).toContain('4');
    expect(t).toContain('1 bloquants');
    expect(ligne.querySelector('[data-statut="ATTENTION_REQUISE"]')).not.toBeNull();
  });

  it('les étapes restantes sont nommées sans inventer de pourcentage', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    const t = el('[data-dossier="ABC SARL"]')!.innerText;
    expect(t).toContain('États financiers, Finalisation');
    expect(t).not.toContain('%');
  });

  it('un collaborateur démarre sur « Mes dossiers », un expert sur « Tous » ; le filtre de l\'URL est repris', () => {
    ouvrir(['ROLE_COLLABORATEUR']);
    expect(dernier()['filtre']).toBe('MES_DOSSIERS');
    TestBed.resetTestingModule();
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['dossiers', 'membres', 'affecterEnMasse']);
    ouvrir(['ROLE_EXPERT_COMPTABLE'], { filtre: 'PRETS_REVUE' });
    expect(dernier()['filtre']).toBe('PRETS_REVUE');
    expect(el('[data-filtre="PRETS_REVUE"]')!.getAttribute('aria-selected')).toBe('true');
  });

  it('choisir un filtre déclenche la requête côté serveur (jamais un filtre local)', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    (el('[data-filtre="EN_ATTENTE_CLIENT"]') as HTMLButtonElement).click();
    expect(dernier()['filtre']).toBe('EN_ATTENTE_CLIENT');
    expect(dernier()['page']).toBe(0);
    const statut = el('[data-champ="filtre-statut"]') as HTMLSelectElement;
    statut.value = 'PRET_POUR_REVUE';
    statut.dispatchEvent(new Event('change'));
    expect(dernier()['statut']).toBe('PRET_POUR_REVUE');
    const tri = el('[data-champ="tri"]') as HTMLSelectElement;
    tri.value = 'ECHEANCE';
    tri.dispatchEvent(new Event('change'));
    expect(dernier()['tri']).toBe('ECHEANCE');
  });

  it('la recherche est envoyée au serveur après une courte pause', fakeAsync(() => {
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    const appels = service.dossiers.calls.count();
    const champ = el('[data-champ="recherche"]') as HTMLInputElement;
    champ.value = 'abc';
    champ.dispatchEvent(new Event('input'));
    tick(100);
    expect(service.dossiers.calls.count()).toBe(appels);
    tick(200);
    expect(dernier()['q']).toBe('abc');
    expect(service.dossiers.calls.count()).toBe(appels + 1);
  }));

  it('pagination serveur : suivant demande la page 1, précédent est désactivé sur la première', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE'], {}, page([dossier()], 60, 0, 25));
    expect((el('[data-action="page-precedente"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(el('[data-zone="pagination"]')!.innerText).toContain('1–25 sur 60');
    (el('[data-action="page-suivante"]') as HTMLButtonElement).click();
    expect(dernier()['page']).toBe(1);
  });

  it('état vide explicite, adapté au filtre « Mes dossiers »', () => {
    ouvrir(['ROLE_COLLABORATEUR'], {}, page([]));
    expect(el('[data-champ="portefeuille-vide"]')!.innerText).toContain('Aucun dossier ne vous est affecté');
  });

  it('l\'affectation en masse est réservée à l\'expert : sélection, choix du collaborateur, requête, message', () => {
    ouvrir(['ROLE_COLLABORATEUR']);
    expect(el('input[type="checkbox"]')).toBeNull();
    TestBed.resetTestingModule();
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['dossiers', 'membres', 'affecterEnMasse']);
    ouvrir(['ROLE_EXPERT_COMPTABLE']);
    expect(el('[data-zone="affectation-masse"]')).toBeNull();
    (el('input[type="checkbox"]') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(el('[data-zone="affectation-masse"]')!.innerText).toContain('1 dossier sélectionné');
    expect((el('[data-action="affecter-masse"]') as HTMLButtonElement).disabled).toBeTrue();
    const choix = el('[data-champ="masse-collaborateur"]') as HTMLSelectElement;
    choix.value = 'u-2';
    choix.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    service.affecterEnMasse.and.returnValue(of({ dossiers: 1 }));
    (el('[data-action="affecter-masse"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.affecterEnMasse).toHaveBeenCalledWith([{ entrepriseId: 'e-1', exerciceId: 'x-1' }], 'u-2', null);
    expect(el('[data-champ="info"]')!.innerText).toContain('1 dossier affecté');
  });

  it('colonne TVA : l’avancement fiscal vient du backend, « — » quand aucune période n’existe', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE'], {}, page([
      dossier({ entrepriseId: 'a', exerciceId: 'xa', entrepriseNom: 'A', tva: 'A_REVOIR', tvaLibelle: 'À revoir' }),
      dossier({ entrepriseId: 'b', exerciceId: 'xb', entrepriseNom: 'B' }),
    ]));
    expect((el('[data-dossier="A"] [data-champ="tva"]') as HTMLElement).innerText).toBe('À revoir');
    expect((el('[data-dossier="B"] [data-champ="tva"]') as HTMLElement).innerText).toBe('—');
  });

  it('Ouvrir mène à l\'écran où l\'action suivante se fait, selon le statut', () => {
    ouvrir(['ROLE_EXPERT_COMPTABLE'], {}, page([
      dossier({ entrepriseId: 'a', exerciceId: 'xa', entrepriseNom: 'A', statut: 'PRET_POUR_REVUE' }),
      dossier({ entrepriseId: 'b', exerciceId: 'xb', entrepriseNom: 'B', statut: 'EN_ATTENTE_CLIENT' }),
      dossier({ entrepriseId: 'c', exerciceId: 'xc', entrepriseNom: 'C', statut: 'ATTENTION_REQUISE' }),
    ]));
    const href = (nom: string) => (el(`[data-dossier="${nom}"] [data-action="ouvrir-dossier"]`) as HTMLAnchorElement).getAttribute('href');
    expect(href('A')).toBe('/entreprises/a/etats-financiers');
    expect(href('B')).toBe('/entreprises/b/demandes-pieces');
    expect(href('C')).toBe('/entreprises/c/revision');
  });
});
