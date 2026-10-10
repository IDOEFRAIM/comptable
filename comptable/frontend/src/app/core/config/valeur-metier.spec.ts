import { DecimalPipe } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, RouterLink, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ConformiteComponent } from '../../features/conformite/conformite.component';
import { ConformiteService } from '../../features/conformite/conformite.service';
import { CabinetService } from '../../features/cabinet/cabinet.service';
import { DashboardService } from '../../features/dashboard/dashboard.service';
import { DossierComptableDto } from '../../features/dashboard/models/dashboard.model';
import { TableauDeBordComponent } from '../../features/dashboard/tableau-de-bord.component';
import { FinanceService } from '../../features/finance/finance.service';
import { TresorerieComponent } from '../../features/finance/tresorerie.component';
import { QualityDashboardComponent } from '../../features/telemetry/quality-dashboard.component';
import { TelemetryService } from '../../features/telemetry/telemetry.service';
import { ConfigPubliqueService } from './config-publique.service';

const route = { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ entrepriseId: 'e1' })), snapshot: { paramMap: convertToParamMap({ entrepriseId: 'e1' }) } } };
const texte = (f: { nativeElement: unknown }) => (f.nativeElement as HTMLElement).innerText;

describe('Configuration publique (outils de test et inscription)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  it('tant que le serveur n’a pas répondu : côté prudent (ni inscription, ni paiement en ligne, ni outil de test)', () => {
    const c = TestBed.inject(ConfigPubliqueService);
    expect(c.inscriptionOuverte()).toBeFalse();
    expect(c.paiementEnLigneDisponible()).toBeFalse();
    expect(c.outilsDeTestVisibles()).toBeFalse();
    http.expectOne('/api/config').error(new ProgressEvent('error'));
    expect(c.paiementEnLigneDisponible()).toBeFalse();
  });

  it('production avec paiement simulé : ni inscription, ni paiement en ligne, ni simulation', () => {
    const c = TestBed.inject(ConfigPubliqueService);
    http.expectOne('/api/config').flush({ inscriptionOuverte: false, production: true, paiementEnLigneIndisponible: true });
    expect(c.inscriptionOuverte()).toBeFalse();
    expect(c.paiementEnLigneDisponible()).toBeFalse();
    expect(c.outilsDeTestVisibles()).toBeFalse();
  });

  it('développement : tout est disponible pour tester', () => {
    const c = TestBed.inject(ConfigPubliqueService);
    http.expectOne('/api/config').flush({ inscriptionOuverte: true, production: false, paiementEnLigneIndisponible: false });
    expect(c.inscriptionOuverte()).toBeTrue();
    expect(c.paiementEnLigneDisponible()).toBeTrue();
    expect(c.outilsDeTestVisibles()).toBeTrue();
  });
});

describe('Suivi du cabinet : plus de jargon d’éditeur, pas de pourcentage sur un petit échantillon', () => {
  const kpi = {
    humanTouchRate: { automatique: 0, assisteParIa: 1, humainSeul: 0, enAttente: 0, totalResolu: 1, humanTouchRatePourcent: 100, automationRatePourcent: 0 },
    matchingBancaire: { domaine: 'BANCAIRE', propositions: 1, acceptees: 1, rejetees: 0, enAttente: 0, expirees: 0, tauxAcceptationPourcent: 100, tauxRejetPourcent: 0 },
    comptabilisation: { domaine: 'COMPTABILISATION', propositions: 0, acceptees: 0, rejetees: 0, enAttente: 0, expirees: 0, tauxAcceptationPourcent: null, tauxRejetPourcent: null },
    resolutionPreuves: { creees: 3, completees: 2, annulees: 0, enAttente: 1, tauxResolutionPourcent: 66.7, dureeMoyenneResolutionHeures: 5.5 },
    tempsResolutionFindings: { nombreResolu: 4, moyenneHeures: 3, medianeHeures: 2, p90Heures: 9 },
    usageCopilot: { questions: 1, reponsesAvecPreuve: 0, fallback: 0, latenceMoyenneMs: 854, feedbackPositif: 0, feedbackNegatif: 0, sansFeedback: 1, tauxFallbackPourcent: 0, tauxFeedbackPositifPourcent: null, tauxFeedbackNegatifPourcent: null, tauxReponseAvecPreuvePourcent: 0 },
    workQueue: { findingsOuverts: 5, findingsEnRetard: 2, demandesEnAttente: 3, demandesEnRetard: 1 },
  };

  it('N = 1 ou 3 : « — », jamais « 0 % » ni « 100 % » ; le détail réel reste affiché', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: TelemetryService, useValue: { obtenirKpi: () => of(kpi) } }] });
    const f = TestBed.createComponent(QualityDashboardComponent);
    f.detectChanges();
    const t = texte(f);
    expect(f.componentInstance.pourcent(0, 1)).toBe('—');
    expect(f.componentInstance.pourcent(66.7, 9)).toBe('—');
    expect(f.componentInstance.pourcent(66.7, 10)).toBe('67 %');
    expect(t).not.toMatch(/\d+ ?%/);
    expect(t).toContain('3 demandes');
    expect(t).toContain('Travail en cours');
  });

  it('plus de latence, de P90, de « fallback », ni de libellés anglais', () => {
    TestBed.configureTestingModule({ providers: [{ provide: TelemetryService, useValue: { obtenirKpi: () => of(kpi) } }] });
    const f = TestBed.createComponent(QualityDashboardComponent);
    f.detectChanges();
    const html = (f.nativeElement as HTMLElement).innerHTML;
    for (const interdit of ['Latence', 'latence', 'P90', 'fallback', 'Automation Rate', 'Human Touch', 'Copilot', '854']) {
      expect(html).withContext(interdit).not.toContain(interdit);
    }
    const technique = (f.nativeElement as HTMLElement).querySelector('details[data-zone="qualite-assistant"]') as HTMLDetailsElement;
    expect(technique).not.toBeNull();
    expect(technique.open).toBeFalse();
  });
});

