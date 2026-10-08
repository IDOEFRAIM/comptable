import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { StatusComponent } from '../../shared/ui/status.component';
import { DossierSummary, FiltrePortefeuille, Page, TriPortefeuille, UtilisateurRef } from './cabinet-workspace.model';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { dateCourte, ilYA, libelleEtapes, routeDossier, urgent } from './cabinet-workspace.util';

const ONGLETS: { code: FiltrePortefeuille; libelle: string }[] = [
  { code: 'MES_DOSSIERS', libelle: 'Mes dossiers' },
  { code: 'TOUS', libelle: 'Tous' },
  { code: 'A_TRAITER', libelle: 'À traiter' },
  { code: 'EN_ATTENTE_CLIENT', libelle: 'En attente client' },
  { code: 'PRETS_REVUE', libelle: 'Prêts pour revue' },
  { code: 'PRETS_CLOTURE', libelle: 'Prêts à clôturer' },
  { code: 'FINALISES', libelle: 'Finalisés' },
];

const STATUTS: { code: string; libelle: string }[] = [
  { code: 'A_DEMARRER', libelle: 'À démarrer' },
  { code: 'EN_COURS', libelle: 'En cours' },
  { code: 'ATTENTION_REQUISE', libelle: 'Attention requise' },
  { code: 'EN_ATTENTE_CLIENT', libelle: 'En attente du client' },
  { code: 'PRET_POUR_REVUE', libelle: 'Prêt pour revue' },
  { code: 'EN_REVUE', libelle: 'En revue' },
  { code: 'PRET_A_CLOTURER', libelle: 'Prêt à clôturer' },
  { code: 'CLOTURE', libelle: 'Clôturé' },
  { code: 'FINALISE', libelle: 'Finalisé' },
];

const TRIS: { code: TriPortefeuille; libelle: string }[] = [
  { code: 'PRIORITE', libelle: 'Priorité' },
  { code: 'ENTREPRISE', libelle: 'Entreprise' },
  { code: 'ACTIVITE', libelle: 'Dernière activité' },
  { code: 'ECHEANCE', libelle: 'Échéance' },
];

/**
 * « Mes dossiers » : le portefeuille du cabinet. Recherche, filtres, tri et pagination sont exécutés par le backend (jamais le portefeuille entier dans Angular) ; l'écran affiche le statut,
 * la progression par étapes et la priorité fournis par le serveur. L'affectation en masse est réservée à l'expert-comptable.
 */
