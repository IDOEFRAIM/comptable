import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { Ajustement, Controles, Declaration, LigneTva, Origine, Paiement, Periode, Progression, Reglement } from './tva.model';
import { TvaComponent } from './tva.component';
import { TvaService } from './tva.service';

function periode(s: Partial<Periode> = {}): Periode {
  return {
    id: 'p-1', entrepriseId: 'e-1', periodStart: '2026-10-01', periodEnd: '2026-10-31', status: 'PREPARING',
    framework: { id: 'c-1', jurisdiction: 'CI', version: 'TEST-1', status: 'VERIFIED', verifie: true, definitionHash: 'h', sourceReferences: ['source'], effectiveFrom: '2000-01-01', effectiveTo: null },
    totals: { outputBase: 1500, outputVatAccounted: 150, outputVatFiscal: 150, inputBase: 400, inputVatAccounted: 40, inputVatDeductible: 40, adjustmentsOutput: 3, adjustmentsInput: 0, declaredOutput: 153, declaredInput: 40,
      netDeclared: 113, netAccounted: 110, unexplained: 0, toReview: 1, operations: 3, fiscalComplete: true },
    reconciliation: { ledgerOutput: 150, ledgerInput: 40, operationsOutput: 150, operationsInput: 40, ledgerGap: 0 },
    basis: 'FISCAL_VERIFIED', officialStructure: false, anomalies: 1, toReview: 1, finalisable: false, finalisationBlockers: ['1 opération(s) à revoir'],
    permissions: { prepare: true, review: false, finalise: false }, snapshotAvailable: false,
    version: 7, regulatoryPackVerified: true, finalisationReasons: [], progress: progression(), payment: reglement(), credit: { openingAmount: null, reason: null, source: null, previousPeriodId: null, createdAt: null,
      openingState: 'NONE', closingAmount: null, closingState: 'NONE' }, deadline: null, ...s,
  };
}

function progression(s: Partial<Progression> = {}): Progression {
  return {
    status: 'EN_PREPARATION', readySteps: 3, totalSteps: 8, blockingSteps: [],
    steps: [{ code: 'OPERATIONS_CHARGEES', label: 'Opérations chargées', ready: true, blocking: false }, { code: 'TRAITEMENTS_DECIDES', label: 'Traitements décidés', ready: false, blocking: true },
      { code: 'CONTROLES', label: 'Contrôles', ready: true, blocking: false }], ...s,
  };
}

function reglement(s: Partial<Reglement> = {}): Reglement {
  return { declaredAmount: null, paidAmount: 0, remainingAmount: null, status: 'NON_FINALISE', paymentOpen: false, payments: [], ...s };
}

function paiement(s: Partial<Paiement> = {}): Paiement {
  return { id: 'pay-1', amount: 20, paymentDate: '2026-11-02', reference: 'VIR-1', source: 'BANK_STATEMENT', status: 'ACTIVE', createdAt: '2026-11-02T10:00:00Z', reversedAt: null, reverseReason: null, ...s };
}

function ligne(s: Partial<LigneTva> = {}): LigneTva {
  return {
    ecritureId: 'ec-1', date: '2026-10-03', piece: 'F1', document: 'F1', tiers: 'Client A', libelle: 'Vente', side: 'OUTPUT', treatment: 'OUTPUT_TAX', treatmentSource: 'DERIVED', base: 1000,
    impliedRatePercent: 10, rateCode: 'T10', vatAccounted: 100, vatFiscal: 100, vatDeductible: null, ecart: 0, explication: 'NONE', status: 'OK', issues: [], ...s,
  };
}

const ORIGINE: Origine = {
  operation: ligne({ status: 'TO_REVIEW', treatment: 'TO_REVIEW', issues: ['TAX_TREATMENT_MISSING'], side: 'UNDETERMINED' }),
  ecritureLignes: [{ compte: '443900', intitule: 'TVA collectée', libelle: 'l', debit: 0, credit: 100 }],
  facture: null,
  documents: [],
};

