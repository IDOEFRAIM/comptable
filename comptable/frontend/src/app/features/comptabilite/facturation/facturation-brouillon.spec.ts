import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { TokenStorageService } from '../../../core/auth/token-storage.service';
import { FileAttenteService } from '../../../core/pwa/file-attente.service';
import { HorsLigneService } from '../../../core/pwa/hors-ligne.service';
import { ComptabiliteService } from '../comptabilite.service';
import { FacturationComponent } from './facturation.component';

/** La saisie d'un devis ou d'une facture en cours survit à une coupure : elle est retrouvée à la réouverture, par type de document, et effacée une fois enregistrée. */
describe('Brouillon du formulaire de devis et de facture', () => {
  const service = {
    listerTiers: () => of([{ id: 'c1', nom: 'Boutique Ouedraogo' }]),
    listerArticles: () => of([]),
    listerComptes: () => of([]),
    listerFactures: () => of([]),
    listerAvoirs: () => of([]),
    ficheEntreprise: () => of({ raisonSociale: 'X', manquants: [] }),
    creerFacture: () => of({ id: 'd1', numero: 'DEV-2026-0001' }),
  };

  function ouvrir() {
    const f = TestBed.createComponent(FacturationComponent);
    f.detectChanges();
    return f;
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        { provide: ComptabiliteService, useValue: service },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e1' })) } },
        { provide: HorsLigneService, useValue: { enLigne: signal(true) } },
        { provide: FileAttenteService, useValue: { synchroniseeLe: signal(0), enAttente: signal([]) } },
        { provide: TokenStorageService, useValue: { getEmail: () => 'a@cabinet.test' } },
      ],
    });
  });

  afterEach(() => localStorage.clear());

  it('une saisie commencée est retrouvée à la réouverture, avec ses lignes', () => {
    const premier = ouvrir().componentInstance;
    premier.form.patchValue({ tiersId: 'c1', objet: 'Conseil' });
    premier.lignes.at(0).patchValue({ designation: 'Audit', prixUnitaire: 50000 });
    premier.ajouterLigne();
    premier.lignes.at(2).patchValue({ designation: 'Déplacement', prixUnitaire: 10000 });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: ComptabiliteService, useValue: service },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e1' })) } },
        { provide: HorsLigneService, useValue: { enLigne: signal(true) } },
        { provide: FileAttenteService, useValue: { synchroniseeLe: signal(0), enAttente: signal([]) } },
        { provide: TokenStorageService, useValue: { getEmail: () => 'a@cabinet.test' } },
      ],
    });

    const f = ouvrir();
    const reouvert = f.componentInstance;
    expect(reouvert.brouillonRestaure()).not.toBeNull();
    expect(reouvert.afficherFormulaire()).toBeTrue();
    expect(reouvert.form.getRawValue().objet).toBe('Conseil');
    expect(reouvert.lignes.length).toBe(3);
    expect(reouvert.lignes.at(2).getRawValue().designation).toBe('Déplacement');
    expect(f.nativeElement.querySelector('[data-zone="brouillon-restaure"]')).not.toBeNull();
  });

  it('le brouillon d’un devis n’apparaît pas dans une facture, et chaque type garde le sien', () => {
    const f = ouvrir();
    const c = f.componentInstance;
    c.form.patchValue({ objet: 'Pour le devis' });
    c.changerType('FACTURE');
    expect(c.form.getRawValue().objet).toBe('');
    expect(c.brouillonRestaure()).toBeNull();
    c.form.patchValue({ objet: 'Pour la facture' });
    c.changerType('DEVIS');
    expect(c.form.getRawValue().objet).toBe('Pour le devis');
    c.changerType('FACTURE');
    expect(c.form.getRawValue().objet).toBe('Pour la facture');
  });

  it('« Recommencer à zéro » vide le formulaire et efface le brouillon', () => {
    const c = ouvrir().componentInstance;
    c.form.patchValue({ objet: 'À jeter' });
    c.recommencer();
    expect(c.form.getRawValue().objet).toBe('');
    expect(c.lignes.length).toBe(2);
    expect(Object.keys(localStorage).filter((k) => k.includes('facture-DEVIS')).length).toBe(0);
  });

  it('une fois le devis enregistré, le brouillon est effacé', () => {
    const c = ouvrir().componentInstance;
    c.form.patchValue({ tiersId: 'c1' });
    c.lignes.at(0).patchValue({ designation: 'Audit', prixUnitaire: 1000 });
    c.lignes.at(1).patchValue({ designation: 'Rapport', prixUnitaire: 500 });
    expect(Object.keys(localStorage).filter((k) => k.includes('facture-DEVIS')).length).toBe(1);
    c.creer();
    expect(Object.keys(localStorage).filter((k) => k.includes('facture-DEVIS')).length).toBe(0);
    expect(c.brouillonRestaure()).toBeNull();
  });

  it('un formulaire laissé vide ne laisse aucun brouillon', () => {
    const c = ouvrir().componentInstance;
    c.form.patchValue({ objet: 'x' });
    c.form.patchValue({ objet: '' });
    expect(Object.keys(localStorage).filter((k) => k.includes('facture-DEVIS')).length).toBe(0);
  });
});
