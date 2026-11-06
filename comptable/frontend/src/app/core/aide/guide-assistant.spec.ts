import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Route, Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { routes } from '../../app.routes';
import { EntrepriseService } from '../../features/entreprises/entreprise.service';
import { TokenStorageService } from '../auth/token-storage.service';
import { GuideAssistantComponent } from './guide-assistant.component';
import { motsUtiles, normaliser, rechercher, suggestions } from './guide-recherche';
import { GUIDES_PAGES } from './guides-pages';
import { GUIDES_TACHES, PAGES_CLIENT } from './guides-taches';

@Component({ standalone: true, template: '' })
class PageVide {}

const ids = (question: string, cabinet = true) => rechercher(question, cabinet).map((t) => t.id);

describe('Guide : recherche « Que voulez-vous faire ? »', () => {
  it('« factu » propose la facture client ET la facture fournisseur, vers deux pages différentes', () => {
    const trouvees = rechercher('factu', true);
    const vente = trouvees.find((t) => t.id === 'facture-vente');
    const achat = trouvees.find((t) => t.id === 'facture-fournisseur');
    expect(vente?.onglet).toBe('facturation');
    expect(achat?.onglet).toBe('documents');
    expect(vente!.reponse).toContain('facture REÇUE');
    expect(achat!.reponse).toContain('facture à ÉMETTRE');
    expect(trouvees.length).toBeLessThanOrEqual(4);
    // les deux sens de « facture » passent AVANT les cas particuliers (avoir, relance…)
    expect(trouvees.slice(0, 2).map((t) => t.id).sort()).toEqual(['facture-fournisseur', 'facture-vente']);
  });

  it('le début d’un mot suffit, sans tenir compte des accents ni des majuscules', () => {
    expect(ids('fac')).toContain('facture-vente');
    expect(ids('FACTURÉ')).toContain('facture-vente');
    expect(ids('Trésorerie banque')).toContain('importer-releve');
    expect(normaliser('  Écriture  d’OUVERTURE ! ')).toBe('ecriture d ouverture');
  });

  it('les mots vides ne faussent pas la recherche', () => {
    expect(motsUtiles('comment faire le bilan ?')).toEqual(['bilan']);
    expect(ids('comment faire le bilan ?')[0]).toBe('bilan');
    expect(ids('je veux déclarer la TVA')[0]).toBe('tva');
  });

  it('le plus pertinent passe en premier', () => {
    expect(ids('importer un relevé')[0]).toBe('importer-releve');
    expect(ids('rapprocher la banque')[0]).toBe('rapprocher-banque');
    expect(ids('facture fournisseur')[0]).toBe('facture-fournisseur');
    expect(ids('avoir')[0]).toBe('avoir');
    expect(ids('relancer impayé')[0]).toBe('relancer-impayes');
    expect(ids('clôture')[0]).toBe('avant-cloture');
  });

  it('une question sans rapport ne renvoie rien (pas de réponse au hasard)', () => {
    expect(ids('zzzzzz')).toEqual([]);
    expect(ids('ab')).toEqual([]);
  });

  it('un client ne voit que ce qui existe dans son menu (Documents et Pièces demandées)', () => {
    expect(ids('factu', false)).toEqual(['facture-fournisseur']);
    expect(ids('bilan', false)).toEqual([]);
    expect(ids('pièce demandée', false)).toContain('deposer-piece-demandee');
    for (const t of rechercher('piece facture demande depot', false)) {
      expect(PAGES_CLIENT).toContain(t.onglet);
    }
  });

  it('chaque suggestion existe et reste accessible à son public', () => {
    expect(suggestions(true).length).toBe(6);
    expect(suggestions(false).map((t) => t.id)).toEqual(['facture-fournisseur', 'deposer-piece-demandee']);
  });
});

/** Segments d'URL des pages d'un dossier, lus dans le routeur réel : un lien du guide ne doit jamais mener à une page qui n'existe pas. */
function pagesDuDossier(): string[] {
  const trouver = (liste: Route[]): Route | undefined => {
    for (const r of liste) {
      if (r.path === 'entreprises/:entrepriseId') return r;
      const dedans = r.children ? trouver(r.children) : undefined;
      if (dedans) return dedans;
    }
    return undefined;
  };
  return (trouver(routes)?.children ?? []).map((r) => r.path ?? '').filter(Boolean);
}

