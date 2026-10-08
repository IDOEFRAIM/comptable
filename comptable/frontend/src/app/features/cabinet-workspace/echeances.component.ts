import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { EntrepriseService } from '../entreprises/entreprise.service';
import { Echeances, PeriodeEcheance } from './cabinet-workspace.model';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { dateCourte } from './cabinet-workspace.util';

const PERIODES: { code: PeriodeEcheance; libelle: string }[] = [
  { code: 'EN_RETARD', libelle: 'En retard' },
  { code: 'AUJOURDHUI', libelle: 'Aujourd’hui' },
  { code: 'CETTE_SEMAINE', libelle: 'Cette semaine' },
  { code: 'A_VENIR', libelle: 'À venir' },
];

const TYPES = [
  { code: 'CLOTURE_MENSUELLE', libelle: 'Clôture mensuelle' },
  { code: 'CLOTURE_ANNUELLE', libelle: 'Clôture annuelle' },
  { code: 'RELANCE_CLIENT', libelle: 'Relance client' },
  { code: 'ECHEANCE_INTERNE', libelle: 'Échéance interne' },
];

/** Échéances du cabinet : en retard, aujourd'hui, cette semaine, à venir. Le classement par période vient du backend ; l'écran ne compare jamais de dates. */
@Component({
  selector: 'app-echeances',
  standalone: true,
  imports: [FormsModule, RouterLink, EmptyStateComponent, SectionHeaderComponent],
  template: `
    <div class="page-echeances" data-zone="echeances-page">
      <header class="entete-page">
        <h1 class="ui-page-title">Échéances</h1>
        <button type="button" class="ui-btn ui-btn--secondary" (click)="formulaire.set(!formulaire())" data-action="ajouter-echeance">Ajouter une échéance</button>
      </header>

      @if (formulaire()) {
        <form class="ui-surface formulaire" (ngSubmit)="creer()" data-zone="formulaire-echeance">
          <div class="ui-field"><label class="ui-field__label" for="e-entreprise">Entreprise</label>
            <select id="e-entreprise" name="entreprise" class="ui-input" [(ngModel)]="nouvelle.entrepriseId" required>
              <option value="">Choisir…</option>
              @for (e of entreprises(); track e.id) { <option [value]="e.id">{{ e.raisonSociale }}</option> }
            </select></div>
          <div class="ui-field"><label class="ui-field__label" for="e-type">Type</label>
            <select id="e-type" name="type" class="ui-input" [(ngModel)]="nouvelle.type">
              @for (t of types; track t.code) { <option [value]="t.code">{{ t.libelle }}</option> }
            </select></div>
          <div class="ui-field"><label class="ui-field__label" for="e-libelle">Libellé</label>
            <input id="e-libelle" name="libelle" class="ui-input" [(ngModel)]="nouvelle.libelle" required /></div>
          <div class="ui-field"><label class="ui-field__label" for="e-date">Date</label>
            <input id="e-date" name="date" type="date" class="ui-input" [(ngModel)]="nouvelle.date" required /></div>
          <button type="submit" class="ui-btn ui-btn--primary" [disabled]="!nouvelle.entrepriseId || !nouvelle.libelle || !nouvelle.date">Enregistrer</button>
        </form>
      }
      @if (erreur()) { <p class="ui-notice ui-notice--danger" role="alert">{{ erreur() }}</p> }

      <nav class="ui-tabs" role="tablist" aria-label="Période">
        @for (p of periodes; track p.code) {
          <button type="button" role="tab" class="ui-tab" [attr.aria-selected]="periode() === p.code" (click)="periode.set(p.code)" [attr.data-periode]="p.code">
            {{ p.libelle }} <span class="ui-tab__count">{{ compte(p.code) }}</span>
          </button>
        }
      </nav>

      @if (visibles().length) {
        <div class="ui-surface">
          <table class="ui-table">
            <thead><tr><th>Date</th><th>Échéance</th><th>Entreprise</th><th>Type</th><th>Responsable</th><th class="col-action"><span class="ui-sr-only">Action</span></th></tr></thead>
            <tbody>
              @for (e of visibles(); track e.id) {
                <tr [attr.data-echeance]="e.libelle">
                  <td class="date">{{ date(e.date) }}</td>
                  <td>{{ e.libelle }}</td>
                  <td>@if (e.entrepriseNom) { <a [routerLink]="['/entreprises', e.entrepriseId, 'tableau-de-bord']">{{ e.entrepriseNom }}</a> } @else { — }</td>
                  <td class="ui-muted">{{ e.typeLibelle }}</td>
                  <td class="ui-muted">{{ e.responsable?.nom ?? '—' }}</td>
                  <td class="col-action"><button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="terminer(e.id)" data-action="terminer-echeance">Terminée</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <app-empty-state icone="calendar" titre="Aucune échéance" detail="Rien à signaler pour cette période." />
      }
    </div>
  `,
  styles: [
    `
      .page-echeances { max-width: var(--ui-page-max); margin: 0 auto; padding: var(--ui-s5) var(--ui-s5) var(--ui-s7); display: flex; flex-direction: column; gap: var(--ui-s4); }
      .entete-page { display: flex; align-items: center; justify-content: space-between; gap: var(--ui-s4); }
      .formulaire { display: flex; flex-wrap: wrap; align-items: flex-end; gap: var(--ui-s3); padding: var(--ui-s4); }
      .formulaire .ui-field { margin: 0; min-width: 180px; }
      .date { white-space: nowrap; }
      .col-action { width: 1%; text-align: right; white-space: nowrap; }
    `,
  ],
})
export class EcheancesComponent implements OnInit {
  private readonly service = inject(CabinetWorkspaceService);
  private readonly entreprisesService = inject(EntrepriseService);

