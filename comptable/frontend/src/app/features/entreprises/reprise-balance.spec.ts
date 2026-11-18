import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { ApercuReprise } from '../comptabilite/models/comptabilite.model';
import { RepriseBalanceComponent } from './reprise-balance.component';

/** L'écran de reprise : on choisit un fichier, le serveur explique chaque ligne, et l'import n'est possible que si tout est cohérent. */
describe('Reprise de balance', () => {
  const apercu = (extra: Partial<ApercuReprise> = {}): ApercuReprise => ({
    exerciceId: 'x1', dateOuverture: '2026-01-01', totalDebit: 1350000, totalCredit: 1350000, ecart: 0, erreurs: 0, tiersACreer: 1, importable: true, blocage: null, avertissements: [],
    lignes: [
      { ligne: 2, compte: '521', libelle: 'Banque', tiers: '', debit: 1000000, credit: 0, statut: 'OK', message: null, compteRetenu: '521 Banques' },
      { ligne: 4, compte: '411', libelle: 'Clients', tiers: 'Client Alpha', debit: 350000, credit: 0, statut: 'TIERS_A_CREER', message: 'Client « Client Alpha » inconnu : il sera créé avec son compte.', compteRetenu: null },
    ],
    ...extra,
  });

  function ouvrir(retour: ApercuReprise | 'erreur', importer = jasmine.createSpy('importer').and.returnValue(of({ ecriture: { id: 'e1', numeroPiece: 'AN-2026' }, lignes: 2, tiersCrees: 1 }))) {
    const analyse = jasmine.createSpy('apercu').and.returnValue(retour === 'erreur' ? throwError(() => ({ error: { message: 'Colonnes introuvables.' } })) : of(retour));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ComptabiliteService, useValue: { apercuReprise: analyse, importerReprise: importer } },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e1' })) } },
      ],
    });
    const f = TestBed.createComponent(RepriseBalanceComponent);
    f.detectChanges();
    f.componentInstance.fichier.set(new File(['x'], 'balance.csv'));
    f.detectChanges();
    return { f, analyse, importer, racine: f.nativeElement as HTMLElement };
  }
  const cliquer = (racine: HTMLElement, action: string) => (racine.querySelector(`[data-action="${action}"]`) as HTMLButtonElement).click();

  it('analyse le fichier, montre chaque ligne et annonce les tiers à créer', () => {
    const { f, racine } = ouvrir(apercu());
    cliquer(racine, 'analyser-reprise');
    f.detectChanges();
    expect(racine.querySelector('[data-zone="bilan-reprise"]')!.textContent).toContain('1 client(s) ou fournisseur(s) seront créés');
    expect(racine.querySelector('[data-zone="table-reprise"]')!.textContent).toContain('Tiers à créer');
    expect((racine.querySelector('[data-action="importer-reprise"]') as HTMLButtonElement).disabled).toBeFalse();
  });

  it('quand la balance a un problème, l’import est bloqué et la raison est dite', () => {
    const { f, racine } = ouvrir(apercu({ importable: false, blocage: 'La balance n’est pas équilibrée : écart 200.', ecart: 200,
      lignes: [{ ligne: 2, compte: '9999', libelle: 'X', tiers: '', debit: 100, credit: 0, statut: 'ERREUR', message: 'Seuls les comptes de bilan', compteRetenu: null }] }));
    cliquer(racine, 'analyser-reprise');
    f.detectChanges();
    expect(racine.querySelector('[data-zone="blocage-reprise"]')!.textContent).toContain('pas équilibrée');
    expect((racine.querySelector('[data-action="importer-reprise"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(racine.querySelector('.erreur-ligne')).not.toBeNull();
  });

  it('importe, puis renvoie vers les écritures pour la validation', () => {
    const { f, importer, racine } = ouvrir(apercu());
    cliquer(racine, 'analyser-reprise');
    f.detectChanges();
    cliquer(racine, 'importer-reprise');
    f.detectChanges();
    expect(importer).toHaveBeenCalled();
    expect(racine.querySelector('[data-zone="reprise-faite"]')!.textContent).toContain('AN-2026');
    expect(racine.querySelector('[data-zone="reprise-faite"]')!.textContent).toContain('brouillon');
  });

  it('un fichier illisible affiche le message du serveur', () => {
    const { f, racine } = ouvrir('erreur');
    cliquer(racine, 'analyser-reprise');
    f.detectChanges();
    expect(racine.querySelector('[data-zone="erreur-reprise"]')!.textContent).toContain('Colonnes introuvables');
  });
});