describe('Guide : tous les liens mènent à une vraie page', () => {
  const pages = pagesDuDossier();

  it('le routeur expose bien les pages d’un dossier (garde-fou du test lui-même)', () => {
    expect(pages).toContain('facturation');
    expect(pages).toContain('fiscalite/tva');
  });

  it('chaque tâche et chaque étape pointe vers une page existante', () => {
    for (const t of GUIDES_TACHES) {
      expect(pages).withContext(`tâche ${t.id}`).toContain(t.onglet);
      for (const e of t.etapes) {
        if (e.onglet) expect(pages).withContext(`étape de ${t.id} : ${e.texte}`).toContain(e.onglet);
      }
    }
  });

  it('chaque guide de page pointe vers une page existante', () => {
    for (const g of GUIDES_PAGES) {
      expect(pages).withContext(`guide ${g.onglet}`).toContain(g.onglet);
      for (const e of g.etapes) {
        if (e.onglet) expect(pages).withContext(`étape de ${g.onglet}`).toContain(e.onglet);
      }
    }
  });

  it('les identifiants sont uniques et chaque tâche a de quoi être retrouvée', () => {
    const vus = new Set<string>();
    for (const t of GUIDES_TACHES) {
      expect(vus.has(t.id)).withContext(`doublon ${t.id}`).toBeFalse();
      vus.add(t.id);
      expect(t.etapes.length).withContext(t.id).toBeGreaterThan(0);
      expect(t.motsCles.length).withContext(t.id).toBeGreaterThan(2);
    }
  });
});

