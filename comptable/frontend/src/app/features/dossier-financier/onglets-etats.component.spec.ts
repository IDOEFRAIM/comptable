import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { DetailLigneDto, EtatDto, ExceptionDossier, LigneEtatDto } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { OngletControlesComponent } from './onglet-controles.component';
import { OngletEtatComponent } from './onglet-etat.component';
import { OngletPourquoiComponent } from './onglet-pourquoi.component';

function ligne(code: string, ordre: number, p: Partial<LigneEtatDto> = {}): LigneEtatDto {
  return {
    code, label: `Libellé ${code}`, order: ordre, calculationType: 'MAPPED', verificationStatus: 'VERIFIED', provenanceType: 'DIRECT_PRIMARY',
    amountN: 1000, amountNMinus1: 400, variation: 600, grossN: null, depreciationN: null, notCalculableReason: null, accounts: [], complete: true, incompleteReasons: [], ...p,
  };
}

function etat(lignes: LigneEtatDto[], p: Partial<EtatDto> = {}): EtatDto {
  return {
    statementType: 'COMPTE_RESULTAT', framework: 'SYSCOHADA', version: '2', definitionHash: 'h', exerciceId: 'ex-1', exerciceDebut: '2026-01-01', exerciceFin: '2026-12-31',
    exercicePrecedentId: 'ex-0', precedentDebut: '2025-01-01', precedentFin: '2025-12-31', status: 'READY', statusReasons: [], lines: lignes, unmapped: [], ambiguous: [], controls: [], ...p,
  };
}