  readonly periodes = PERIODES;
  readonly types = TYPES;
  readonly donnees = signal<Echeances | null>(null);
  readonly periode = signal<PeriodeEcheance>('EN_RETARD');
  readonly entreprises = signal<{ id: string; raisonSociale: string }[]>([]);
  readonly formulaire = signal(false);
  readonly erreur = signal<string | null>(null);
  nouvelle = { entrepriseId: '', type: 'ECHEANCE_INTERNE', libelle: '', date: '' };

  readonly visibles = computed(() => (this.donnees()?.items ?? []).filter((e) => e.periode === this.periode()));
  date = dateCourte;

  ngOnInit(): void {
    this.charger();
    this.entreprisesService.lister().subscribe({ next: (l) => this.entreprises.set(l), error: () => this.entreprises.set([]) });
  }

  compte(p: PeriodeEcheance): number {
    const d = this.donnees();
    return !d ? 0 : p === 'EN_RETARD' ? d.enRetard : p === 'AUJOURDHUI' ? d.aujourdhui : p === 'CETTE_SEMAINE' ? d.cetteSemaine : d.aVenir;
  }

  creer(): void {
    this.erreur.set(null);
    this.service.creerEcheance({ entrepriseId: this.nouvelle.entrepriseId, type: this.nouvelle.type, libelle: this.nouvelle.libelle, date: this.nouvelle.date }).subscribe({
      next: () => {
        this.nouvelle = { entrepriseId: '', type: 'ECHEANCE_INTERNE', libelle: '', date: '' };
        this.formulaire.set(false);
        this.charger();
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Échéance non enregistrée.'),
    });
  }

  terminer(id: string): void {
    this.service.modifierEcheance(id, { statut: 'FAITE' }).subscribe({ next: () => this.charger(), error: (err) => this.erreur.set(err.error?.message ?? 'Modification impossible.') });
  }

  private charger(): void {
    this.service.echeances(null, false).subscribe({ next: (d) => this.donnees.set(d), error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de charger les échéances.') });
  }
}
