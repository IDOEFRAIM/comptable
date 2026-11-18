import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { DocumentHubService } from '../document-hub.service';
import { LigneHubDto } from '../models/document-hub.model';
import { CompleterPieceComponent } from './completer-piece.component';

/** Une pièce que la lecture automatique n'a pas pu lire : le comptable la complète à la main au lieu de rester bloqué. */
describe('Compléter une pièce illisible', () => {
  const ligne = (extra: Partial<LigneHubDto> = {}): LigneHubDto =>
    ({
      id: 'd1', type: 'FACTURE_ACHAT', canal: 'WEB', recuLe: '2026-11-18T10:00:00Z', datePiece: null, tiersId: null, tiersNom: null, fournisseurExtrait: null, numeroFacture: null,
      montantTtc: null, devise: null, statut: 'ERREUR', statutRapprochement: 'AUCUN', association: null, ecritureId: null, qualiteTiers: null, confianceTiers: null,
      confianceRapprochement: null, valide: false, doublonConfirme: false, constats: [], lectureIncomplete: true, motifLecture: "La lecture automatique n'a pas abouti.", ...extra,
    }) as LigneHubDto;

  function ouvrir(reponse = of({} as never)) {
    const corriger = jasmine.createSpy('corrigerDonnees').and.returnValue(reponse);
    TestBed.configureTestingModule({ providers: [{ provide: DocumentHubService, useValue: { corrigerDonnees: corriger } }] });
    const f = TestBed.createComponent(CompleterPieceComponent);
    f.componentRef.setInput('entrepriseId', 'e1');
    f.componentRef.setInput('ligne', ligne());
    f.detectChanges();
    return { f, corriger, racine: f.nativeElement as HTMLElement };
  }

  it('affiche le motif et envoie ce que le comptable a saisi, les champs vides partant comme absents', () => {
    const { f, corriger, racine } = ouvrir();
    expect(racine.textContent).toContain("La lecture automatique n'a pas abouti.");
    f.componentInstance.form.patchValue({ fournisseur: ' Station XYZ ', datePiece: '2026-11-10', montantHt: 100000, montantTtc: 118000 });
    let enregistre = false;
    f.componentInstance.enregistre.subscribe(() => (enregistre = true));
    f.componentInstance.soumettre();
    expect(corriger).toHaveBeenCalledWith('e1', 'd1', {
      fournisseur: 'Station XYZ', datePiece: '2026-11-10', numeroFacture: undefined, montantHt: 100000, montantTva: undefined, montantTtc: 118000,
    });
    expect(enregistre).toBeTrue();
  });

  it('dit pourquoi le serveur refuse (montants incohérents) sans fermer la fenêtre', () => {
    const { f, racine } = ouvrir(throwError(() => ({ status: 422, error: { message: 'Montants incoherents : HT + TVA different du TTC' } })));
    f.componentInstance.form.patchValue({ montantHt: 100000, montantTtc: 150000 });
    let enregistre = false;
    f.componentInstance.enregistre.subscribe(() => (enregistre = true));
    f.componentInstance.soumettre();
    f.detectChanges();
    expect(enregistre).toBeFalse();
    expect(racine.querySelector('[data-zone="erreur-completer"]')!.textContent).toContain('incoherents');
  });
});
