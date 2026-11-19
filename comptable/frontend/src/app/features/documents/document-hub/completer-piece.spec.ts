import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ComptabiliteService } from '../../comptabilite/comptabilite.service';
import { DocumentHubService } from '../document-hub.service';
import { LigneHubDto } from '../models/document-hub.model';
import { CompleterPieceComponent } from './completer-piece.component';

/** Une pièce que la lecture automatique n'a pas pu lire : le comptable la complète sans quitter l'écran, en choisissant le fournisseur dans une liste. */
describe('Compléter une pièce illisible', () => {
  const tiers = [
    { id: 't1', nom: 'Station XYZ', compteComptableNumero: '401001' },
    { id: 't2', nom: 'Rouge SARL', compteComptableNumero: '401002' },
  ];

  const ligne = (extra: Partial<LigneHubDto> = {}): LigneHubDto =>
    ({
      id: 'd1', type: 'FACTURE_ACHAT', canal: 'WEB', recuLe: '2026-11-18T10:00:00Z', datePiece: null, tiersId: null, tiersNom: null, fournisseurExtrait: null, numeroFacture: null,
      montantTtc: null, devise: null, statut: 'ERREUR', statutRapprochement: 'AUCUN', association: null, ecritureId: null, qualiteTiers: null, confianceTiers: null,
      confianceRapprochement: null, valide: false, doublonConfirme: false, constats: [], lectureIncomplete: true, motifLecture: "La lecture automatique n'a pas abouti.", ...extra,
    }) as LigneHubDto;

  function ouvrir(reponse: unknown = of({}), l: LigneHubDto = ligne()) {
    const corriger = jasmine.createSpy('corrigerDonnees').and.returnValue(reponse);
    const listerTiers = jasmine.createSpy('listerTiers').and.returnValue(of(tiers));
    TestBed.configureTestingModule({
      providers: [
        { provide: DocumentHubService, useValue: { corrigerDonnees: corriger, fichier: () => of(new Blob(['x'], { type: 'image/png' })) } },
        { provide: ComptabiliteService, useValue: { listerTiers } },
      ],
    });
    const f = TestBed.createComponent(CompleterPieceComponent);
    f.componentRef.setInput('entrepriseId', 'e1');
    f.componentRef.setInput('ligne', l);
    f.detectChanges();
    return { f, corriger, listerTiers, racine: f.nativeElement as HTMLElement };
  }

  it('affiche le motif, la pièce à côté du formulaire, et propose les fournisseurs en liste', () => {
    const { racine, listerTiers } = ouvrir();
    expect(listerTiers).toHaveBeenCalledWith('e1', 'FOURNISSEUR');
    expect(racine.textContent).toContain("La lecture automatique n'a pas abouti.");
    expect(racine.querySelector('.apercu img')).not.toBeNull();
    const options = Array.from(racine.querySelectorAll('[data-champ="tiers"] option')).map((o) => o.textContent!.trim());
    expect(options).toEqual(['Sélectionner…', 'Station XYZ', 'Rouge SARL', 'Autre — saisir le nom…']);
  });

  it('envoie le fournisseur choisi dans la liste par son identifiant, jamais un nom tapé', () => {
    const { f, corriger } = ouvrir();
    f.componentInstance.form.patchValue({ tiersChoix: 't2', datePiece: '2026-11-10', montantHt: 100000, montantTtc: 118000 });
    let enregistre = false;
    f.componentInstance.enregistre.subscribe(() => (enregistre = true));
    f.componentInstance.soumettre();
    expect(corriger).toHaveBeenCalledWith('e1', 'd1', {
      tiersId: 't2', fournisseur: undefined, datePiece: '2026-11-10', numeroFacture: undefined, montantHt: 100000, montantTva: undefined, montantTtc: 118000,
    });
    expect(enregistre).toBeTrue();
  });

  it('présélectionne le fournisseur lu quand il existe déjà, sinon propose de saisir le nom lu', () => {
    const connu = ouvrir(of({}), ligne({ fournisseurExtrait: 'station xyz' }));
    expect(connu.f.componentInstance.form.controls.tiersChoix.value).toBe('t1');

    TestBed.resetTestingModule();
    const inconnu = ouvrir(of({}), ligne({ fournisseurExtrait: 'Nouveau Fournisseur' }));
    inconnu.f.detectChanges();
    expect(inconnu.f.componentInstance.form.controls.tiersChoix.value).toBe('__autre__');
    expect((inconnu.racine.querySelector('[data-champ="fournisseur"]') as HTMLInputElement).value).toBe('Nouveau Fournisseur');
    inconnu.f.componentInstance.soumettre();
    expect(inconnu.corriger.calls.mostRecent().args[2].fournisseur).toBe('Nouveau Fournisseur');
    expect(inconnu.corriger.calls.mostRecent().args[2].tiersId).toBeUndefined();
  });

  it('annonce la TVA qui sera calculée quand HT et TTC sont saisis', () => {
    const { f, racine } = ouvrir();
    f.componentInstance.form.patchValue({ montantHt: 100000, montantTtc: 118000 });
    f.detectChanges();
    expect((racine.querySelector('[data-champ="montantTva"]') as HTMLInputElement).placeholder).toContain('18');
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
