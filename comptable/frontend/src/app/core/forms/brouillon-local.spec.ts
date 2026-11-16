import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ComptabiliteService } from '../../features/comptabilite/comptabilite.service';
import { SaisieEcritureComponent } from '../../features/comptabilite/ecritures/saisie-ecriture.component';
import { TokenStorageService } from '../auth/token-storage.service';
import { BrouillonLocalService, DUREE_MAX_BROUILLON_MS } from './brouillon-local.service';
import { PREFIXE_BROUILLON } from './brouillons-stockage';

function jeton(email: string): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'none' })}.${b64({ email })}.sig`;
}

const nettoyer = () => {
  localStorage.clear();
  sessionStorage.clear();
};

describe('Brouillons de saisie sur l’appareil', () => {
  beforeEach(() => {
    nettoyer();
    TestBed.inject(TokenStorageService).setToken(jeton('a@cabinet.test'));
  });
  afterEach(nettoyer);

  it('une saisie sauvée est relue telle quelle', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { libelle: 'Achat riz', montant: 15000 });
    expect(b.lire<{ libelle: string }>('ecriture', 'd1')?.valeurs).toEqual({ libelle: 'Achat riz', montant: 15000 } as never);
  });

  it('un brouillon est propre à un dossier et à un formulaire', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    expect(b.lire('ecriture', 'd2')).toBeNull();
    expect(b.lire('operation', 'd1')).toBeNull();
  });

  it('un brouillon est propre à un utilisateur', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    localStorage.setItem('comptable.jwt', jeton('autre@cabinet.test'));
    expect(b.lire('ecriture', 'd1')).toBeNull();
  });

  it('au-delà de 7 jours, il est ignoré et supprimé', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    const cle = Object.keys(localStorage).find((k) => k.startsWith(PREFIXE_BROUILLON))!;
    localStorage.setItem(cle, JSON.stringify({ le: Date.now() - DUREE_MAX_BROUILLON_MS - 1000, valeurs: { x: 1 } }));
    expect(b.lire('ecriture', 'd1')).toBeNull();
    expect(localStorage.getItem(cle)).toBeNull();
  });

  it('un contenu corrompu ne fait pas planter la saisie', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    const cle = Object.keys(localStorage).find((k) => k.startsWith(PREFIXE_BROUILLON))!;
    localStorage.setItem(cle, '{pas du json');
    expect(b.lire('ecriture', 'd1')).toBeNull();
  });

  it('effacer supprime le brouillon', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    b.effacer('ecriture', 'd1');
    expect(b.lire('ecriture', 'd1')).toBeNull();
  });

  it('la déconnexion supprime tous les brouillons', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    b.sauver('operation', 'd2', { y: 2 });
    TestBed.inject(TokenStorageService).clear();
    expect(Object.keys(localStorage).some((k) => k.startsWith(PREFIXE_BROUILLON))).toBeFalse();
  });

  it('la connexion d’une autre personne supprime les brouillons de la précédente', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    TestBed.inject(TokenStorageService).setToken(jeton('autre@cabinet.test'));
    expect(Object.keys(localStorage).some((k) => k.startsWith(PREFIXE_BROUILLON))).toBeFalse();
  });

  it('la même personne qui se reconnecte retrouve ses brouillons', () => {
    const b = TestBed.inject(BrouillonLocalService);
    b.sauver('ecriture', 'd1', { x: 1 });
    TestBed.inject(TokenStorageService).setToken(jeton('a@cabinet.test'));
    expect(b.lire('ecriture', 'd1')).not.toBeNull();
  });
});

describe('Saisie d’écriture : enregistrement à chaque modification', () => {
  const stub = { listerJournaux: () => of([]), listerComptes: () => of([]), listerTiers: () => of([]), creerEcriture: () => of({}) };

  beforeEach(() => {
    nettoyer();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ComptabiliteService, useValue: stub },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'd1' })) } },
      ],
    });
    TestBed.inject(TokenStorageService).setToken(jeton('a@cabinet.test'));
  });
  afterEach(nettoyer);

  it('ce que l’utilisateur tape est gardé sur l’appareil sans cliquer sur Enregistrer', fakeAsync(() => {
    const f = TestBed.createComponent(SaisieEcritureComponent);
    f.detectChanges();
    const c = f.componentInstance;
    c.form.controls.libelle.setValue('Achat de riz');
    c.form.markAsDirty();
    tick(300);
    const lu = TestBed.inject(BrouillonLocalService).lire<{ libelle: string }>('ecriture', 'd1');
    expect(lu?.valeurs.libelle).toBe('Achat de riz');
  }));

  it('une saisie neuve (rien tapé) ne crée pas de brouillon', fakeAsync(() => {
    const f = TestBed.createComponent(SaisieEcritureComponent);
    f.detectChanges();
    tick(300);
    expect(TestBed.inject(BrouillonLocalService).lire('ecriture', 'd1')).toBeNull();
  }));

  it('à la réouverture (après coupure), la saisie est retrouvée avec toutes ses lignes', () => {
    TestBed.inject(BrouillonLocalService).sauver('ecriture', 'd1', {
      journalId: 'j1',
      dateEcriture: '2026-11-14',
      libelle: 'Achat de riz',
      numeroPiece: 'F-12',
      lignes: [
        { compteId: 'c1', libelle: '', debit: 15000, credit: 0, tiersId: '' },
        { compteId: 'c2', libelle: '', debit: 0, credit: 15000, tiersId: '' },
        { compteId: 'c3', libelle: '', debit: 0, credit: 0, tiersId: '' },
      ],
    });
    const f = TestBed.createComponent(SaisieEcritureComponent);
    f.detectChanges();
    const c = f.componentInstance;
    expect(c.form.controls.libelle.value).toBe('Achat de riz');
    expect(c.lignes.length).toBe(3);
    expect(c.totalDebit()).toBe(15000);
    expect(c.brouillonRestaure()).not.toBeNull();
    expect((f.nativeElement as HTMLElement).querySelector('[data-zone="brouillon-restaure"]')).not.toBeNull();
  });

  it('« Recommencer à zéro » efface le brouillon et vide la saisie', () => {
    TestBed.inject(BrouillonLocalService).sauver('ecriture', 'd1', {
      journalId: '', dateEcriture: '2026-11-14', libelle: 'Vieux', numeroPiece: '',
      lignes: [{ compteId: '', libelle: '', debit: 5, credit: 0, tiersId: '' }, { compteId: '', libelle: '', debit: 0, credit: 5, tiersId: '' }],
    });
    const f = TestBed.createComponent(SaisieEcritureComponent);
    f.detectChanges();
    f.componentInstance.recommencer();
    expect(f.componentInstance.form.controls.libelle.value).toBe('');
    expect(f.componentInstance.brouillonRestaure()).toBeNull();
    expect(TestBed.inject(BrouillonLocalService).lire('ecriture', 'd1')).toBeNull();
  });
});