@Component({
  selector: 'app-portfolio',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusComponent, EmptyStateComponent],
  template: `
    <div class="page-portfolio" data-zone="portfolio">
      <header class="entete-page">
        <h1 class="ui-page-title">Dossiers</h1>
        <p class="ui-meta">{{ resume() }}</p>
      </header>

      <nav class="ui-tabs" role="tablist" aria-label="Filtres du portefeuille">
        @for (o of onglets; track o.code) {
          <button type="button" role="tab" class="ui-tab" [attr.aria-selected]="filtre() === o.code" (click)="choisirFiltre(o.code)" [attr.data-filtre]="o.code">{{ o.libelle }}</button>
        }
      </nav>

      <div class="barre">
        <div class="recherche">
          <label class="ui-sr-only" for="recherche-dossier">Rechercher un dossier</label>
          <input id="recherche-dossier" class="ui-input" type="search" placeholder="Entreprise, identifiant ou responsable" [ngModel]="q()" (ngModelChange)="rechercher($event)" data-champ="recherche" />
        </div>
        <label class="ui-sr-only" for="f-collaborateur">Collaborateur</label>
        <select id="f-collaborateur" class="ui-input filtre" [ngModel]="collaborateurId()" (ngModelChange)="majFiltre(collaborateurId, $event)" data-champ="filtre-collaborateur">
          <option value="">Tous les collaborateurs</option>
          @for (m of membres(); track m.id) { <option [value]="m.id">{{ m.nom }}</option> }
        </select>
        <label class="ui-sr-only" for="f-expert">Expert</label>
        <select id="f-expert" class="ui-input filtre" [ngModel]="expertId()" (ngModelChange)="majFiltre(expertId, $event)" data-champ="filtre-expert">
          <option value="">Tous les experts</option>
          @for (m of membres(); track m.id) { <option [value]="m.id">{{ m.nom }}</option> }
        </select>
        <label class="ui-sr-only" for="f-exercice">Exercice</label>
        <select id="f-exercice" class="ui-input filtre" [ngModel]="exercice()" (ngModelChange)="majFiltre(exercice, $event)" data-champ="filtre-exercice">
          <option value="">Tous les exercices</option>
          @for (a of annees; track a) { <option [value]="a">{{ a }}</option> }
        </select>
        <label class="ui-sr-only" for="f-statut">Statut</label>
        <select id="f-statut" class="ui-input filtre" [ngModel]="statut()" (ngModelChange)="majFiltre(statut, $event)" data-champ="filtre-statut">
          <option value="">Tous les statuts</option>
          @for (s of statuts; track s.code) { <option [value]="s.code">{{ s.libelle }}</option> }
        </select>
        <label class="ui-sr-only" for="f-tri">Trier par</label>
        <select id="f-tri" class="ui-input filtre" [ngModel]="tri()" (ngModelChange)="majFiltre(tri, $event)" data-champ="tri">
          @for (t of tris; track t.code) { <option [value]="t.code">Tri : {{ t.libelle }}</option> }
        </select>
      </div>

      @if (selection().size > 0 && expert) {
        <div class="ui-notice ui-notice--info barre-masse" data-zone="affectation-masse">
          <div class="masse">
            <strong>{{ selection().size }} dossier{{ selection().size > 1 ? 's' : '' }} sélectionné{{ selection().size > 1 ? 's' : '' }}</strong>
            <label class="ui-sr-only" for="masse-collaborateur">Affecter à</label>
            <select id="masse-collaborateur" class="ui-input filtre" [ngModel]="affectationMasse()" (ngModelChange)="affectationMasse.set($event)" data-champ="masse-collaborateur">
              <option value="">Affecter à…</option>
              @for (m of membres(); track m.id) { <option [value]="m.id">{{ m.nom }}</option> }
            </select>
            <button type="button" class="ui-btn ui-btn--primary ui-btn--sm" [disabled]="!affectationMasse() || enCours()" (click)="affecterSelection()" data-action="affecter-masse">Affecter</button>
            <button type="button" class="ui-btn ui-btn--tertiary ui-btn--sm" (click)="selection.set(new_set())">Annuler</button>
          </div>
        </div>
      }
      @if (erreur()) {
        <p class="ui-notice ui-notice--danger" role="alert">{{ erreur() }}</p>
      }
      @if (info()) {
        <p class="ui-notice ui-notice--success" role="status" data-champ="info">{{ info() }}</p>
      }

      @if (page(); as p) {
        @if (p.items.length) {
          <div class="ui-surface">
            <table class="ui-table portefeuille">
              <thead>
                <tr>
                  @if (expert) { <th class="col-case"><span class="ui-sr-only">Sélection</span></th> }
                  <th>Entreprise</th><th>Responsable</th><th>Statut</th><th>Étapes</th><th class="ui-num">Pièces</th><th class="ui-num">Banque</th><th class="ui-num">Contrôles</th><th>TVA</th><th>Dernière activité</th>
                  <th class="col-action"><span class="ui-sr-only">Action</span></th>
                </tr>
              </thead>
              <tbody>
                @for (d of p.items; track d.exerciceId) {
                  <tr [class.urgent]="urgent(d.priorite)" [attr.data-dossier]="d.entrepriseNom" [attr.data-statut-dossier]="d.statut" [attr.data-priorite]="d.priorite">
                    @if (expert) {
                      <td class="col-case"><input type="checkbox" [checked]="selection().has(d.exerciceId)" (change)="basculer(d)" [attr.aria-label]="'Sélectionner ' + d.entrepriseNom" /></td>
                    }
                    <td>
                      <p class="nom">{{ d.entrepriseNom }}</p>
                      <p class="ui-meta">Exercice {{ annee(d) }}@if (d.identifiant) { · {{ d.identifiant }} }</p>
                    </td>
                    <td>
                      <p class="resp">{{ d.collaborateur?.nom ?? 'Non affecté' }}</p>
                      @if (d.expert) { <p class="ui-meta">Expert : {{ d.expert.nom }}</p> }
                    </td>
                    <td><app-status [statut]="d.statut" [texte]="d.statutLibelle" /><span class="ui-sr-only"> Priorité {{ d.priorite }}</span></td>
                    <td>
                      <span class="etapes" [attr.title]="restantes(d)">{{ etapes(d) }}</span>
                      @if (d.etapesPretes < d.etapesTotal) { <p class="ui-meta reste">{{ restantes(d) }}</p> }
                    </td>
                    <td class="ui-num">{{ d.piecesManquantes || '—' }}</td>
                    <td class="ui-num">{{ d.banqueExceptions || '—' }}</td>
                    <td class="ui-num">{{ d.findingsActifs || '—' }}@if (d.findingsBloquants) { <span class="ui-meta"> ({{ d.findingsBloquants }} bloquants)</span> }</td>
                    <td data-champ="tva">{{ d.tvaLibelle ?? '—' }}</td>
                    <td class="ui-muted">{{ activite(d.derniereActivite) }}</td>
                    <td class="col-action"><a class="ui-btn ui-btn--secondary ui-btn--sm" [routerLink]="route(d)" data-action="ouvrir-dossier">Ouvrir</a></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="pagination" data-zone="pagination">
            <span class="ui-meta">{{ debut() }}–{{ fin() }} sur {{ p.total }}</span>
            <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" [disabled]="p.page === 0" (click)="aller(p.page - 1)" data-action="page-precedente">Précédent</button>
            <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" [disabled]="(p.page + 1) * p.taille >= p.total" (click)="aller(p.page + 1)" data-action="page-suivante">Suivant</button>
          </div>
        } @else {
          <app-empty-state data-champ="portefeuille-vide" icone="search" titre="Aucun dossier" [detail]="messageVide()" />
        }
      } @else if (chargement()) {
        <p class="ui-meta" role="status">Chargement des dossiers…</p>
      }
    </div>
  `,
  styles: [
    `
      .page-portfolio { max-width: var(--ui-page-max); margin: 0 auto; padding: var(--ui-s5) var(--ui-s5) var(--ui-s7); display: flex; flex-direction: column; gap: var(--ui-s4); }
      .entete-page { display: flex; align-items: baseline; justify-content: space-between; gap: var(--ui-s4); flex-wrap: wrap; }
      .entete-page p { margin: 0; }
      .barre { display: flex; flex-wrap: wrap; gap: var(--ui-s2); }
      .recherche { flex: 1; min-width: 260px; }
      .filtre { width: auto; min-width: 150px; }
      .barre-masse .masse { display: flex; align-items: center; flex-wrap: wrap; gap: var(--ui-s3); }
      .portefeuille td { vertical-align: top; }
      .portefeuille tr.urgent td:first-child { box-shadow: inset 2px 0 0 var(--ui-warning); }
      .portefeuille tr[data-priorite='BLOCKER'] td:first-child { box-shadow: inset 2px 0 0 var(--ui-danger); }
      .nom { margin: 0; font-weight: var(--ui-fw-semibold); }
      .resp { margin: 0; }
      td p.ui-meta { margin: 2px 0 0; }
      .etapes { font-variant-numeric: tabular-nums; font-weight: var(--ui-fw-medium); }
      .reste { max-width: 200px; }
      .col-case { width: 32px; }
      .col-action { width: 1%; text-align: right; white-space: nowrap; }
      .pagination { display: flex; justify-content: flex-end; align-items: center; gap: var(--ui-s2); }
    `,
  ],
})
export class PortfolioComponent implements OnInit, OnDestroy {
  private readonly service = inject(CabinetWorkspaceService);
  private readonly route$ = inject(ActivatedRoute);
  private readonly tokens = inject(TokenStorageService);
  private abonnement?: Subscription;
  private minuteur?: ReturnType<typeof setTimeout>;

