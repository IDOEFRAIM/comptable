import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SnapshotViewerComponent } from './snapshot-viewer.component';

function etat(type: string, code: string): unknown {
  return {
    statementType: type, framework: 'F', version: '2', definitionHash: 'h', exerciceId: 'ex-1', exerciceDebut: '2026-01-01', exerciceFin: '2026-12-31',
    exercicePrecedentId: null, precedentDebut: null, precedentFin: null, status: 'FINAL', statusReasons: [], unmapped: [], ambiguous: [], controls: [],
    lines: [{ code, label: 'Ligne figée', order: 1, calculationType: 'MAPPED', verificationStatus: 'VERIFIED', provenanceType: 'DIRECT_PRIMARY', amountN: 1234, amountNMinus1: null,
      grossN: null, depreciationN: null, notCalculableReason: null, accounts: [], complete: true, incompleteReasons: [] }],
  };
}

describe('SnapshotViewerComponent (version finale)', () => {
  let fixture: ComponentFixture<SnapshotViewerComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SnapshotViewerComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  it('lit UNIQUEMENT le snapshot : aucune requête vers les états vivants (compte de résultat, bilan, TFT, notes, synthèse)', () => {
    fixture = TestBed.createComponent(SnapshotViewerComponent);
    fixture.componentRef.setInput('entrepriseId', 'e-1');
    fixture.componentRef.setInput('snapshotId', 'snap-1');
    fixture.detectChanges();

    const requete = http.expectOne('/api/entreprises/e-1/etats-financiers-syscohada/snapshots/snap-1');
    requete.flush({
      id: 'snap-1', exerciceId: 'ex-1', statementType: 'DOSSIER_COMPLET', framework: 'SYSCOHADA_REVISE_AUDCIF_2017', definitionVersion: '2', definitionHash: 'h', status: 'FINAL',
      datasetFingerprint: 'f', payloadSha256: 'sha-final', createdAt: '2026-10-05T13:00:00Z',
      payload: {
        frameworkVersion: 'SYSCOHADA_REVISE_AUDCIF_2017/2', entreprise: { raisonSociale: 'PME Test' }, exercice: { debut: '2026-01-01', fin: '2026-12-31' }, generatedAt: '2026-10-05T12:00:00Z',
        definitionHashes: { TFT: 'abc' }, compteResultat: etat('COMPTE_RESULTAT', 'XI'), bilanActif: etat('BILAN_ACTIF', 'BZ'), bilanPassif: etat('BILAN_PASSIF', 'DZ'), tft: etat('TFT', 'ZH'),
        notes: { notes: [{ noteCode: '17', title: 'Fournisseurs', completionStatus: 'READY', generatedData: [], manualData: { groupe: { contenu: { value: 'Aucune dette' } } }, review: { reviewedAt: null } }], notesNonApplicables: [] },
        controls: [],
      },
    });
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).innerText;
    expect(texte).toContain('Dossier financier final');
    expect(texte).toContain('PME Test');
    expect(texte).toContain('sha-final');
    expect(texte).toContain('Ligne figée');
    expect(texte).toContain('Aucune dette');
    // lecture seule : aucun bouton « Pourquoi ? » (le drill-down relève des états vivants)
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('[data-action^="pourquoi-"]').length).toBe(0);
    http.verify();           // aucune autre requête (pas d'état vivant)
  });
});
