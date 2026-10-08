import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { AvecModificationsNonEnregistrees, modificationsNonEnregistreesGuard } from './modifications-non-enregistrees.guard';

describe('modificationsNonEnregistreesGuard', () => {
  const ecran = (modifie: boolean): AvecModificationsNonEnregistrees => ({ aDesModificationsNonEnregistrees: () => modifie });
  const garde = (c: AvecModificationsNonEnregistrees) =>
    TestBed.runInInjectionContext(() => modificationsNonEnregistreesGuard(c, {} as ActivatedRouteSnapshot, {} as RouterStateSnapshot, {} as RouterStateSnapshot));

  it('rien de saisi : on quitte sans aucune question', () => {
    const confirmation = spyOn(window, 'confirm');
    expect(garde(ecran(false))).toBeTrue();
    expect(confirmation).not.toHaveBeenCalled();
  });

  it('saisie non enregistrée : confirmation demandée, refus = on reste sur la page', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    expect(garde(ecran(true))).toBeFalse();
  });

  it('saisie non enregistrée : confirmation acceptée = on quitte', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    expect(garde(ecran(true))).toBeTrue();
  });
});