  readonly expert = this.tokens.getRoles().includes('ROLE_EXPERT_COMPTABLE');
  readonly onglets = ONGLETS;
  readonly statuts = STATUTS;
  readonly tris = TRIS;
  readonly annees = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() + 1 - i);

  readonly filtre = signal<FiltrePortefeuille>(this.expert ? 'TOUS' : 'MES_DOSSIERS');
  readonly q = signal('');
  readonly collaborateurId = signal('');
  readonly expertId = signal('');
  readonly exercice = signal('');
  readonly statut = signal('');
  readonly tri = signal<TriPortefeuille>('PRIORITE');
  readonly pageCourante = signal(0);
  readonly taille = 25;

  readonly page = signal<Page<DossierSummary> | null>(null);
  readonly membres = signal<UtilisateurRef[]>([]);
  readonly selection = signal<Set<string>>(new Set());
  readonly affectationMasse = signal('');
  readonly chargement = signal(false);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly info = signal<string | null>(null);

  readonly resume = computed(() => {
    const p = this.page();
    return p ? `${p.total} dossier${p.total > 1 ? 's' : ''}` : '';
  });
  readonly debut = computed(() => ((this.page()?.page ?? 0) * this.taille) + 1);
  readonly fin = computed(() => Math.min(this.debut() + this.taille - 1, this.page()?.total ?? 0));

  etapes = libelleEtapes;
  urgent = urgent;
  route = (d: DossierSummary) => routeDossier(d.statut, d.entrepriseId);
  new_set = () => new Set<string>();

  ngOnInit(): void {
    this.service.membres().subscribe({ next: (m) => this.membres.set(m), error: () => this.membres.set([]) });
    this.abonnement = this.route$.queryParamMap.subscribe((p) => {
      const filtre = p.get('filtre') as FiltrePortefeuille | null;
      if (filtre && ONGLETS.some((o) => o.code === filtre)) {
        this.filtre.set(filtre);
      }
      this.pageCourante.set(0);
      this.charger();
    });
  }

  ngOnDestroy(): void {
    this.abonnement?.unsubscribe();
    clearTimeout(this.minuteur);
  }

  choisirFiltre(f: FiltrePortefeuille): void {
    this.filtre.set(f);
    this.pageCourante.set(0);
    this.charger();
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  majFiltre(cible: { set: (v: any) => void }, valeur: string): void {
    cible.set(valeur);
    this.pageCourante.set(0);
    this.charger();
  }

  /** Recherche déclenchée côté serveur après une courte pause de saisie (jamais un filtre local sur un portefeuille chargé). */
  rechercher(valeur: string): void {
    this.q.set(valeur);
    clearTimeout(this.minuteur);
    this.minuteur = setTimeout(() => {
      this.pageCourante.set(0);
      this.charger();
    }, 250);
  }

  aller(page: number): void {
    this.pageCourante.set(page);
    this.charger();
  }

  basculer(d: DossierSummary): void {
    const s = new Set(this.selection());
    if (!s.delete(d.exerciceId)) {
      s.add(d.exerciceId);
    }
    this.selection.set(s);
  }

  affecterSelection(): void {
    const ids = this.selection();
    const dossiers = (this.page()?.items ?? []).filter((d) => ids.has(d.exerciceId)).map((d) => ({ entrepriseId: d.entrepriseId, exerciceId: d.exerciceId }));
    if (!dossiers.length || !this.affectationMasse()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.service.affecterEnMasse(dossiers, this.affectationMasse(), null).subscribe({
      next: (r) => {
        this.info.set(`${r.dossiers} dossier${r.dossiers > 1 ? 's' : ''} affecté${r.dossiers > 1 ? 's' : ''}.`);
        this.selection.set(new Set());
        this.affectationMasse.set('');
        this.enCours.set(false);
        this.charger();
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Affectation impossible.');
        this.enCours.set(false);
      },
    });
  }

  annee(d: DossierSummary): string {
    return d.exerciceFin.slice(0, 4);
  }

  restantes(d: DossierSummary): string {
    const manquantes = d.etapes.filter((e) => !e.prete).map((e) => e.libelle);
    return manquantes.length > 2 ? `${manquantes.slice(0, 2).join(', ')} +${manquantes.length - 2}` : manquantes.join(', ');
  }

  activite(iso: string | null): string {
    return ilYA(iso);
  }

  date = dateCourte;

  messageVide(): string {
    return this.filtre() === 'MES_DOSSIERS' ? 'Aucun dossier ne vous est affecté pour le moment.' : 'Aucun dossier ne correspond à ces critères.';
  }

  private charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.service
      .dossiers({
        filtre: this.filtre(),
        q: this.q().trim(),
        collaborateurId: this.collaborateurId(),
        expertId: this.expertId(),
        exercice: this.exercice(),
        statut: this.statut(),
        tri: this.tri(),
        page: this.pageCourante(),
        taille: this.taille,
      })
      .subscribe({
        next: (p) => {
          this.page.set(p);
          this.chargement.set(false);
        },
        error: (err) => {
          this.erreur.set(err.error?.message ?? 'Impossible de charger les dossiers.');
          this.chargement.set(false);
        },
      });
  }
}