describe('GuideAssistantComponent : où emmener l’utilisateur ?', () => {
  const entreprise = (id: string, nom: string) => ({ id, raisonSociale: nom, pays: 'BF', devise: 'XOF', statut: 'ACTIVE' });
  let fixture: ComponentFixture<GuideAssistantComponent>;
  let liste: ReturnType<typeof entreprise>[];
  let appels = 0;

  const el = (sel: string) => (fixture.nativeElement as HTMLElement).querySelector(sel) as HTMLElement | null;

  async function ouvrir(options: { entreprises?: ReturnType<typeof entreprise>[]; url?: string; roles?: string[] }) {
    liste = options.entreprises ?? [];
    appels = 0;
    TestBed.configureTestingModule({
      imports: [GuideAssistantComponent],
      providers: [
        provideRouter([{ path: '**', component: PageVide }]),
        { provide: TokenStorageService, useValue: { getRoles: () => options.roles ?? ['ROLE_EXPERT_COMPTABLE'], getEmail: () => 'test@guide.ci' } },
        { provide: EntrepriseService, useValue: { lister: () => { appels++; return of(liste); } } },
      ],
    });
    if (options.url) {
      await TestBed.inject(Router).navigateByUrl(options.url);
    }
    fixture = TestBed.createComponent(GuideAssistantComponent);
    fixture.detectChanges();
    (el('.guide-bouton') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function taper(texte: string) {
    const champ = el('#guide-question') as HTMLInputElement;
    champ.value = texte;
    champ.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => localStorage.removeItem('warimara.guide.dossier.test@guide.ci'));

  it('hors dossier avec UN seul dossier : le bouton emmène directement dans ce dossier', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')] });
    taper('factu');
    const bouton = el('[data-tache="facture-vente"] a.guide-aller') as HTMLAnchorElement;
    expect(bouton).not.toBeNull();
    expect(bouton.textContent).toContain('Ouvrir Facturation');
    expect(bouton.getAttribute('href')).toBe('/entreprises/e1/facturation');
    expect((el('[data-tache="facture-fournisseur"] a.guide-aller') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e1/documents');
    expect(el('[data-zone="dossier-cible"]')?.textContent).toContain('SARL Alpha');
  });

  it('hors dossier avec PLUSIEURS dossiers : un sélecteur est demandé, et les boutons attendent le choix', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha'), entreprise('e2', 'SA Beta')] });
    taper('factu');
    expect(el('[data-zone="choix-dossier"]')).not.toBeNull();
    expect(el('a.guide-aller')).toBeNull();
    expect(el('.guide-attente')?.textContent).toContain('Choisissez un dossier');

    const select = el('#guide-dossier') as HTMLSelectElement;
    select.value = 'e2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect((el('[data-tache="facture-vente"] a.guide-aller') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e2/facturation');
    expect(el('.guide-attente')).toBeNull();
    expect(localStorage.getItem('warimara.guide.dossier.test@guide.ci')).toBe('e2');
  });

  it('le dernier dossier ouvert est proposé d’office la fois suivante, mais seulement s’il existe encore', async () => {
    localStorage.setItem('warimara.guide.dossier.test@guide.ci', 'e2');
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha'), entreprise('e2', 'SA Beta')] });
    taper('bilan');
    expect((el('[data-tache="bilan"] a.guide-aller') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e2/etats-financiers');
    TestBed.resetTestingModule();
    localStorage.setItem('warimara.guide.dossier.test@guide.ci', 'supprime');
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha'), entreprise('e2', 'SA Beta')] });
    taper('bilan');
    expect(el('a.guide-aller')).toBeNull();
    expect(el('[data-zone="choix-dossier"]')).not.toBeNull();
  });

  it('aucun dossier : un message clair, pas de bouton mort', async () => {
    await ouvrir({ entreprises: [] });
    taper('factu');
    expect(el('[data-zone="aucun-dossier"]')?.textContent).toContain('Créez d’abord une entreprise');
    expect(el('a.guide-aller')).toBeNull();
    expect(el('[data-tache="facture-vente"] .guide-reponse')?.textContent).toContain('Facturation');
  });

  it('dans un dossier : le bouton emmène dans CE dossier, sans rien charger', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')], url: '/entreprises/abc123/documents' });
    taper('tva');
    expect(appels).toBe(0);
    expect((el('[data-tache="tva"] a.guide-aller') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/abc123/fiscalite/tva');
    expect(el('[data-zone="choix-dossier"]')).toBeNull();
    expect(el('details.guide-page summary')?.textContent).toContain('Documents');
  });

  it('un client ne reçoit jamais un bouton vers une page qui n’existe pas dans son menu', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')], roles: ['ROLE_CLIENT'] });
    taper('factu');
    expect(el('[data-tache="facture-vente"]')).toBeNull();
    expect((el('[data-tache="facture-fournisseur"] a.guide-aller') as HTMLAnchorElement).getAttribute('href')).toBe('/entreprises/e1/documents');
    // l'étape « Validation » est réservée au cabinet : pas de lien « Y aller » pour un client
    const etapes = el('[data-tache="facture-fournisseur"] ol.guide-etapes') as HTMLElement;
    expect(etapes.querySelector('a[href$="/validation"]')).toBeNull();
  });

  it('sans recherche : des exemples cliquables ; un clic lance la recherche', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')] });
    const puces = (fixture.nativeElement as HTMLElement).querySelectorAll('.guide-puce');
    expect(puces.length).toBe(6);
    (puces[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect((el('#guide-question') as HTMLInputElement).value).toContain('facture à un client');
    expect(el('[data-tache="facture-vente"]')).not.toBeNull();
  });

  it('plusieurs réponses : étapes repliées pour toutes les voir d’un coup d’œil, dépliables à la demande', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')] });
    taper('factu');
    const cartes = (fixture.nativeElement as HTMLElement).querySelectorAll('.guide-resultat');
    expect(cartes.length).toBeGreaterThan(1);
    cartes.forEach((c) => expect(c.querySelector('ol.guide-etapes')).toBeNull());
    (cartes[1].querySelector('.guide-etapes-bouton') as HTMLButtonElement).click();
    fixture.detectChanges();
    const apres = (fixture.nativeElement as HTMLElement).querySelectorAll('.guide-resultat');
    expect(apres[1].querySelector('ol.guide-etapes')).not.toBeNull();
    expect(apres[0].querySelector('ol.guide-etapes')).toBeNull();
  });

  it('une seule réponse : ses étapes sont directement dépliées', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')] });
    taper('amortissement');
    const cartes = (fixture.nativeElement as HTMLElement).querySelectorAll('.guide-resultat');
    expect(cartes.length).toBe(1);
    expect(cartes[0].querySelector('ol.guide-etapes')).not.toBeNull();
  });

  it('une recherche sans résultat le dit clairement', async () => {
    await ouvrir({ entreprises: [entreprise('e1', 'SARL Alpha')] });
    taper('zzzzzz');
    expect(el('[data-zone="aucun-resultat"]')?.textContent).toContain('Je n’ai rien trouvé');
  });
});