describe('OngletEtatComponent (Compte de résultat, Bilan, TFT)', () => {
  let fixture: ComponentFixture<OngletEtatComponent>;
  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  function ouvrir(mode: 'CR' | 'ACTIF' | 'PASSIF' | 'TFT', e: EtatDto, controle: { totalActif: number; totalPassif: number; ecart: number; equilibre: boolean } | null = null): void {
    TestBed.configureTestingModule({ imports: [OngletEtatComponent] });
    fixture = TestBed.createComponent(OngletEtatComponent);
    fixture.componentRef.setInput('etat', e);
    fixture.componentRef.setInput('mode', mode);
    fixture.componentRef.setInput('titre', 'Test');
    fixture.componentRef.setInput('controle', controle);
    fixture.detectChanges();
  }

  it('Compte de résultat : N, N-1 et variation viennent du backend (aucun calcul), soldes XA…XI distingués, regroupements', () => {
    ouvrir('CR', etat([ligne('XA', 1, { amountN: 0.3, amountNMinus1: 0.1, variation: 0.2 }), ligne('XF', 2), ligne('XH', 3), ligne('XI', 4)]));
    expect(el('[data-ligne="XA"]')!.innerText.replace(/\s+/g, ' ')).toContain('0,3');
    expect(el('[data-ligne="XA"]')!.innerText).toContain('0,2');              // variation affichée telle que fournie
    for (const code of ['XA', 'XF', 'XH', 'XI']) {
      expect(el(`[data-ligne="${code}"]`)!.classList).toContain('solde');
    }
    const t = (fixture.nativeElement as HTMLElement).innerText.toLowerCase();
    expect(t).toContain('exploitation');
    expect(t).toContain('financier');
    expect(t).toContain('hao');
    expect(t).toContain('résultat net');
  });

  it('une variation inconnue reste vide, jamais zéro', () => {
    ouvrir('CR', etat([ligne('XA', 1, { amountNMinus1: null, variation: null })]));
    const cellules = el('[data-ligne="XA"]')!.querySelectorAll('td');
    expect(cellules[cellules.length - 2].textContent!.trim()).toBe('');        // dernière colonne = menu
    expect(cellules[cellules.length - 2].textContent).not.toContain('0');
  });

  it('une ligne incomplète affiche « Incomplet » avec sa cause, jamais 0 ; une ligne non calculable affiche « Non déterminé »', () => {
    ouvrir('ACTIF', etat([
      ligne('AK', 1, { amountN: 0, complete: false, incompleteReasons: ['Mapping réglementaire incomplet'] }),
      ligne('AL', 2, { amountN: null, notCalculableReason: 'Aucune donnée' }),
    ], { status: 'INCOMPLETE' }));
    const ak = el('[data-ligne="AK"]')!;
    expect(el('[data-champ="montant-AK"]')!.innerText).toBe('Incomplet');
    expect(ak.innerText).toContain('Mapping réglementaire incomplet');
    expect(el('[data-champ="montant-AL"]')!.innerText).toBe('Non déterminé');
    expect(el('[data-ligne="AK"] app-status, app-status')).not.toBeNull();       // le statut INCOMPLETE n'est pas masqué
  });

  it('Actif : colonnes brut, amortissements, net N et net N-1 ; contrôle BZ/DZ équilibré', () => {
    ouvrir('ACTIF', etat([ligne('AD', 1, { grossN: 1500, depreciationN: 500, amountN: 1000 })], { statementType: 'BILAN_ACTIF' }), { totalActif: 1000, totalPassif: 1000, ecart: 0, equilibre: true });
    const entetes = (fixture.nativeElement as HTMLElement).querySelector('thead')!.innerText.toLowerCase();
    expect(entetes).toContain('brut n');
    expect(entetes).toContain('net n-1');
    expect(el('[data-champ="verdict-equilibre"]')!.innerText).toContain('Équilibré');
  });

  it('un écart BZ/DZ est affiché sans tolérance silencieuse', () => {
    ouvrir('ACTIF', etat([ligne('AD', 1)], { statementType: 'BILAN_ACTIF' }), { totalActif: 1000, totalPassif: 985, ecart: 15, equilibre: false });
    expect(el('[data-champ="verdict-equilibre"]')!.innerText).toContain('Écart de 15');
    expect(el('[data-champ="verdict-equilibre"] [data-statut="BLOCKED"]')).not.toBeNull();
  });

  it('comptes empêchant la finalisation : numéro, solde et code UNMAPPED_ACCOUNT', () => {
    ouvrir('ACTIF', etat([ligne('AD', 1)], { status: 'INCOMPLETE', unmapped: [{ accountId: 'c-1', number: '404', label: 'Fournisseurs d’immobilisations', balanceN: 250000 }] }));
    const zone = el('[data-zone="comptes-bloquants"]')!.innerText;
    expect(zone).toContain('404');
    expect(zone).toContain('250');
    expect(zone).toContain('UNMAPPED_ACCOUNT');
  });

  it('TFT : sections et codes, cause précise d’une ligne incomplète, ouverture du « Pourquoi ? »', () => {
    ouvrir('TFT', etat([
      ligne('ZA', 1), ligne('FA', 2, { amountN: 0, complete: false, incompleteReasons: ['CAFG non déterminée : compte 67900 détecté'] }), ligne('FF', 3), ligne('ZH', 4),
    ], { statementType: 'TFT', status: 'INCOMPLETE' }));
    const t = (fixture.nativeElement as HTMLElement).innerText.toLowerCase();
    expect(t).toContain('investissements');
    expect(t).toContain('variation de trésorerie');
    expect(el('[data-ligne="FA"]')!.innerText).toContain('CAFG non déterminée');
    expect(el('[data-champ="montant-FA"]')!.innerText).toBe('Incomplet');
    let emis: unknown = null;
    fixture.componentInstance.pourquoi.subscribe((v) => (emis = v));
    (el('[data-action="menu-FA"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el('[data-action="pourquoi-FA"]') as HTMLButtonElement).click();
    expect(emis).toEqual({ type: 'TFT', code: 'FA' });
  });

  it('N-1 : sans exercice précédent réel, la colonne affiche « – » et la période N-1 n’est pas inventée', () => {
    ouvrir('CR', etat([ligne('XA', 1, { amountNMinus1: null, variation: null })], { exercicePrecedentId: null, precedentDebut: null, precedentFin: null }));
    expect((fixture.nativeElement as HTMLElement).innerText).toContain('pas d’exercice précédent');
    expect(el('[data-ligne="XA"]')!.innerText).toContain('–');
  });

  it('N-1 : les dates affichées sont celles du vrai exercice précédent fournies par le backend', () => {
    ouvrir('CR', etat([ligne('XA', 1)], { precedentDebut: '2024-07-01', precedentFin: '2025-12-31' }));
    expect(el('.periode')!.innerText).toContain('2024-07-01');
    expect(el('.periode')!.innerText).toContain('2025-12-31');
  });
});

describe('OngletControlesComponent (anomalies et qualification des flux)', () => {
  let service: jasmine.SpyObj<DossierFinancierService>;
  let fixture: ComponentFixture<OngletControlesComponent>;
  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  const flux: ExceptionDossier = {
    id: 'u-1', code: 'UNQUALIFIED_CASH_EVENT', severity: 'BLOCKING', category: 'FLUX_NON_QUALIFIE', title: 'Flux de trésorerie non qualifié — EC-2026-00452', detail: '', tab: 'TFT',
    target: { ecritureId: 'ec-1', numeroPiece: 'EC-2026-00452', date: '2026-03-01', tresorerie: 700, lignesTft: ['FK'], lignes: [{ compte: '521', libelleCompte: 'Banque', debit: 700, credit: 0 }, { compte: '101', libelleCompte: 'Capital', debit: 0, credit: 700 }] },
  };

  function ouvrir(exceptions: ExceptionDossier[], prepare = true): void {
    service.evenementsTresorerie.and.returnValue(of([
      { code: 'CAPITAL_CASH_INCREASE', libelle: 'Augmentation de capital', ligneTft: 'FK', encaissement: true },
      { code: 'NO_TFT_FLOW', libelle: 'Aucun flux TFT', ligneTft: null, encaissement: false },
    ]));
    TestBed.configureTestingModule({ imports: [OngletControlesComponent], providers: [provideRouter([]), { provide: DossierFinancierService, useValue: service }] });
    fixture = TestBed.createComponent(OngletControlesComponent);
    fixture.componentRef.setInput('entrepriseId', 'e-1');
    fixture.componentRef.setInput('exceptions', exceptions);
    fixture.componentRef.setInput('permissions', { prepare, review: false, finalise: false });
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<DossierFinancierService>('DossierFinancierService', ['evenementsTresorerie', 'qualifier']);
  });

  it('aucune anomalie : état vide explicite', () => {
    ouvrir([]);
    expect(el('[data-champ="aucune-anomalie"]')).not.toBeNull();
  });

  it('un flux non qualifié propose les événements du backend et envoie la sélection ; le serveur reste l’autorité', () => {
    ouvrir([flux]);
    expect((el('[data-action="qualifier"]') as HTMLButtonElement).disabled).toBeTrue();
    const select = el('[data-champ="evenement-u-1"]') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['', 'CAPITAL_CASH_INCREASE', 'NO_TFT_FLOW']);
    select.value = 'CAPITAL_CASH_INCREASE';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    service.qualifier.and.returnValue(of({ ecritureId: 'ec-1', numeroPiece: 'EC-2026-00452', cashFlowEvent: 'CAPITAL_CASH_INCREASE' }));
    let modifiee = false;
    fixture.componentInstance.modifiee.subscribe(() => (modifiee = true));
    (el('[data-action="qualifier"]') as HTMLButtonElement).click();
    expect(service.qualifier).toHaveBeenCalledWith('e-1', 'ec-1', 'CAPITAL_CASH_INCREASE');
    expect(modifiee).toBeTrue();                                    // le parent recharge la synthèse : l’anomalie ne disparaît que si le backend la résout
  });

  it('un refus du serveur (422) est affiché et rien n’est considéré comme résolu', () => {
    ouvrir([flux]);
    const select = el('[data-champ="evenement-u-1"]') as HTMLSelectElement;
    select.value = 'CAPITAL_CASH_INCREASE';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    service.qualifier.and.returnValue(throwError(() => ({ error: { message: 'Evenement refuse : incoherent avec les lignes' } })));
    (el('[data-action="qualifier"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-champ="erreur-qualification"]')!.innerText).toContain('incoherent');
    expect((fixture.nativeElement as HTMLElement).innerText).not.toContain('Traités pendant cette session');
  });

  it('sans droit de préparation (CLIENT), aucune qualification n’est proposée', () => {
    ouvrir([flux], false);
    expect(el('[data-action="qualifier"]')).toBeNull();
    expect(el('[data-zone="qualification"]')!.innerText).toContain('réservée');
  });

  it('anomalies par catégorie : les bloquants d’abord, la note incomplète ouvre la note', () => {
    ouvrir([
      { id: 'n-1', code: 'REQUIRED_NOTE_INCOMPLETE', severity: 'WARNING', category: 'NOTE_INCOMPLETE', title: 'Note 17 incomplète', detail: '', tab: 'NOTES', target: { noteCode: '17' } },
      { id: 'u-2', code: 'UNMAPPED_ACCOUNT', severity: 'BLOCKING', category: 'COMPTE_NON_MAPPE', title: 'Compte 404 non mappé', detail: '', tab: 'BILAN', target: {} },
    ]);
    const titres = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.item__titre')).map((n) => (n as HTMLElement).innerText);
    expect(titres).toEqual(['Compte 404 non mappé', 'Note 17 incomplète']);
    let cible: unknown = null;
    fixture.componentInstance.aller.subscribe((v) => (cible = v));
    (el('[data-action="ouvrir-note"]') as HTMLButtonElement).click();
    expect(cible).toEqual({ onglet: 'NOTES', note: '17' });
  });
});

