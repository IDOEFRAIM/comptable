import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { EntrepriseService } from '../entreprises/entreprise.service';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { DossierFinancierComponent } from './dossier-financier.component';
import { DossierFinancierService } from './dossier-financier.service';
import { EtatDto } from './dossier-financier.model';
import { espace, note, synthese } from './fixtures.spec-util';
import { SyntheseDossierDto } from './dossier-financier.model';

function etat(type: EtatDto['statementType'], status: EtatDto['status']): EtatDto {
  return {
    statementType: type, framework: 'F', version: '2', definitionHash: 'h', exerciceId: 'ex-1', exerciceDebut: '2026-01-01', exerciceFin: '2026-12-31',
    exercicePrecedentId: null, precedentDebut: null, precedentFin: null, status, statusReasons: [], lines: [], unmapped: [], ambiguous: [], controls: [],
  };
}

describe('DossierFinancierComponent (vue d\'ensemble)', () => {
  let service: jasmine.SpyObj<DossierFinancierService>;
  let fixture: ComponentFixture<DossierFinancierComponent>;

  async function ouvrir(s: SyntheseDossierDto): Promise<void> {
    service.synthese.and.returnValue(of(s));
    service.compteResultat.and.returnValue(of(etat('COMPTE_RESULTAT', 'READY')));
    service.bilan.and.returnValue(of({ actif: etat('BILAN_ACTIF', 'READY'), passif: etat('BILAN_PASSIF', 'READY'), status: 'READY', reasons: [], totalActifN: 0, totalPassifN: 0, resultatBilanN: 0, resultatCompteResultatN: 0 }));
    service.tft.and.returnValue(of(etat('TFT', s.cashFlow.status)));
    service.notes.and.returnValue(of(espace([note()])));
    fixture = TestBed.createComponent(DossierFinancierComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  beforeEach(() => {
    service = jasmine.createSpyObj<DossierFinancierService>('DossierFinancierService', ['synthese', 'compteResultat', 'bilan', 'tft', 'notes', 'snapshot']);
    TestBed.configureTestingModule({
      imports: [DossierFinancierComponent],
      providers: [
        { provide: DossierFinancierService, useValue: service },
        { provide: EntrepriseService, useValue: { lister: () => of([{ id: 'e-1', raisonSociale: 'Société ABC' }]) } },
        { provide: ComptabiliteService, useValue: { listerExercices: () => of([{ id: 'ex-1', dateDebut: '2026-01-01', dateFin: '2026-12-31', statut: 'CLOTURE' }]), cloturerExercice: () => of({}) } },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e-1' })) } },
      ],
    });
  });

  it('affiche les statuts réels du backend sans les adoucir : un TFT INCOMPLETE reste « Incomplet » (icône + texte)', async () => {
    await ouvrir(synthese({ cashFlow: { status: 'INCOMPLETE', reasons: ['CAFG non résolue'] }, finalizable: false, blockingFindings: 2 }));

    const tft = el('[data-etat-tuile="TFT"]')!;
    expect(tft.innerText).toContain('Incomplet');
    expect(tft.querySelector('[data-statut="INCOMPLETE"]')).not.toBeNull();
    expect(el('[data-champ="controles-bloquants"]')!.innerText).toContain('2');
    expect(el('[data-champ="finalisable"]')!.innerText).toContain('NON');
  });

  it('progression des notes et travail par exception : les 3 premières interventions, jamais les lignes saines', async () => {
    const exceptions = ['a', 'b', 'c', 'd'].map((c) => ({ id: c, code: 'X', severity: 'BLOCKING' as const, category: 'NOTE_INCOMPLETE', title: `Intervention ${c}`, detail: '', tab: 'NOTES', target: {} }));
    await ouvrir(synthese({ exceptions, blockingFindings: 4, notes: { ...synthese().notes, decided: 39 } }));

    expect(el('[data-champ="progression-notes"]')!.innerText).toContain('39 / 46 décidées');
    expect(el('[data-champ="nb-interventions"]')!.innerText).toContain('4 élément(s)');
    expect((fixture.nativeElement as HTMLElement).innerText).toContain('Intervention a');
    expect((fixture.nativeElement as HTMLElement).innerText).not.toContain('Intervention d\n');
  });

  it('aucune anomalie : message explicite, et le bouton Finaliser de la vue d\'ensemble n\'est qu\'une navigation (la décision reste au backend)', async () => {
    await ouvrir(synthese({ exceptions: [], finalizable: true }));
    expect(el('[data-champ="rien-a-traiter"]')).not.toBeNull();
    (el('[data-action="aller-finaliser"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-zone="pre-finalisation"]')).not.toBeNull();
    expect(el('[data-action="finaliser"]')).not.toBeNull();             // finalizable=true + expert
  });

  it('un seul chargement agrégé par vue (synthèse, états, notes) : pas d\'appel par ligne ni par note', async () => {
    await ouvrir(synthese());
    expect(service.synthese).toHaveBeenCalledTimes(1);
    expect(service.notes).toHaveBeenCalledTimes(1);
    expect(service.bilan).toHaveBeenCalledTimes(1);
  });
});