describe('Tableau de bord : pas de score sans données', () => {
  const dossier = (surcharge: Partial<DossierComptableDto>): DossierComptableDto => ({
    entrepriseNom: 'SARL Test', scoreGlobal: 100, statut: 'PRET', actionsPrioritaires: [], blockingItems: [], aucuneActivite: false,
    scoresParDomaine: [{ domaine: 'Banque', score: 100, elementsOuverts: 0 }, { domaine: 'Fiscalite', score: 87, elementsOuverts: 2 }], ...surcharge,
  });

  function ouvrir(d: DossierComptableDto) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), route,
        { provide: DashboardService, useValue: { obtenir: () => of(null), obtenirDossier: () => of(d), listerFindings: () => of([]), obtenirClotureReadiness: () => of(null) } },
        { provide: CabinetService, useValue: { obtenirCollaborateurs: () => of([]) } },
      ],
    });
    TestBed.overrideComponent(TableauDeBordComponent, { set: { imports: [DecimalPipe, RouterLink, FormsModule], schemas: [NO_ERRORS_SCHEMA] } });
    const f = TestBed.createComponent(TableauDeBordComponent);
    f.detectChanges();
    return f;
  }

  it('dossier vide : « Aucune activité enregistrée », ni « Prêt », ni « 100% »', () => {
    const f = ouvrir(dossier({ aucuneActivite: true }));
    const t = texte(f);
    expect(t).toContain('Aucune activité enregistrée');
    expect(t).not.toContain('Aucun point bloquant');
    expect(t).not.toMatch(/100 ?%/);
    expect((f.nativeElement as HTMLElement).querySelector('[data-domaine]')).toBeNull();
  });

  it('dossier actif : des nombres réels à traiter par domaine, plus de pourcentage', () => {
    const f = ouvrir(dossier({}));
    const t = texte(f);
    expect(t).toContain('2 à traiter');
    expect(t.toLowerCase()).toContain('fiscalité');
    expect(t).toContain('Aucun constat');
    expect(t).not.toMatch(/\d+ ?%/);
    expect(t).not.toContain('Score global');
  });
});

describe('Conformité fiscale : jamais de score parfait sans cadre vérifié', () => {
  function ouvrir(sante: object) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), route,
        { provide: ConformiteService, useValue: { obtenir: () => of({ obligations: [], anomalies: [] }), obtenirTaxHealth: () => of(sante) } },
      ],
    });
    const f = TestBed.createComponent(ConformiteComponent);
    f.detectChanges();
    return f;
  }

  it('cadre non vérifié : « Non évalué » avec le motif', () => {
    const f = ouvrir({ evalue: false, motif: 'Non évalué : aucun cadre fiscal vérifié ne s’applique à ce pays.', anomaliesOuvertes: 0 });
    const t = texte(f);
    expect(t).toContain('Non évalué');
    expect(t).not.toContain('/ 100');
    expect((f.nativeElement as HTMLElement).querySelector('[data-champ="conformite-non-evaluee"]')).not.toBeNull();
  });

  it('cadre vérifié : le nombre réel d’anomalies fiscales ouvertes', () => {
    const f = ouvrir({ evalue: true, motif: null, anomaliesOuvertes: 3 });
    expect(texte(f)).toContain('3 anomalies fiscales ouvertes');
  });
});

describe('Trésorerie : une prévision incomplète le dit', () => {
  it('avertissement « hors fournisseurs », libellés honnêtes, renvoi vers le compte de résultat officiel', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), route,
        {
          provide: FinanceService,
          useValue: {
            obtenirPosition: () => of({ dateReference: '2026-10-09', total: 0, parCompte: [] }),
            obtenirPrevision: () => of([{ horizonJours: 30, soldeActuel: 0, encaissementsConnus: 0, encaissementsAttendus: 0, decaissementsConnus: 0, soldePrevisionnel: 0 }]),
            obtenirSnapshot: () => of({ cash: 0, receivables: 0, payables: 0, revenue: 1, expenses: 1, profit: 0, taxes: 0 }),
          },
        },
      ],
    });
    const f = TestBed.createComponent(TresorerieComponent);
    f.detectChanges();
    const t = texte(f);
    expect(t).toContain('plafond optimiste');
    expect(t).toContain('hors fournisseurs et charges');
    expect(t).toContain('Impôts à payer (seuls décaissements connus)');
    expect(t).toContain('Produits (classe 7)');
    expect(t).toContain("Ce n'est pas le résultat comptable");
    expect(t).not.toContain('Chiffre d\'affaires');
    expect(t).not.toContain('Snapshot');
  });
});