describe('OngletPourquoiComponent (drill-down)', () => {
  let service: jasmine.SpyObj<DossierFinancierService>;
  let fixture: ComponentFixture<OngletPourquoiComponent>;
  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);

  const detail: DetailLigneDto = {
    statementType: 'COMPTE_RESULTAT', lineCode: 'TA', exerciceId: 'ex-1',
    accounts: [{ accountId: 'c-1', number: '701', label: 'Ventes', role: 'PRIMARY', amountN: 5000, amountNMinus1: null }],
    account: null, components: [],
  };

  function ouvrir(selection: { type: 'COMPTE_RESULTAT' | 'TFT'; code: string } | null): void {
    TestBed.configureTestingModule({ imports: [OngletPourquoiComponent], providers: [{ provide: DossierFinancierService, useValue: service }] });
    fixture = TestBed.createComponent(OngletPourquoiComponent);
    fixture.componentRef.setInput('entrepriseId', 'e-1');
    fixture.componentRef.setInput('exerciceId', 'ex-1');
    fixture.componentRef.setInput('selection', selection);
    fixture.detectChanges();
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<DossierFinancierService>('DossierFinancierService', ['detailLigne']);
  });

  it('sans sélection : invitation à ouvrir une ligne', () => {
    ouvrir(null);
    expect((fixture.nativeElement as HTMLElement).innerText).toContain('Aucune ligne sélectionnée');
    expect(service.detailLigne).not.toHaveBeenCalled();
  });

  it('ligne → comptes → écritures : chaque niveau est demandé au backend à la demande, une seule requête par niveau', () => {
    service.detailLigne.and.returnValue(of(detail));
    ouvrir({ type: 'COMPTE_RESULTAT', code: 'TA' });
    expect(service.detailLigne).toHaveBeenCalledOnceWith('e-1', 'ex-1', 'COMPTE_RESULTAT', 'TA', undefined);
    expect(el('[data-compte="701"]')!.innerText).toContain('Ventes');

    service.detailLigne.and.returnValue(of({
      ...detail,
      account: { accountId: 'c-1', number: '701', label: 'Ventes', opening: 0, movementDebit: 0, movementCredit: 5000, closing: 5000,
        entries: [{ ligneId: 'l-1', ecritureId: 'ec-9', date: '2026-02-01', journal: 'VT', numeroPiece: 'F-001', libelle: 'Facture F-001', debit: 0, credit: 5000, lineage: [{ sourceType: 'FACTURE', sourceEvent: 'COMPTABILISEE', sourceId: 'f-1' }] }] },
    } as DetailLigneDto));
    (Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.innerText.includes('Voir les écritures')) as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.detailLigne).toHaveBeenCalledWith('e-1', 'ex-1', 'COMPTE_RESULTAT', 'TA', 'c-1');
    const t = el('[data-zone="ecritures-compte"]')!.innerText;
    expect(t).toContain('F-001');
    expect(t).toContain('FACTURE');                                  // origine / lineage
  });

  it('TFT : les écritures et événements de trésorerie sont affichés, pas de détail par compte', () => {
    service.detailLigne.and.returnValue(of({ ...detail, statementType: 'TFT', accounts: [],
      components: [{ ligneTft: 'FK', ecritureId: 'ec-1', numeroPiece: 'EC-1', date: '2026-03-01', evenement: 'CAPITAL_CASH_INCREASE', montant: 700, lineage: [] }] } as unknown as DetailLigneDto));
    ouvrir({ type: 'TFT', code: 'FK' });
    const t = (fixture.nativeElement as HTMLElement).innerText;
    expect(t).toContain('CAPITAL_CASH_INCREASE');
    expect(t).toContain('Saisie manuelle');
    expect((Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).some((b) => b.innerText.includes('Voir les écritures')))).toBeFalse();
  });

  it('une erreur du serveur est affichée', () => {
    service.detailLigne.and.returnValue(throwError(() => ({ error: { message: 'Ligne inconnue' } })));
    ouvrir({ type: 'COMPTE_RESULTAT', code: 'ZZ' });
    expect((fixture.nativeElement as HTMLElement).innerText).toContain('Ligne inconnue');
  });
});
