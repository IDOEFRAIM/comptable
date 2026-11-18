import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ComptabiliserModalComponent } from './comptabiliser-modal.component';
import { ComptabiliteService } from './comptabilite.service';

/** Quand l'extraction n'a pas lu de date sur la pièce, le comptable peut la saisir ici au lieu de rester bloqué par « Date de pièce manquante ». */
describe('Fenêtre « Comptabiliser le document » : date de la pièce', () => {
  function ouvrir() {
    const comptabiliser = jasmine.createSpy('comptabiliserDocument').and.returnValue(of({ id: 'ec1' }));
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ComptabiliteService,
          useValue: {
            listerTiers: () => of([{ id: 't1', nom: 'rouge', compteComptableNumero: '401002' }]),
            listerComptes: () => of([{ id: 'c1', numero: '601', intitule: 'Achats de marchandises', nature: 'CHARGE' }]),
            comptabiliserDocument: comptabiliser,
          },
        },
      ],
    });
    const f = TestBed.createComponent(ComptabiliserModalComponent);
    f.componentRef.setInput('entrepriseId', 'e1');
    f.componentRef.setInput('documentId', 'd1');
    f.componentRef.setInput('typeDocument', 'FACTURE_ACHAT');
    f.detectChanges();
    return { f, comptabiliser, racine: f.nativeElement as HTMLElement };
  }

  it('propose un champ date et envoie la date saisie', () => {
    const { f, comptabiliser, racine } = ouvrir();
    expect(racine.querySelector('[data-zone="date-piece"]')).not.toBeNull();
    f.componentInstance.form.patchValue({ tiersId: 't1', compteChargeOuProduitId: 'c1', dateEcriture: '2026-11-10' });
    f.componentInstance.soumettre();
    expect(comptabiliser.calls.mostRecent().args[2].dateEcriture).toBe('2026-11-10');
  });

  it('sans date saisie, n’envoie rien : le serveur garde la date lue sur la pièce', () => {
    const { f, comptabiliser } = ouvrir();
    f.componentInstance.form.patchValue({ tiersId: 't1', compteChargeOuProduitId: 'c1' });
    f.componentInstance.soumettre();
    expect(comptabiliser.calls.mostRecent().args[2].dateEcriture).toBeUndefined();
  });
});
