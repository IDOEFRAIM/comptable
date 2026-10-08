import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { DossierFinancierService } from './dossier-financier.service';
import { OngletNotesComponent } from './onglet-notes.component';
import { espace, note, section } from './fixtures.spec-util';
import { NoteDto } from './dossier-financier.model';

describe('OngletNotesComponent (Note Workspace)', () => {
  let service: jasmine.SpyObj<DossierFinancierService>;
  let fixture: ComponentFixture<OngletNotesComponent>;

  function creer(notes: NoteDto[], permissions = { prepare: true, review: true, finalise: true }, noteInitiale: string | null = null): void {
    fixture = TestBed.createComponent(OngletNotesComponent);
    fixture.componentRef.setInput('entrepriseId', 'e-1');
    fixture.componentRef.setInput('exerciceId', 'ex-1');
    fixture.componentRef.setInput('espace', espace(notes));
    fixture.componentRef.setInput('permissions', permissions);
    fixture.componentRef.setInput('noteInitiale', noteInitiale);
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);
  const texte = (): string => (fixture.nativeElement as HTMLElement).innerText;

  function saisir(selecteur: string, valeur: string): void {
    const champ = el(selecteur) as HTMLTextAreaElement;
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<DossierFinancierService>('DossierFinancierService', ['modifierDonneesManuelles', 'modifierApplicabilite', 'validerCandidates', 'relire', 'rouvrir']);
    TestBed.configureTestingModule({ imports: [OngletNotesComponent], providers: [{ provide: DossierFinancierService, useValue: service }] });
  });

  it('une note MIXED incomplète : parties calculée et à compléter séparées, envoi avec expectedVersion, puis readiness du backend', async () => {
    creer([note()], undefined, '17');
    expect(el('[data-zone="donnees-calculees"]')!.innerText).toContain('300');
    expect(el('[data-zone="informations-a-completer"]')!.innerText).toContain('À compléter');
    expect(texte()).toContain('Incomplet');

    service.modifierDonneesManuelles.and.returnValue(of(note({ completionStatus: 'READY', version: 4 })));
    let rechargee = false;
    fixture.componentInstance.modifiee.subscribe(() => (rechargee = true));
    saisir('[data-champ="champ-groupe"]', 'Aucune dette groupe');
    saisir('[data-champ="champ-echeances"]', '30 jours');
    (el('[data-action="enregistrer-note"]') as HTMLButtonElement).click();

    expect(service.modifierDonneesManuelles).toHaveBeenCalledWith('e-1', 'ex-1', '17', 3, jasmine.arrayContaining([
      jasmine.objectContaining({ sectionCode: 'groupe', fieldCode: 'contenu', value: 'Aucune dette groupe' }),
      jasmine.objectContaining({ sectionCode: 'echeances', fieldCode: 'contenu', value: '30 jours' }),
    ]));
    // le composant ne décide pas du statut : il demande au parent de recharger la vue du backend
    expect(rechargee).toBeTrue();
  });

  it('un conflit de version (409) affiche le message de rechargement et n\'écrase rien', () => {
    creer([note()], undefined, '17');
    service.modifierDonneesManuelles.and.returnValue(throwError(() => ({ status: 409, error: { message: 'Conflit de version sur la note 17 : version courante 4, version fournie 3' } })));
    saisir('[data-champ="champ-groupe"]', 'x');
    (el('[data-action="enregistrer-note"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el('[data-zone="conflit"]')!.innerText).toContain('Cette note a été modifiée par un autre utilisateur');
    expect(el('[data-zone="conflit"]')!.innerText).toContain('Rechargez les dernières données');
  });

  it('une modification après revue affiche « Revue devenue obsolète » (jamais une coche verte trompeuse)', () => {
    const revue = note({ completionStatus: 'READY', manualSections: [section('groupe', 'groupe', 'COMPLETE', 'ok')], review: { reviewedBy: 'u', reviewedAt: '2026-10-05T10:00:00Z', valid: true } });
    creer([revue], undefined, '17');
    expect(el('[data-champ="revue-info"]')).not.toBeNull();

    service.modifierDonneesManuelles.and.returnValue(of(note({ completionStatus: 'READY' })));
    saisir('[data-champ="champ-groupe"]', 'ok modifié');
    (el('[data-action="enregistrer-note"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el('[data-champ="revue-obsolete"]')!.innerText).toContain('Revue devenue obsolète');
  });

  it('une note READY non revue : l\'expert voit « Marquer comme revue », pas un collaborateur', () => {
    creer([note({ completionStatus: 'READY' })], { prepare: true, review: true, finalise: true }, '17');
    expect(el('[data-action="marquer-revue"]')).not.toBeNull();
    creer([note({ completionStatus: 'READY' })], { prepare: true, review: false, finalise: false }, '17');
    expect(el('[data-action="marquer-revue"]')).toBeNull();
  });

  it('CLIENT (aucun droit) : aucune action de modification, de décision, de revue ni de validation en masse', async () => {
    const candidate = note({ noteCode: '9', title: 'Titres de placement', applicabilityStatus: 'NOT_APPLICABLE_CANDIDATE', attached: false, completionStatus: 'MANUAL_REQUIRED' });
    creer([note(), candidate], { prepare: false, review: false, finalise: false }, '17');

    expect(el('[data-action="enregistrer-note"]')).toBeNull();
    expect(el('[data-action="applicable"]')).toBeNull();
    expect(el('[data-action="non-applicable"]')).toBeNull();
    expect(el('[data-action="marquer-revue"]')).toBeNull();
    expect(el('[data-action="valider-candidates"]')).toBeNull();
    expect(texte()).toContain('Lecture seule');
    (el('[data-note="17"]') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();            // NgModel applique `disabled` de façon asynchrone
    expect((el('textarea') as HTMLTextAreaElement).disabled).toBeTrue();
  });

  it('suggestions de non-applicabilité : la liste est affichée AVANT la validation explicite en masse', () => {
    const c1 = note({ noteCode: '9', title: 'Titres de placement', applicabilityStatus: 'NOT_APPLICABLE_CANDIDATE', attached: false, manualSections: [] });
    const c2 = note({ noteCode: '6', title: 'Stocks', applicabilityStatus: 'NOT_APPLICABLE_CANDIDATE', attached: false, manualSections: [] });
    creer([c1, c2]);
    expect(texte()).toContain('Valider les 2 suggestions de non-applicabilité');
    expect(el('[data-zone="confirmation-candidates"]')).toBeNull();

    (el('[data-action="valider-candidates"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const liste = el('[data-zone="confirmation-candidates"]')!.innerText;
    expect(liste).toContain('Note 9');
    expect(liste).toContain('Note 6');
    expect(service.validerCandidates).not.toHaveBeenCalled();     // rien n'est validé silencieusement

    service.validerCandidates.and.returnValue(of(espace([])));
    (el('[data-action="confirmer-candidates"]') as HTMLButtonElement).click();
    expect(service.validerCandidates).toHaveBeenCalledOnceWith('e-1', 'ex-1');
  });

  it('Note 35 : effectif inconnu → à déterminer ; effectif connu → règle de seuil affichée', () => {
    const effectif = { sectionCode: 'effectif', label: 'Effectif', sourceType: 'MANUAL', required: true, status: 'MISSING' as const, fields: [{ fieldCode: 'effectif', label: 'Effectif', type: 'INTEGER' as const, required: true, filled: false, value: null, comment: null, source: null }] };
    creer([note({ noteCode: '35', title: 'Informations sociales', applicabilityStatus: 'TO_DETERMINE', attached: false, automationType: 'MANUAL_REQUIRED', manualSections: [effectif] })], undefined, '35');
    expect(el('[data-champ="note35"]')!.innerText).toContain('Effectif non renseigné');
    expect(el('#champ-effectif-effectif')).not.toBeNull();         // saisissable même si la note n'est pas encore jointe

    const connu = { ...effectif, status: 'COMPLETE' as const, fields: [{ ...effectif.fields[0], filled: true, value: '251' }] };
    creer([note({ noteCode: '35', title: 'Informations sociales', applicabilityStatus: 'APPLICABLE', automationType: 'MANUAL_REQUIRED', manualSections: [connu] })], undefined, '35');
    expect(el('[data-champ="note35"]')!.innerText).toContain('Note obligatoire');

    // le verdict est celui du backend (statut d'applicabilité), jamais une comparaison faite dans Angular
    const connuNonApplicable = { ...effectif, status: 'COMPLETE' as const, fields: [{ ...effectif.fields[0], filled: true, value: '999' }] };
    creer([note({ noteCode: '35', title: 'Informations sociales', applicabilityStatus: 'NOT_APPLICABLE', attached: false, automationType: 'MANUAL_REQUIRED', manualSections: [connuNonApplicable] })], undefined, '35');
    expect(el('[data-champ="note35"]')!.innerText).toContain('Note non obligatoire');
  });

  it('applicabilité TO_DETERMINE : décision explicite Applicable / Non applicable avec motif et expectedVersion', () => {
    creer([note({ applicabilityStatus: 'TO_DETERMINE', attached: false })], undefined, '17');
    expect(el('[data-action="applicable"]')).not.toBeNull();
    expect(el('[data-action="non-applicable"]')).not.toBeNull();
    service.modifierApplicabilite.and.returnValue(of(note({ applicabilityStatus: 'NOT_APPLICABLE' })));
    saisir('[data-champ="motif-applicabilite"]', 'Sans objet');
    (el('[data-action="non-applicable"]') as HTMLButtonElement).click();
    expect(service.modifierApplicabilite).toHaveBeenCalledWith('e-1', 'ex-1', '17', 3, 'NOT_APPLICABLE', 'Sans objet');
  });

  it('NOT_APPLICABLE_CANDIDATE : « Ladini propose : Non applicable » et validation explicite requise', () => {
    creer([note({ applicabilityStatus: 'NOT_APPLICABLE_CANDIDATE', attached: false })], undefined, '17');
    expect(el('[data-zone="applicabilite"]')!.innerText).toContain('Ladini propose');
    expect(el('[data-zone="applicabilite"]')!.innerText).toContain('à valider explicitement');
  });

  it('note AUTOMATIC prête : données calculées en lecture seule, aucun champ à saisir', () => {
    creer([note({ noteCode: '23', title: 'Transports', automationType: 'AUTOMATIC', completionStatus: 'READY', manualSections: [], missingSections: [] })], undefined, '23');
    expect(el('[data-zone="donnees-calculees"]')!.innerText).toContain('300');
    expect(el('[data-zone="informations-a-completer"]')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('textarea, input[type="number"]').length).toBe(0);
  });

  it('formulaire généré depuis fields[] : DECIMAL, INTEGER, DATE, BOOLEAN et ENUM ont chacun leur contrôle', () => {
    const types = ['DECIMAL', 'INTEGER', 'DATE', 'BOOLEAN', 'ENUM'] as const;
    const sec = {
      sectionCode: 'divers', label: 'Divers', sourceType: 'MANUAL', required: true, status: 'MISSING' as const,
      fields: types.map((t) => ({ fieldCode: t.toLowerCase(), label: t, type: t, required: true, filled: false, value: null, comment: null, source: null, ...(t === 'ENUM' ? { options: ['A', 'B'] } : {}) })),
    };
    creer([note({ manualSections: [sec], missingSections: ['divers'] })], undefined, '17');
    const zone = el('[data-zone="informations-a-completer"]') as HTMLElement;
    expect(zone.querySelector('#champ-divers-decimal')).not.toBeNull();
    expect(zone.querySelector('#champ-divers-integer')).not.toBeNull();
    expect(zone.querySelector('#champ-divers-date')).not.toBeNull();
    expect(zone.querySelector('#champ-divers-boolean')).not.toBeNull();
    expect(zone.querySelector('#champ-divers-decimal')!.getAttribute('inputmode')).toBe('decimal');
    expect(zone.querySelector('#champ-divers-integer')!.getAttribute('type')).toBe('number');
    expect(zone.querySelector('#champ-divers-date')!.getAttribute('type')).toBe('date');
    expect(zone.querySelector('#champ-divers-boolean')!.tagName).toBe('SELECT');
    const enumChamp = zone.querySelector('#champ-divers-enum') as HTMLSelectElement;
    expect(enumChamp.tagName).toBe('SELECT');
    expect(Array.from(enumChamp.options).map((o) => o.value)).toEqual(['', 'A', 'B']);      // valeurs du référentiel backend
  });
});
