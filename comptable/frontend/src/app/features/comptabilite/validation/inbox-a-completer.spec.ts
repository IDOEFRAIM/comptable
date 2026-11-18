import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { DocumentHubService } from '../../documents/document-hub.service';
import { LigneHubDto } from '../../documents/models/document-hub.model';
import { ComptabiliteService } from '../comptabilite.service';
import { DocumentAgentService } from '../document-agent.service';
import { InboxValidationComponent } from './inbox-validation.component';

/** « À valider » ne ment plus : une pièce que la lecture automatique n'a pas pu lire y apparaît, avec de quoi la relire ou la compléter. */
describe('À valider : pièces à compléter', () => {
  const piece = (extra: Partial<LigneHubDto> = {}): LigneHubDto =>
    ({
      id: 'p1', type: 'FACTURE_ACHAT', canal: 'WEB', recuLe: '2026-11-18T10:00:00Z', datePiece: null, tiersId: null, tiersNom: null, fournisseurExtrait: null, numeroFacture: null,
      montantTtc: null, devise: null, statut: 'ERREUR', statutRapprochement: 'AUCUN', association: null, ecritureId: null, qualiteTiers: null, confianceTiers: null,
      confianceRapprochement: null, valide: false, doublonConfirme: false, constats: [], lectureIncomplete: true, motifLecture: "La lecture automatique n'a pas abouti.", ...extra,
    }) as LigneHubDto;

  function ouvrir(lignes: LigneHubDto[], relire: unknown = of({})) {
    const hub = {
      lister: jasmine.createSpy('lister').and.returnValue(of({ contenu: lignes, page: 0, taille: 50, total: lignes.length, totalPages: 1 })),
      relire: jasmine.createSpy('relire').and.returnValue(relire),
      fichier: () => of(new Blob()),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: DocumentHubService, useValue: hub },
        { provide: DocumentAgentService, useValue: { listerEnAttenteValidation: () => of([]) } },
        { provide: ComptabiliteService, useValue: { listerComptes: () => of([]), listerTiers: () => of([]) } },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e1' })) } },
      ],
    });
    const f = TestBed.createComponent(InboxValidationComponent);
    f.detectChanges();
    return { f, hub, racine: f.nativeElement as HTMLElement };
  }

  it('affiche la pièce illisible avec son motif au lieu de « Aucune pièce en attente »', () => {
    const { hub, racine } = ouvrir([piece()]);
    expect(hub.lister).toHaveBeenCalledWith('e1', { tuile: 'A_COMPLETER', taille: 50 });
    expect(racine.querySelector('[data-zone="a-completer"]')!.textContent).toContain("La lecture automatique n'a pas abouti.");
    expect(racine.textContent).not.toContain('Aucune pièce en attente de validation.');
    expect(racine.textContent).toContain('Aucune autre pièce en attente de validation.');
  });

  it('sans aucune pièce à compléter ni à valider, dit simplement qu’il n’y a rien', () => {
    const { racine } = ouvrir([]);
    expect(racine.querySelector('[data-zone="a-completer"]')).toBeNull();
    expect(racine.textContent).toContain('Aucune pièce en attente de validation.');
  });

  it('« Relire avec l’IA » relance la lecture du fichier déjà reçu et recharge la liste', () => {
    const { f, hub, racine } = ouvrir([piece()]);
    (racine.querySelector('[data-action="relire-ia"]') as HTMLButtonElement).click();
    f.detectChanges();
    expect(hub.relire).toHaveBeenCalledWith('e1', 'p1');
    expect(hub.lister).toHaveBeenCalledTimes(2);
    expect(racine.textContent).toContain('relue par l’IA');
  });

  it('une relecture qui échoue dit pourquoi, sans rien casser', () => {
    const { f, racine } = ouvrir([piece()], throwError(() => ({ error: { message: 'La lecture automatique n’a toujours pas abouti.' } })));
    (racine.querySelector('[data-action="relire-ia"]') as HTMLButtonElement).click();
    f.detectChanges();
    expect(racine.textContent).toContain('toujours pas abouti');
    expect(racine.querySelector('[data-zone="a-completer"]')).not.toBeNull();
  });

  it('« Compléter » ouvre la fenêtre de saisie de la pièce', () => {
    const { f, racine } = ouvrir([piece()]);
    (racine.querySelector('[data-action="completer-piece"]') as HTMLButtonElement).click();
    f.detectChanges();
    expect(racine.querySelector('[data-zone="completer-piece"]')).not.toBeNull();
  });
});
