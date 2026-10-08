import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { StatusComponent } from '../../shared/ui/status.component';
import { Dashboard, DossierSummary } from './cabinet-workspace.model';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { dateCourte, depuisJours, ilYA, libelleEtapes, routeDossier, routeSource } from './cabinet-workspace.util';

/**
 * Page d'accueil du cabinet : « qu'est-ce qui nécessite mon attention ? ». Une file de travail (À traiter) en premier, quelques indicateurs sobres, la file de revue de l'expert et les
 * échéances. Tout vient du backend (statuts, priorités, compteurs, libellés) : l'écran ne calcule aucune règle métier.
 */
@Component({
  selector: 'app-cabinet-workspace',
  standalone: true,
  imports: [RouterLink, StatusComponent, IconComponent, EmptyStateComponent, SectionHeaderComponent],
  template: `
    <div class="page-cabinet" data-zone="cabinet-workspace">
      <header class="entete-page">
        <div>
          <h1 class="ui-page-title">Cabinet</h1>
          <p class="ui-meta">{{ sousTitre() }}</p>
        </div>
        <div class="perimetre" role="group" aria-label="Périmètre">
          <button type="button" class="ui-tab-btn" [attr.aria-pressed]="mes()" (click)="changerPerimetre(true)" data-perimetre="MES_DOSSIERS">Mes dossiers</button>
          <button type="button" class="ui-tab-btn" [attr.aria-pressed]="!mes()" (click)="changerPerimetre(false)" data-perimetre="CABINET">Tout le cabinet</button>
        </div>
      </header>

      @if (erreur()) {
        <p class="ui-notice ui-notice--danger" role="alert">{{ erreur() }}</p>
      }

      @if (dashboard(); as d) {
        <dl class="resume" data-zone="resume">
          <a class="resume__item" [routerLink]="['/cabinet/dossiers']" [queryParams]="{ filtre: mes() ? 'MES_DOSSIERS' : 'TOUS' }" data-champ="resume-actifs">
            <dt>Dossiers actifs</dt><dd>{{ d.summary.dossiersActifs }}</dd>
          </a>
          <a class="resume__item" [routerLink]="['/cabinet/dossiers']" [queryParams]="{ filtre: 'A_TRAITER' }" data-champ="resume-a-traiter">
            <dt>Dossiers à traiter</dt><dd>{{ d.summary.aTraiter }}</dd>
          </a>
          <a class="resume__item" [routerLink]="['/cabinet/dossiers']" [queryParams]="{ filtre: 'EN_ATTENTE_CLIENT' }" data-champ="resume-attente-client">
            <dt>En attente client</dt><dd>{{ d.summary.enAttenteClient }}</dd>
          </a>
          <a class="resume__item" [routerLink]="['/cabinet/dossiers']" [queryParams]="{ filtre: 'PRETS_REVUE' }" data-champ="resume-prets-revue">
            <dt>Prêts pour revue</dt><dd>{{ d.summary.pretsPourRevue }}</dd>
          </a>
          <a class="resume__item" [routerLink]="['/cabinet/echeances']" data-champ="resume-echeances">
            <dt>Échéances proches</dt><dd>{{ d.summary.echeancesProches }}</dd>
          </a>
        </dl>

        <section class="ui-section" data-zone="a-traiter">
          <app-section-header titre="À traiter" [compteur]="d.workQueue.total">
            <a class="ui-btn ui-btn--secondary ui-btn--sm" routerLink="/cabinet/dossiers" [queryParams]="{ filtre: 'A_TRAITER' }">Voir les dossiers</a>
          </app-section-header>
          @if (d.workQueue.items.length) {
            <div class="ui-surface">
              <table class="ui-table file">
                <thead>
                  <tr><th>Entreprise</th><th>Problème</th><th>Domaine</th><th>Impact</th><th>Ancienneté</th><th>Responsable</th><th class="col-action"><span class="ui-sr-only">Action</span></th></tr>
                </thead>
                <tbody>
                  @for (i of d.workQueue.items; track i.id) {
                    <tr [class.bloquant]="i.priorite === 'BLOCKER'" [attr.data-item]="i.id" [attr.data-entreprise]="i.entrepriseNom">
                      <td class="entreprise">{{ i.entrepriseNom }}</td>
                      <td class="probleme">{{ i.titre }}</td>
                      <td class="ui-muted">{{ i.domaine }}</td>
                      <td [class.impact-fort]="i.priorite === 'BLOCKER'">{{ i.impact }}</td>
                      <td class="ui-muted">{{ depuis(i.ancienneteJours) }}</td>
                      <td class="ui-muted">{{ i.responsable?.nom ?? '—' }}</td>
                      <td class="col-action"><a class="ui-btn ui-btn--secondary ui-btn--sm" [routerLink]="source(i)" data-action="ouvrir-item">Ouvrir</a></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (d.workQueue.total > d.workQueue.items.length) {
              <p class="ui-meta suite">{{ d.workQueue.total - d.workQueue.items.length }} autres éléments à traiter dans les dossiers.</p>
            }
          } @else {
            <app-empty-state data-champ="rien-a-traiter" titre="Rien à traiter" detail="Aucune anomalie ne nécessite votre intervention pour le moment." />
          }
        </section>

        <div class="deux-colonnes">
          @if (expert) {
            <section class="ui-section" data-zone="a-revoir">
              <app-section-header titre="À revoir" [compteur]="d.reviewQueue.length">
                <a class="ui-btn ui-btn--tertiary ui-btn--sm" routerLink="/cabinet/dossiers" [queryParams]="{ filtre: 'PRETS_REVUE' }">Tout voir</a>
              </app-section-header>
              @if (d.reviewQueue.length) {
                <ul class="liste">
                  @for (r of d.reviewQueue; track r.exerciceId) {
                    <li [attr.data-revue]="r.entrepriseNom">
                      <div>
                        <p class="liste__titre">{{ r.entrepriseNom }}</p>
                        <p class="ui-meta"><app-status [statut]="r.statut" [texte]="r.statutLibelle" /> · {{ etapes(r) }} étapes prêtes</p>
                      </div>
                      <a class="ui-btn ui-btn--primary ui-btn--sm" [routerLink]="dossier(r)" data-action="ouvrir-revue">Ouvrir la revue</a>
                    </li>
                  }
                </ul>
              } @else {
                <app-empty-state icone="check-circle" titre="Aucun dossier à revoir" detail="Les dossiers prêts pour revue apparaîtront ici." />
              }
            </section>
          }
          <section class="ui-section" data-zone="echeances">
            <app-section-header titre="Échéances" [compteur]="d.deadlines.length">
              <a class="ui-btn ui-btn--tertiary ui-btn--sm" routerLink="/cabinet/echeances">Tout voir</a>
            </app-section-header>
            @if (d.deadlines.length) {
              <ul class="liste">
                @for (e of d.deadlines; track e.id) {
                  <li>
                    <div>
                      <p class="liste__titre">{{ e.libelle }}</p>
                      <p class="ui-meta">{{ e.entrepriseNom ?? '' }} · {{ e.typeLibelle }}</p>
                    </div>
                    <span class="echeance-date" [class.en-retard]="e.periode === 'EN_RETARD'">{{ date(e.date) }}</span>
                  </li>
                }
              </ul>
            } @else {
              <app-empty-state icone="calendar" titre="Aucune échéance" detail="Les échéances ouvertes du cabinet apparaîtront ici." />
            }
          </section>
        </div>
        <p class="ui-meta maj">Mis à jour {{ majIlYA() }}</p>
      } @else if (chargement()) {
        <p class="ui-meta" role="status">Chargement du cabinet…</p>
      }
    </div>
  `,
  styles: [
    `
      .page-cabinet { max-width: var(--ui-page-max); margin: 0 auto; padding: var(--ui-s5) var(--ui-s5) var(--ui-s7); display: flex; flex-direction: column; gap: var(--ui-s5); }
      .entete-page { display: flex; justify-content: space-between; align-items: flex-end; gap: var(--ui-s4); flex-wrap: wrap; }
      .entete-page p { margin: var(--ui-s1) 0 0; }
      .perimetre { display: inline-flex; padding: 2px; background: var(--ui-bg); border: 1px solid var(--ui-line); border-radius: var(--ui-r); }
      .ui-tab-btn { padding: var(--ui-s1) var(--ui-s3); border: 0; border-radius: 4px; background: transparent; color: var(--ui-text-2); font: inherit; font-size: var(--ui-fs-sm); font-weight: var(--ui-fw-medium); cursor: pointer; box-shadow: none; }
      .ui-tab-btn[aria-pressed='true'] { background: var(--ui-surface); color: var(--ui-text); box-shadow: 0 0 0 1px var(--ui-line-strong); }
      .resume { display: flex; flex-wrap: wrap; gap: var(--ui-s6); margin: 0; }
      .resume__item { display: flex; flex-direction: column; gap: var(--ui-s1); color: inherit; text-decoration: none; }
      .resume__item dt { font-size: var(--ui-fs-xs); font-weight: var(--ui-fw-semibold); letter-spacing: 0.04em; text-transform: uppercase; color: var(--ui-text-3); }
      .resume__item dd { margin: 0; font-size: var(--ui-fs-2xl); font-weight: var(--ui-fw-semibold); font-variant-numeric: tabular-nums; }
      .resume__item:hover dd { color: var(--ui-primary); }
      .file tr.bloquant td:first-child { box-shadow: inset 2px 0 0 var(--ui-danger); }
      .entreprise { font-weight: var(--ui-fw-semibold); white-space: nowrap; }
      .probleme { min-width: 240px; }
      .impact-fort { color: var(--ui-text); font-weight: var(--ui-fw-medium); }
      .col-action { width: 1%; text-align: right; white-space: nowrap; }
      .suite { margin: var(--ui-s3) 0 0; }
      .deux-colonnes { display: grid; grid-template-columns: repeat(auto-fit, minmax(380px, 1fr)); gap: var(--ui-s6); padding-top: var(--ui-s5); border-top: 1px solid var(--ui-line); }
      .deux-colonnes > .ui-section { margin-top: 0; padding-top: 0; border-top: 0; }
      .liste { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--ui-line); }
      .liste li { display: flex; justify-content: space-between; align-items: center; gap: var(--ui-s4); padding: var(--ui-s3) 0; border-bottom: 1px solid var(--ui-line); }
      .liste__titre { margin: 0; font-weight: var(--ui-fw-medium); }
      .liste p.ui-meta { margin: 2px 0 0; display: flex; align-items: center; gap: var(--ui-s1); }
      .echeance-date { font-variant-numeric: tabular-nums; color: var(--ui-text-2); font-size: var(--ui-fs-sm); white-space: nowrap; }
      .echeance-date.en-retard { color: var(--ui-danger); font-weight: var(--ui-fw-medium); }
      .maj { margin: 0; }
    `,
  ],
})
export class CabinetWorkspaceComponent implements OnInit {
  private readonly service = inject(CabinetWorkspaceService);
  private readonly tokens = inject(TokenStorageService);

  readonly expert = this.tokens.getRoles().includes('ROLE_EXPERT_COMPTABLE');
  readonly mes = signal(!this.expert);
  readonly dashboard = signal<Dashboard | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  private derniereMaj = new Date();

  readonly sousTitre = computed(() => (this.mes() ? 'Les dossiers qui vous sont affectés.' : 'Tous les dossiers du cabinet.'));

  depuis = depuisJours;
  date = dateCourte;
  source = routeSource;
  dossier = (r: DossierSummary) => routeDossier(r.statut, r.entrepriseId);
  etapes = libelleEtapes;

  ngOnInit(): void {
    this.charger();
  }

  changerPerimetre(mes: boolean): void {
    this.mes.set(mes);
    this.charger();
  }

  majIlYA(): string {
    return ilYA(this.derniereMaj.toISOString());
  }

  private charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.service.dashboard(this.mes()).subscribe({
      next: (d) => {
        this.dashboard.set(d);
        this.derniereMaj = new Date();
        this.chargement.set(false);
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Impossible de charger le cabinet.');
        this.chargement.set(false);
      },
    });
  }
}
