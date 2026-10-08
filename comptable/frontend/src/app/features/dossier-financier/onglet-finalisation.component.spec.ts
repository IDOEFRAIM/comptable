import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NEVER, of } from 'rxjs';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { SnapshotDto, SyntheseDossierDto } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { OngletFinalisationComponent } from './onglet-finalisation.component';
import { synthese } from './fixtures.spec-util';

describe('OngletFinalisationComponent (pré-finalisation, finalisation)', () => {
  let service: jasmine.SpyObj<DossierFinancierService>;
  let fixture: ComponentFixture<OngletFinalisationComponent>;
  let http: HttpTestingController;

  function creer(s: SyntheseDossierDto): void {
    fixture = TestBed.createComponent(OngletFinalisationComponent);
    fixture.componentRef.setInput('entrepriseId', 'e-1');
    fixture.componentRef.setInput('exerciceId', 'ex-1');
    fixture.componentRef.setInput('synthese', s);
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  beforeEach(() => {
    service = jasmine.createSpyObj<DossierFinancierService>('DossierFinancierService', ['finaliser', 'exporterFinal', 'exporterBrouillon', 'snapshot']);
    TestBed.configureTestingModule({
      imports: [OngletFinalisationComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DossierFinancierService, useValue: service },
        { provide: ComptabiliteService, useValue: jasmine.createSpyObj('ComptabiliteService', ['cloturerExercice']) },
      ],
    });
    service.snapshot.and.returnValue(NEVER);
    http = TestBed.inject(HttpTestingController);
  });

  it('backend finalizable=false : aucun bouton Finaliser (Angular ne recalcule pas la readiness), avec le nombre de bloquants', () => {
    creer(synthese({ finalizable: false, blockingFindings: 3, checklist: [{ code: 'TFT', label: 'TFT', status: 'BLOCKED', detail: 'INCOMPLETE' }] }));
    expect(el('[data-action="finaliser"]')).toBeNull();
    expect(el('[data-champ="non-finalisable"]')!.innerText).toContain('3 points bloquants');
    expect(el('[data-point="TFT"]')!.innerText).toContain('Bloquant');          // statut en icône + texte
  });

  it('backend finalizable=true et expert : bouton visible ; la finalisation exige une confirmation explicite d\'immuabilité', () => {
    creer(synthese({ finalizable: true }));
    expect(el('[data-action="finaliser"]')).not.toBeNull();
    expect(el('[data-zone="confirmation-finalisation"]')).toBeNull();

    (el('[data-action="finaliser"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-zone="confirmation-finalisation"]')!.innerText).toContain('version immuable');
    expect(service.finaliser).not.toHaveBeenCalled();

    service.finaliser.and.returnValue(of({ id: 'snap-1' } as unknown as SnapshotDto));
    (el('[data-action="confirmer-finalisation"]') as HTMLButtonElement).click();
    expect(service.finaliser).toHaveBeenCalledOnceWith('e-1', 'ex-1');
  });

  it('backend finalizable=true mais utilisateur non expert (collaborateur ou client) : pas de bouton', () => {
    creer(synthese({ finalizable: true, permissions: { prepare: true, review: false, finalise: false } }));
    expect(el('[data-action="finaliser"]')).toBeNull();
    creer(synthese({ finalizable: true, permissions: { prepare: false, review: false, finalise: false } }));
    expect(el('[data-action="finaliser"]')).toBeNull();
    expect(el('[data-action="cloturer"]')).toBeNull();
  });

  it('dossier déjà finalisé : « Dossier finalisé » (date, utilisateur, version, empreinte), plus d\'action de finalisation, lecture du snapshot seulement', () => {
    creer(synthese({
      finalised: true,
      finalizable: false,
      snapshot: { id: 'snap-1', createdAt: '2026-10-05T13:00:00Z', createdBy: 'E2E Expert', framework: 'SYSCOHADA_REVISE_AUDCIF_2017', definitionVersion: '2', payloadSha256: 'abc123', definitionHash: 'h', liveChangedSinceFinal: true },
    }));
    expect(el('[data-zone="dossier-finalise"]')!.innerText).toContain('Dossier finalisé');
    expect(el('[data-zone="dossier-finalise"]')!.innerText).toContain('E2E Expert');
    expect(el('[data-action="finaliser"]')).toBeNull();
    expect(el('[data-champ="donnees-modifiees"]')!.innerText).toContain('La version finale reste inchangée');
    // la visionneuse lit le snapshot par son identifiant (aucun état vivant n'est demandé)
    expect(service.snapshot).toHaveBeenCalledWith('e-1', 'snap-1');
    http.verify();
  });
});