describe('TvaComponent (espace TVA)', () => {
  let service: jasmine.SpyObj<TvaService>;
  let fixture: ComponentFixture<TvaComponent>;

  async function ouvrir(p: Periode = periode(), lignes: LigneTva[] = [ligne()]): Promise<void> {
    service.periodes.and.returnValue(of([{ id: p.id, periodStart: p.periodStart, periodEnd: p.periodEnd, status: p.status, frameworkVersion: 'TEST-1', frameworkVerified: true, version: p.version }]));
    service.periode.and.returnValue(of(p));
    service.operations.and.callFake((_e, _p, f) => of({ items: lignes.filter((l) => f.cote !== 'PURCHASES' || l.side === 'INPUT'), page: 0, taille: 25, total: f.statut === 'TO_REVIEW' ? lignes.filter((l) => l.status === 'TO_REVIEW').length : lignes.length }));
    service.ajustements.and.returnValue(of([]));
    service.controles.and.returnValue(of({ anomalies: [], reconciliation: p.reconciliation, unexplained: 0 }));
    TestBed.configureTestingModule({
      imports: [TvaComponent],
      providers: [{ provide: TvaService, useValue: service }, { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e-1' })) } }],
    });
    fixture = TestBed.createComponent(TvaComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Variante synchrone (fakeAsync) de {@link ouvrir}. */
  function ouvrirSync(p: Periode = periode(), lignes: LigneTva[] = [ligne()]): void {
    service.periodes.and.returnValue(of([{ id: p.id, periodStart: p.periodStart, periodEnd: p.periodEnd, status: p.status, frameworkVersion: 'TEST-1', frameworkVerified: true, version: p.version }]));
    service.periode.and.returnValue(of(p));
    service.operations.and.callFake(() => of({ items: lignes, page: 0, taille: 25, total: lignes.length }));
    service.ajustements.and.returnValue(of([]));
    service.controles.and.returnValue(of({ anomalies: [], reconciliation: p.reconciliation, unexplained: 0 }));
    TestBed.configureTestingModule({
      imports: [TvaComponent],
      providers: [{ provide: TvaService, useValue: service }, { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e-1' })) } }],
    });
    fixture = TestBed.createComponent(TvaComponent);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  function ouvrirOngletSync(code: string): void {
    (el(`[data-onglet="${code}"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  const el = (sel: string): HTMLElement | null => (fixture.nativeElement as HTMLElement).querySelector(sel);
  const texte = (sel: string): string => el(sel)!.innerText;
  async function onglet(code: string): Promise<void> {
    (el(`[data-onglet="${code}"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<TvaService>('TvaService', ['periodes', 'ouvrir', 'periode', 'operations', 'origine', 'decider', 'traitementGroupe', 'ajustements', 'ajouterAjustement', 'annulerAjustement', 'controles', 'declaration', 'action',
      'paiements', 'enregistrerPaiement', 'annulerPaiement', 'saisirCredit']);
  });

  it('vue d\'ensemble : collectée, déductible, ajustements, solde net et opérations à revoir, tels que calculés par le backend', async () => {
    await ouvrir();
    expect(texte('[data-champ="tva-collectee"]')).toBe('153');
    expect(texte('[data-champ="tva-deductible"]')).toBe('40');
    expect(texte('[data-champ="ajustements"]')).toBe('3');
    expect(texte('[data-champ="solde"]')).toBe('113');
    expect(texte('[data-champ="a-revoir"]')).toBe('1');
    expect(texte('[data-zone="rapprochement"]')).toContain('150');
    expect(texte('[data-zone="statut-periode"]')).toContain('En préparation');
    expect(el('[data-zone="bloqueurs"]')!.innerText).toContain('opération(s) à revoir');
    expect(el('[data-zone="bandeau-non-verifie"]')).toBeNull();
  });

  it('sans cadre vérifié : bandeau explicite, montants comptabilisés non déclarables', async () => {
    await ouvrir(periode({ basis: 'ACCOUNTED_UNVERIFIED', framework: null }));
    expect(texte('[data-zone="bandeau-non-verifie"]')).toContain('Aucun cadre fiscal vérifié');
    expect(texte('[data-zone="bandeau-non-verifie"]')).toContain('non déclarables');
    expect(texte('[data-zone="vue-ensemble"]')).not.toContain('NaN');
  });

  it('ventes : tableau Date / Document / Client / Base / Taux / TVA / Traitement / Statut', async () => {
    await ouvrir(periode(), [ligne(), ligne({ ecritureId: 'ec-2', document: 'F2', status: 'TO_REVIEW', issues: ['VAT_AMOUNT_MISMATCH'], treatment: 'OUTPUT_TAX' })]);
    await onglet('VENTES');
    const t = texte('[data-zone="ventes"]');
    for (const attendu of ['Date', 'Document', 'Client', 'Base', 'Taux', 'TVA', 'Traitement fiscal', 'Statut', 'F1', 'Client A', 'T10', 'TVA collectée', 'OK']) {
      expect(t.toLowerCase()).toContain(attendu.toLowerCase());                     // les en-têtes sont mis en capitales par la feuille de style
    }
    expect(el('[data-ligne="ec-2"]')!.innerText).toContain('TVA différente du calcul');
    expect(el('[data-ligne="ec-2"]')!.classList).toContain('a-revoir');
  });

  it('achats : TVA comptable et TVA déductible distinguées, traitement décidé signalé', async () => {
    const achat = ligne({ ecritureId: 'ac-1', side: 'INPUT', treatment: 'PARTIALLY_DEDUCTIBLE', treatmentSource: 'DECISION', vatAccounted: 40, vatDeductible: 20, rateCode: 'T10' });
    service.periodes.and.returnValue(of([]));
    await ouvrir(periode(), [achat]);
    service.operations.and.returnValue(of({ items: [achat], page: 0, taille: 25, total: 1 }));
    await onglet('ACHATS');
    const t = texte('[data-zone="achats"]');
    expect(t.toLowerCase()).toContain('fournisseur');
    expect(t.toLowerCase()).toContain('tva comptable');
    expect(t.toLowerCase()).toContain('tva déductible');
    expect(el('[data-ligne="ac-1"]')!.innerText).toContain('Partiellement déductible');
    expect(el('[data-ligne="ac-1"]')!.innerText).toContain('décidé');
    expect(el('[data-ligne="ac-1"]')!.innerText).toContain('20');
  });

  it('TO_REVIEW : l\'opération s\'ouvre avec son origine, la décision exige un traitement et une justification', async () => {
    const aRevoir = ligne({ ecritureId: 'ec-9', status: 'TO_REVIEW', treatment: 'TO_REVIEW', issues: ['TAX_TREATMENT_MISSING'], side: 'UNDETERMINED' });
    await ouvrir(periode(), [aRevoir]);
    await onglet('VENTES');
    service.origine.and.returnValue(of({ ...ORIGINE, operation: { ...ORIGINE.operation, ecritureId: 'ec-9' } }));
    (el('[data-ligne="ec-9"] [data-action="traiter"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.origine).toHaveBeenCalledWith('e-1', 'p-1', 'ec-9');
    expect(texte('[data-zone="origine"]')).toContain('443900');
    const bouton = (): HTMLButtonElement => el('[data-action="enregistrer-traitement"]') as HTMLButtonElement;
    expect(bouton().disabled).toBeTrue();
    const select = el('[data-champ="traitement-select"]') as HTMLSelectElement;
    select.value = 'OUT_OF_SCOPE';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(bouton().disabled).toBeTrue();                                             // pas de justification
    const motif = el('[data-champ="traitement-motif"]') as HTMLTextAreaElement;
    motif.value = 'Hors champ selon le dossier client';
    motif.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(bouton().disabled).toBeFalse();
    service.decider.and.returnValue(of(periode({ toReview: 0, anomalies: 0 })));
    bouton().click();
    expect(service.decider).toHaveBeenCalledWith('e-1', 'p-1', 'ec-9', { treatment: 'OUT_OF_SCOPE', deductiblePercent: null, reason: 'Hors champ selon le dossier client', expectedVersion: 7 });
  });

  it('les traitements proposés dépendent du sens de la TVA comptabilisée (aucun traitement incompatible)', async () => {
    const vente = ligne({ ecritureId: 'ec-1', status: 'TO_REVIEW', side: 'OUTPUT' });
    await ouvrir(periode(), [vente]);
    await onglet('VENTES');
    service.origine.and.returnValue(of({ ...ORIGINE, operation: vente }));
    (el('[data-ligne="ec-1"] [data-action="traiter"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const valeurs = Array.from((el('[data-champ="traitement-select"]') as HTMLSelectElement).options).map((o) => o.value);
    expect(valeurs).toContain('OUTPUT_TAX');
    expect(valeurs).not.toContain('INPUT_TAX');
    expect(valeurs).not.toContain('NON_DEDUCTIBLE');
  });

  it('ajustements : liste, justification obligatoire, annulation (jamais une suppression)', async () => {
    const a: Ajustement = { id: 'a-1', kind: 'CORRECTION', effect: 'OUTPUT', amount: 3, reason: 'Arrondi accepté', evidence: null, ecritureId: null, createdAt: '2026-10-05T10:00:00Z', cancelled: false, cancelReason: null };
    await ouvrir();
    service.ajustements.and.returnValue(of([a, { ...a, id: 'a-2', cancelled: true, cancelReason: 'Erreur' }]));
    await onglet('AJUSTEMENTS');
    expect(texte('[data-zone="ajustements"]')).toContain('Arrondi accepté');
    expect(el('[data-ajustement="a-2"]')!.innerText).toContain('annulé : Erreur');
    expect(el('[data-ajustement="a-2"] [data-action="annuler-ajustement"]')).toBeNull();
    service.annulerAjustement.and.returnValue(of(periode()));
    (el('[data-ajustement="a-1"] [data-action="annuler-ajustement"]') as HTMLButtonElement).click();
    expect(service.annulerAjustement).toHaveBeenCalledWith('e-1', 'p-1', 'a-1', jasmine.any(String), 7);
  });

  it('ajustements : le formulaire n\'est disponible qu\'avec le droit de préparer', async () => {
    await ouvrir(periode({ permissions: { prepare: false, review: false, finalise: false } }));
    await onglet('AJUSTEMENTS');
    expect(el('[data-action="nouvel-ajustement"]')).toBeNull();
    expect((el('[data-action="recalculer"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(el('[data-action="preparer"]')).toBeNull();
  });

  it('contrôles : anomalies lisibles (jamais des codes bruts), état vide explicite', async () => {
    await ouvrir();
    const c: Controles = { anomalies: [{ code: 'VAT_AMOUNT_MISMATCH', ecritureId: 'ec-1', key: null, message: 'Écriture F1 : TVA comptabilisée 103 ≠ TVA calculée 100' }], reconciliation: periode().reconciliation, unexplained: 3 };
    service.controles.and.returnValue(of(c));
    await onglet('CONTROLES');
    expect(el('[data-anomalie="VAT_AMOUNT_MISMATCH"]')!.innerText).toContain('TVA différente du calcul');
    expect(el('[data-anomalie="VAT_AMOUNT_MISMATCH"]')!.innerText).toContain('103');
    service.controles.and.returnValue(of({ anomalies: [], reconciliation: periode().reconciliation, unexplained: 0 }));
    await onglet('VUE');
    await onglet('CONTROLES');
    expect(texte('[data-champ="aucune-anomalie"]')).toContain('Aucune anomalie');
  });

  it('déclaration : structure générique signalée non officielle, montants du backend', async () => {
    await ouvrir();
    const d: Declaration = { id: 'd-1', status: 'DRAFT', basis: 'FISCAL_VERIFIED', officialStructure: false, framework: null, lines: [{ code: 'TVA_COLLECTEE', label: 'TVA collectée', source: 'OUTPUT_VAT', amount: 153 },
      { code: 'TVA_NETTE', label: 'Solde de TVA', source: 'NET_VAT', amount: 113 }], reviewedAt: null, finalizedAt: null, snapshotId: null, snapshotSha256: null, finalisable: false, finalisationBlockers: [], finalisationReasons: [] };
    service.declaration.and.returnValue(of(d));
    await onglet('DECLARATION');
    expect(texte('[data-zone="declaration"]')).toContain('non officielle');
    expect(el('[data-ligne-declaration="TVA_NETTE"]')!.innerText).toContain('113');
  });

  it('revue et finalisation : réservées à l\'expert, finalisation derrière une confirmation d\'immuabilité', async () => {
    await ouvrir(periode({ status: 'READY_FOR_REVIEW', toReview: 0, anomalies: 0, finalisationBlockers: [], permissions: { prepare: true, review: true, finalise: true } }));
    expect(el('[data-action="revoir"]')).not.toBeNull();
    const revue = periode({ status: 'REVIEWED', finalisable: true, finalisationBlockers: [], toReview: 0, anomalies: 0, permissions: { prepare: true, review: true, finalise: true } });
    service.action.and.returnValue(of(revue));
    service.periode.and.returnValue(of(revue));                                       // le rechargement qui suit l'action renvoie la période revue
    (el('[data-action="revoir"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.action).toHaveBeenCalledWith('e-1', 'p-1', 'revoir', 7);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el('[data-action="finaliser"]')).not.toBeNull();
    (el('[data-action="finaliser"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(texte('[data-zone="confirmation-finalisation"]')).toContain('snapshot fiscal immuable');
  });

  it('un collaborateur ne voit ni la revue ni la finalisation ; la préparation est proposée', async () => {
    await ouvrir(periode({ status: 'PREPARING', permissions: { prepare: true, review: false, finalise: false } }));
    expect(el('[data-action="preparer"]')).not.toBeNull();
    expect(el('[data-action="revoir"]')).toBeNull();
    expect(el('[data-action="finaliser"]')).toBeNull();
  });

  it('période finalisée : plus aucune action de modification, statut Finalisée', async () => {
    await ouvrir(periode({ status: 'FINAL', anomalies: 0, toReview: 0, finalisationBlockers: [], permissions: { prepare: true, review: true, finalise: true } }));
    expect(texte('[data-zone="statut-periode"]')).toContain('Finalisée');
    expect((el('[data-action="recalculer"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(el('[data-action="preparer"]')).toBeNull();
    expect(el('[data-action="revoir"]')).toBeNull();
    expect(el('[data-action="finaliser"]')).toBeNull();
    expect(el('[data-action="rouvrir"]')).toBeNull();
  });

  it('une erreur du backend (par ex. finalisation refusée) est affichée telle quelle', async () => {
    await ouvrir(periode({ status: 'PREPARING' }));
    service.action.and.returnValue(throwError(() => ({ error: { message: 'La période n\'est pas prête pour la revue : 1 opération(s) à revoir' } })));
    (el('[data-action="preparer"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(texte('[data-zone="erreur-tva"]')).toContain('pas prête pour la revue');
  });

  it('aucune période : état vide explicite et ouverture d\'une période', async () => {
    service.periodes.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [TvaComponent],
      providers: [{ provide: TvaService, useValue: service }, { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e-1' })) } }],
    });
    fixture = TestBed.createComponent(TvaComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(texte('[data-champ="aucune-periode"]')).toContain('Aucune période TVA');
    (el('[data-action="nouvelle-periode"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-zone="form-periode"]')).not.toBeNull();
  });

  // ------------------------------------------------------------------------------------------------------------------------------ G3A : infrastructure

  it('cadre non vérifié : explication claire (préparable et contrôlable, pas finalisable), sans alerte sur tout l\'écran ; statut opérationnel et progression du backend', async () => {
    await ouvrir(periode({ basis: 'ACCOUNTED_UNVERIFIED', framework: null, regulatoryPackVerified: false, finalisationReasons: ['REGULATORY_PACK_NOT_VERIFIED'],
      progress: progression({ status: 'PRET_POUR_REVUE', readySteps: 5 }) }));
    expect(texte('[data-champ="explication-cadre"]')).toContain('peut être préparée et contrôlée');
    expect(texte('[data-champ="explication-cadre"]')).toContain('ne peut pas être finalisée tant qu’un cadre réglementaire vérifié n’est pas installé');
    expect(texte('[data-champ="cadre-reglementaire"]')).toContain('non vérifié');
    expect(el('[data-zone="erreur-tva"]')).toBeNull();                                // pas d'alerte rouge permanente
    expect(texte('[data-champ="statut-operationnel"]')).toContain('Prête pour revue');
    expect(texte('[data-champ="statut-operationnel"]')).toContain('5 / 8 étapes prêtes');
    expect(el('[data-raison="REGULATORY_PACK_NOT_VERIFIED"]')!.innerText).toContain('Aucun cadre réglementaire vérifié');
    expect(el('[data-action="finaliser"]')).toBeNull();                               // jamais proposé sans cadre vérifié
  });

  it('progression : huit étapes nommées, jamais un pourcentage ; échéance issue du cadre ou « non fournie »', async () => {
    await ouvrir(periode({ deadline: '2026-12-07' }));
    expect(texte('[data-zone="progression"]')).toContain('Opérations chargées');
    expect(texte('[data-zone="progression"]')).not.toContain('%');
    expect(el('[data-etape="TRAITEMENTS_DECIDES"]')!.getAttribute('data-prete')).toBe('false');
    expect(texte('[data-champ="echeance"]')).toContain('2026-12-07');
    TestBed.resetTestingModule();
    await ouvrir(periode({ deadline: null }));
    expect(texte('[data-champ="echeance"]')).toContain('non fournie');
  });

  it('listes paginées côté serveur : page X / N, total, précédent / suivant, jamais un filtrage local', async () => {
    await ouvrir();
    service.operations.and.returnValue(of({ items: [ligne()], page: 1, taille: 25, total: 130 }));
    await onglet('VENTES');
    expect(texte('[data-champ="pagination-info"]')).toContain('26–50 sur 130');
    expect(texte('[data-champ="pagination-info"]')).toContain('page 2 / 6');
    service.operations.calls.reset();
    (Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.pagination button')).find((b) => (b as HTMLElement).innerText === 'Suivant') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.operations).toHaveBeenCalledWith('e-1', 'p-1', jasmine.objectContaining({ cote: 'SALES' }), 2, 25);
  });

  it('recherche, filtres et tri sont envoyés au serveur (la recherche après une courte pause)', fakeAsync(() => {
    ouvrirSync();
    ouvrirOngletSync('VENTES');
    service.operations.calls.reset();
    const champ = el('[data-champ="recherche"]') as HTMLInputElement;
    champ.value = 'F007';
    champ.dispatchEvent(new Event('input'));
    tick(100);
    fixture.detectChanges();
    expect(service.operations).not.toHaveBeenCalled();                                 // pause avant l'envoi
    tick(300);
    expect(service.operations).toHaveBeenCalledWith('e-1', 'p-1', jasmine.objectContaining({ cote: 'SALES', q: 'F007' }), 0, 25);

    const traitement = el('[data-champ="filtre-traitement"]') as HTMLSelectElement;
    traitement.value = 'EXEMPT';
    traitement.dispatchEvent(new Event('change'));
    tick();
    expect(service.operations).toHaveBeenCalledWith('e-1', 'p-1', jasmine.objectContaining({ traitement: 'EXEMPT', q: 'F007' }), 0, 25);
    const tri = el('[data-champ="tri"]') as HTMLSelectElement;
    tri.value = 'amount';
    tri.dispatchEvent(new Event('change'));
    tick();
    (el('[data-action="sens-tri"]') as HTMLButtonElement).click();
    expect(service.operations.calls.mostRecent().args[2]).toEqual(jasmine.objectContaining({ tri: 'amount', sens: 'desc' }));
    (el('[data-champ="constat-actif"]') as HTMLInputElement).click();
    expect(service.operations.calls.mostRecent().args[2]).toEqual(jasmine.objectContaining({ constatActif: true }));
  }));

  it('validation en masse : la sélection est envoyée avec le jeton de version ; le serveur reste l\'autorité (refus affiché)', async () => {
    await ouvrir(periode(), [ligne({ ecritureId: 'ec-1' }), ligne({ ecritureId: 'ec-2', document: 'F2' })]);
    await onglet('VENTES');
    expect(el('[data-zone="validation-groupe"]')).toBeNull();                          // rien de sélectionné : pas de barre d'action
    const cases = (fixture.nativeElement as HTMLElement).querySelectorAll('[data-champ="selection-ligne"]');
    (cases[0] as HTMLInputElement).click();
    (cases[1] as HTMLInputElement).click();
    fixture.detectChanges();
    expect(texte('[data-champ="nb-selection"]')).toContain('2 opération(s) sélectionnée(s)');
    expect((el('[data-action="valider-groupe"]') as HTMLButtonElement).disabled).toBeTrue();         // justification obligatoire
    const motif = el('[data-champ="motif-groupe"]') as HTMLInputElement;
    motif.value = 'Ventes confirmées par les factures';
    motif.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    service.traitementGroupe.and.returnValue(throwError(() => ({ status: 422, error: { message: 'Validation en masse refusée : 1 opération(s) incompatible(s) sur 2' } })));
    (el('[data-action="valider-groupe"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.traitementGroupe).toHaveBeenCalledWith('e-1', 'p-1', { ecritureIds: ['ec-1', 'ec-2'], treatment: 'OUTPUT_TAX', reason: 'Ventes confirmées par les factures', expectedVersion: 7 });
    expect(texte('[data-zone="erreur-tva"]')).toContain('incompatible');

    service.traitementGroupe.and.returnValue(of({ applied: 2, period: periode({ version: 9 }) }));
    service.periode.and.returnValue(of(periode({ version: 9 })));
    (el('[data-action="valider-groupe"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el('[data-zone="validation-groupe"]')).toBeNull();                          // sélection vidée après succès
  });

  it('sans le droit de préparer : ni sélection en masse, ni formulaire de paiement ou de crédit', async () => {
    await ouvrir(periode({ permissions: { prepare: false, review: false, finalise: false } }));
    expect(el('[data-action="saisir-credit"]')).toBeNull();
    await onglet('VENTES');
    expect(el('[data-champ="selection-ligne"]')).toBeNull();
    TestBed.resetTestingModule();
    await ouvrir(periode({ status: 'FINAL', permissions: { prepare: false, review: false, finalise: false }, payment: reglement({ declaredAmount: 60, paidAmount: 0, remainingAmount: 60, status: 'A_PAYER', paymentOpen: true }) }));
    expect(el('[data-zone="form-paiement"]')).toBeNull();
  });

  it('règlement : solde déclaré, payé, reste à payer et historique (paiement annulé conservé) ; pas de bouton « payer en ligne »', async () => {
    const reg = reglement({ declaredAmount: 60, paidAmount: 20, remainingAmount: 40, status: 'PARTIELLEMENT_PAYE', paymentOpen: true,
      payments: [paiement(), paiement({ id: 'pay-2', amount: 40, status: 'REVERSED', reverseReason: 'Virement rejeté', reference: 'VIR-2' })] });
    await ouvrir(periode({ status: 'FINAL', anomalies: 0, toReview: 0, finalisationBlockers: [], progress: progression({ status: 'PARTIELLEMENT_PAYE', readySteps: 7 }), payment: reg,
      permissions: { prepare: true, review: true, finalise: true } }));
    expect(texte('[data-champ="solde-declare"]')).toBe('60');
    expect(texte('[data-champ="paye"]')).toBe('20');
    expect(texte('[data-champ="reste-a-payer"]')).toBe('40');
    expect(texte('[data-champ="statut-operationnel"]')).toContain('Partiellement payée');
    expect(el('[data-paiement="pay-2"]')!.innerText).toContain('Annulé : Virement rejeté');
    expect(el('[data-paiement="pay-2"] [data-action="annuler-paiement"]')).toBeNull();           // un paiement annulé n'est plus annulable, mais reste visible
    expect((fixture.nativeElement as HTMLElement).innerText.toLowerCase()).not.toContain('payer en ligne');
  });

  it('paiement partiel : l\'envoi porte une clé d\'idempotence conservée entre deux essais, la version lue et la source', async () => {
    const final = periode({ status: 'FINAL', anomalies: 0, toReview: 0, finalisationBlockers: [], payment: reglement({ declaredAmount: 60, remainingAmount: 60, status: 'A_PAYER', paymentOpen: true }),
      permissions: { prepare: true, review: true, finalise: true } });
    await ouvrir(final);
    expect((el('[data-action="enregistrer-paiement"]') as HTMLButtonElement).disabled).toBeTrue();
    const montant = el('[data-champ="paiement-montant"]') as HTMLInputElement;
    montant.value = '20';
    montant.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    service.enregistrerPaiement.and.returnValue(throwError(() => ({ status: 500, error: { message: 'Indisponible' } })));
    (el('[data-action="enregistrer-paiement"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const premiere = service.enregistrerPaiement.calls.mostRecent().args[2];
    expect(premiere.amount).toBe(20);
    expect(premiere.source).toBe('MANUAL');
    expect(premiere.expectedVersion).toBe(7);
    expect(premiere.idempotencyKey.length).toBeGreaterThan(8);
    (el('[data-action="enregistrer-paiement"]') as HTMLButtonElement).click();                    // second essai après l'échec : même clé
    expect(service.enregistrerPaiement.calls.mostRecent().args[2].idempotencyKey).toBe(premiere.idempotencyKey);
    service.enregistrerPaiement.and.returnValue(of(reglement()));
    service.periode.and.returnValue(of(periode({ ...final, version: 8, payment: reglement({ declaredAmount: 60, paidAmount: 20, remainingAmount: 40, status: 'PARTIELLEMENT_PAYE', paymentOpen: true, payments: [paiement()] }) })));
    (el('[data-action="enregistrer-paiement"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(texte('[data-champ="paye"]')).toBe('20');
    expect(texte('[data-champ="reste-a-payer"]')).toBe('40');
  });

  it('annulation d\'un paiement : motif obligatoire, appel avec la version', async () => {
    const reg = reglement({ declaredAmount: 60, paidAmount: 20, remainingAmount: 40, status: 'PARTIELLEMENT_PAYE', paymentOpen: true, payments: [paiement()] });
    await ouvrir(periode({ status: 'FINAL', anomalies: 0, toReview: 0, finalisationBlockers: [], payment: reg, permissions: { prepare: true, review: true, finalise: true } }));
    (el('[data-paiement="pay-1"] [data-action="annuler-paiement"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect((el('[data-action="confirmer-annulation-paiement"]') as HTMLButtonElement).disabled).toBeTrue();
    const motif = el('[data-champ="motif-annulation-paiement"]') as HTMLInputElement;
    motif.value = 'Virement rejeté';
    motif.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    service.annulerPaiement.and.returnValue(of(reglement()));
    (el('[data-action="confirmer-annulation-paiement"]') as HTMLButtonElement).click();
    expect(service.annulerPaiement).toHaveBeenCalledWith('e-1', 'p-1', 'pay-1', 'Virement rejeté', 7);
  });

  it('crédit : ouverture, clôture et validation affichés sans libellé juridique ; saisie justifiée avec source ; « à revoir » sans règle de report', async () => {
    await ouvrir(periode({ credit: { openingAmount: 500, reason: 'Reprise', source: 'Déclaration précédente', previousPeriodId: null, createdAt: '2026-10-01T10:00:00Z', openingState: 'TO_REVIEW', closingAmount: null, closingState: 'NONE' } }));
    expect(texte('[data-champ="credit-ouverture"]')).toBe('500');
    expect(texte('[data-champ="credit-cloture"]')).toBe('—');
    expect(texte('[data-champ="credit-statut"]')).toContain('ouverture à revoir');
    (el('[data-action="saisir-credit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();                                                        // enregistrement asynchrone des champs ngModel
    fixture.detectChanges();
    expect((el('[data-action="enregistrer-credit"]') as HTMLButtonElement).disabled).toBeTrue();
    for (const [champ, valeur] of [['credit-montant', '450'], ['credit-source', 'Déclaration papier'], ['credit-motif', 'Montant corrigé']]) {
      const c = el(`[data-champ="${champ}"]`) as HTMLInputElement;
      c.value = valeur;
      c.dispatchEvent(new Event('input'));
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    service.saisirCredit.and.returnValue(of({ openingAmount: 450, reason: 'Montant corrigé', source: 'Déclaration papier', previousPeriodId: null, createdAt: null, openingState: 'TO_REVIEW', closingAmount: null, closingState: 'NONE' }));
    (el('[data-action="enregistrer-credit"]') as HTMLButtonElement).click();
    expect(service.saisirCredit).toHaveBeenCalledWith('e-1', 'p-1', { amount: 450, reason: 'Montant corrigé', source: 'Déclaration papier', previousPeriodId: null, expectedVersion: 7 });
  });

  it('conflit 409 : message de rechargement, jamais d\'écrasement silencieux ; « Recharger » relit la période', async () => {
    await ouvrir(periode({ status: 'PREPARING' }));
    service.action.and.returnValue(throwError(() => ({ status: 409, error: { message: 'Cette période a été modifiée par un autre utilisateur (version courante 9, version fournie 7). Rechargez les dernières données avant de poursuivre.' } })));
    (el('[data-action="preparer"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(texte('[data-zone="erreur-tva"]')).toContain('modifiée par un autre utilisateur');
    expect(texte('[data-zone="erreur-tva"]')).toContain('Rechargez les dernières données');
    service.periode.calls.reset();
    service.periode.and.returnValue(of(periode({ version: 9 })));
    (el('[data-action="recharger"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.periode).toHaveBeenCalledTimes(1);
    expect(el('[data-zone="erreur-tva"]')).toBeNull();
  });

  it('un 409 sans rapport avec la concurrence (par ex. période non prête) n\'est pas présenté comme un conflit de version', async () => {
    await ouvrir(periode({ status: 'PREPARING' }));
    service.action.and.returnValue(throwError(() => ({ status: 409, error: { message: 'La période n\'est pas prête pour la revue : 1 opération(s) à revoir' } })));
    (el('[data-action="preparer"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(texte('[data-zone="erreur-tva"]')).toContain('pas prête pour la revue');
    expect(el('[data-action="recharger"]')).toBeNull();
  });
});
