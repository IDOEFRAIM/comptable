import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { EntrepriseService } from '../entreprises/entreprise.service';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { EcheancesComponent } from './echeances.component';
import { echeance } from './fixtures.spec-util';

describe('EcheancesComponent', () => {
  let service: jasmine.SpyObj<CabinetWorkspaceService>;
  let fixture: ComponentFixture<EcheancesComponent>;

  function ouvrir(items = [echeance({ id: '1', libelle: 'Retard', periode: 'EN_RETARD' }), echeance({ id: '2', libelle: 'Semaine', periode: 'CETTE_SEMAINE' })]): void {
    service.echeances.and.returnValue(of({ enRetard: items.filter((i) => i.periode === 'EN_RETARD').length, aujourdhui: 0, cetteSemaine: items.filter((i) => i.periode === 'CETTE_SEMAINE').length, aVenir: 0, items }));
    TestBed.configureTestingModule({
      imports: [EcheancesComponent],
      providers: [provideRouter([]), { provide: CabinetWorkspaceService, useValue: service }, { provide: EntrepriseService, useValue: { lister: () => of([{ id: 'e-1', raisonSociale: 'ABC SARL' }]) } }],
    });
    fixture = TestBed.createComponent(EcheancesComponent);
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  beforeEach(() => {
    service = jasmine.createSpyObj<CabinetWorkspaceService>('CabinetWorkspaceService', ['echeances', 'creerEcheance', 'modifierEcheance']);
  });

  it('classe les échéances par période fournie par le backend, avec les compteurs', () => {
    ouvrir();
    expect(el('[data-periode="EN_RETARD"]')!.innerText).toContain('1');
    expect(el('[data-echeance="Retard"]')).not.toBeNull();
    expect(el('[data-echeance="Semaine"]')).toBeNull();
    (el('[data-periode="CETTE_SEMAINE"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-echeance="Semaine"]')).not.toBeNull();
    expect(el('[data-echeance="Semaine"]')!.innerText).toContain('12/10/2026');
  });

  it('état vide explicite pour une période sans échéance', () => {
    ouvrir([]);
    expect(el('[data-zone="echeances-page"]')!.innerText).toContain('Aucune échéance');
  });

  it('marquer terminée appelle le serveur puis recharge', () => {
    ouvrir();
    service.modifierEcheance.and.returnValue(of(echeance({ statut: 'FAITE', periode: 'FAITE' })));
    (el('[data-action="terminer-echeance"]') as HTMLButtonElement).click();
    expect(service.modifierEcheance).toHaveBeenCalledWith('1', { statut: 'FAITE' });
    expect(service.echeances.calls.count()).toBe(2);
  });

  it('créer une échéance envoie type, libellé et date', () => {
    ouvrir();
    (el('[data-action="ajouter-echeance"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const cmp = fixture.componentInstance;
    cmp.nouvelle = { entrepriseId: 'e-1', type: 'RELANCE_CLIENT', libelle: 'Relancer', date: '2026-11-01' };
    service.creerEcheance.and.returnValue(of(echeance()));
    cmp.creer();
    expect(service.creerEcheance).toHaveBeenCalledWith({ entrepriseId: 'e-1', type: 'RELANCE_CLIENT', libelle: 'Relancer', date: '2026-11-01' });
  });
});
