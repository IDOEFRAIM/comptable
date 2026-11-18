import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { FicheEntreprise } from '../comptabilite/models/comptabilite.model';
import { FicheEntrepriseComponent } from './fiche-entreprise.component';

/** La fiche légale : chargée, enregistrée avec les champs vides envoyés comme absents, et ce qui manque est dit clairement. */
describe('Fiche de l’entreprise', () => {
  const fiche = (extra: Partial<FicheEntreprise> = {}): FicheEntreprise => ({
    raisonSociale: 'Boutique Sawadogo', numeroContribuable: null, rccm: null, formeJuridique: null, regimeFiscal: null, adresse: null, telephone: null, email: null, coordonneesBancaires: null,
    mentionsFacture: null, manquants: ['IFU (numéro d’identification fiscale)', 'RCCM', 'Adresse'], ...extra,
  });

  function ouvrir(retour: FicheEntreprise = fiche()) {
    const modifier = jasmine.createSpy('modifier').and.callFake((_id: string, f: FicheEntreprise) => of({ ...f, manquants: [] }));
    TestBed.configureTestingModule({
      providers: [
        { provide: ComptabiliteService, useValue: { ficheEntreprise: () => of(retour), modifierFicheEntreprise: modifier } },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e1' })) } },
      ],
    });
    const f = TestBed.createComponent(FicheEntrepriseComponent);
    f.detectChanges();
    return { f, modifier, racine: f.nativeElement as HTMLElement };
  }

  it('affiche la fiche et dit ce qu’il manque pour des documents présentables', () => {
    const { racine } = ouvrir();
    expect((racine.querySelector('[data-champ="raisonSociale"]') as HTMLInputElement).value).toBe('Boutique Sawadogo');
    expect(racine.querySelector('[data-zone="fiche-manquants"]')!.textContent).toContain('IFU');
    expect(racine.querySelector('[data-zone="fiche-manquants"]')!.textContent).toContain('RCCM');
  });

  it('enregistre la fiche : les champs vides partent comme absents, et l’alerte disparaît une fois complète', () => {
    const { f, modifier, racine } = ouvrir();
    f.componentInstance.form.patchValue({ numeroContribuable: '00123456A', rccm: 'BF-OUA-2020-B-1234', adresse: 'Ouagadougou' });
    f.detectChanges();
    (racine.querySelector('[data-action="enregistrer-fiche"]') as HTMLButtonElement).click();
    f.detectChanges();
    const envoye = modifier.calls.mostRecent().args[1] as FicheEntreprise;
    expect(envoye.numeroContribuable).toBe('00123456A');
    expect(envoye.telephone).toBeNull();
    expect(racine.querySelector('[data-zone="fiche-enregistree"]')).not.toBeNull();
    expect(racine.querySelector('[data-zone="fiche-manquants"]')).toBeNull();
  });
});
