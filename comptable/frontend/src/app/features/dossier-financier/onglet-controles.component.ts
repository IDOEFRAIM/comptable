import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { ExceptionDossier, OptionEvenement } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { formaterMontant } from './montant';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';

const CATEGORIES: Record<string, string> = {
  COMPTE_NON_MAPPE: 'Compte non mappé',
  MAPPING_AMBIGU: 'Mapping ambigu',
  ECART_RESULTAT: 'Écart de résultat',
  BILAN_DESEQUILIBRE: 'Bilan déséquilibré',
  ECART_TFT: 'Écart TFT',
  CAFG_NON_RESOLUE: 'CAFG non résolue',
  FLUX_NON_QUALIFIE: 'Flux de trésorerie non qualifié',
  NOTE_INCOMPLETE: 'Note incomplète',
  NOTE_A_DECIDER: 'Notes à décider',
  LIGNE_PARTIELLE: 'Ligne partiellement codée',
  EXERCICE: 'Exercice',
  COMPTABILITE: 'Comptabilité',
};

/**
 * Anomalies du dossier — travail par exception : d'abord ce qui nécessite une intervention, jamais la liste des lignes saines. La qualification d'un flux de trésorerie envoie
 * l'événement choisi (enum backend) ; le backend valide comptes, sens, présence de trésorerie et montant : une sélection ne suffit jamais.
 */
@Component({
  selector: 'app-onglet-controles',
  standalone: true,
  imports: [FormsModule, RouterLink, IconComponent, EmptyStateComponent, SectionHeaderComponent],
  template: `
    <section data-zone="anomalies">
      @if (exceptions().length === 0) {
        <app-empty-state data-champ="aucune-anomalie" titre="Aucune anomalie bloquante" detail="Votre dossier est prêt pour la prochaine étape." />
      } @else {
        <p class="ui-sr-only" data-champ="nb-interventions">{{ exceptions().length }} élément(s) nécessite(nt) votre intervention</p>
        @for (groupe of groupes(); track groupe.titre) {
          @if (groupe.items.length) {
            <div class="groupe">
              <app-section-header [titre]="groupe.titre" [compteur]="groupe.items.length" />
              <ul class="inbox">
                @for (e of groupe.items; track e.id) {
                  <li class="item" [class.item--bloquant]="e.severity === 'BLOCKING'" [attr.data-exception]="e.code">
                    <span class="item__icone" [class.item__icone--bloquant]="e.severity === 'BLOCKING'"><app-icon [nom]="e.severity === 'BLOCKING' ? 'alert-circle' : 'alert-triangle'" /></span>
                    <div class="item__corps">
                      <p class="item__titre">{{ e.title }}</p>
                      <p class="ui-meta item__meta">{{ categorie(e) }}@if (e.detail) { · {{ e.detail }} }</p>

                      @switch (e.code) {
                        @case ('UNQUALIFIED_CASH_EVENT') {
                          <div class="qualification" data-zone="qualification">
                            <table class="ui-table petit-tableau">
                              <thead><tr><th>Compte</th><th>Libellé</th><th class="ui-num">Débit</th><th class="ui-num">Crédit</th></tr></thead>
                              <tbody>
                                @for (l of e.target['lignes']; track $index) {
                                  <tr><td>{{ l.compte }}</td><td>{{ l.libelleCompte }}</td><td class="ui-num">{{ montant(l.debit) }}</td><td class="ui-num">{{ montant(l.credit) }}</td></tr>
                                }
                              </tbody>
                            </table>
                            <p class="ui-meta">Pièce {{ e.target['numeroPiece'] }} du {{ e.target['date'] }} · mouvement de trésorerie : {{ montant(e.target['tresorerie']) }} · lignes TFT concernées : {{ e.target['lignesTft'].join(', ') }}</p>
                            @if (permissions().prepare) {
                              <div class="ui-field">
                                <label class="ui-field__label" [for]="'evt-' + e.id">Nature du flux de trésorerie</label>
                                <div class="qualifier">
                                  <select class="ui-input" [id]="'evt-' + e.id" [ngModel]="choix()[e.id] || ''" (ngModelChange)="choisir(e.id, $event)" [attr.data-champ]="'evenement-' + e.id">
                                    <option value="">Choisir…</option>
                                    @for (o of options(); track o.code) {
                                      <option [value]="o.code">{{ o.libelle }}{{ o.ligneTft ? ' (' + o.ligneTft + ')' : '' }}</option>
                                    }
                                  </select>
                                  <button type="button" class="ui-btn ui-btn--primary" [disabled]="!choix()[e.id] || enCours()" (click)="qualifier(e)" data-action="qualifier">Qualifier</button>
                                </div>
                                <span class="ui-field__hint">Le serveur contrôle les comptes, le sens et le montant avant d’enregistrer.</span>
                              </div>
                              @if (erreurs()[e.id]; as m) {
                                <p class="ui-notice ui-notice--danger" data-champ="erreur-qualification">{{ m }}</p>
                              }
                            } @else {
                              <p class="ui-meta">La qualification est réservée aux collaborateurs et experts-comptables.</p>
                            }
                          </div>
                        }
                      }
                    </div>
                    <div class="item__actions">
                      @switch (e.code) {
                        @case ('UNMAPPED_ACCOUNT') {
                          <a [routerLink]="['/entreprises', entrepriseId(), 'plan-comptable']" class="ui-btn ui-btn--link">Voir le plan comptable</a>
                          <button type="button" class="ui-btn ui-btn--link" (click)="aller.emit({ onglet: e.tab })">Voir l’état concerné</button>
                        }
                        @case ('REQUIRED_NOTE_INCOMPLETE') {
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="aller.emit({ onglet: 'NOTES', note: e.target['noteCode'] })" data-action="ouvrir-note">Ouvrir la note {{ e.target['noteCode'] }}</button>
                        }
                        @case ('NOTES_CANDIDATES') {
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="aller.emit({ onglet: 'NOTES' })">Voir les notes</button>
                        }
                        @case ('NOTES_A_DECIDER') {
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="aller.emit({ onglet: 'NOTES' })">Voir les notes</button>
                        }
                        @case ('EXERCICE_OUVERT') {
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="aller.emit({ onglet: 'VERSION_FINALE' })">Aller à la clôture</button>
                        }
                        @case ('UNQUALIFIED_CASH_EVENT') {}
                        @default {
                          @if (e.tab && e.tab !== 'VUE_ENSEMBLE') {
                            <button type="button" class="ui-btn ui-btn--link" (click)="aller.emit({ onglet: e.tab })">Voir l’état concerné</button>
                          }
                        }
                      }
                    </div>
                  </li>
                }
              </ul>
            </div>
          }
        }
      }
      @if (resolus().length) {
        <div class="groupe">
          <app-section-header titre="Résolus" [compteur]="resolus().length" sousTitre="Traités pendant cette session." />
          <ul class="inbox">
            @for (r of resolus(); track r) {
              <li class="item item--resolu"><span class="item__icone item__icone--ok"><app-icon nom="check" /></span><div class="item__corps"><p class="item__titre">{{ r }}</p></div></li>
            }
          </ul>
        </div>
      }
    </section>
  `,
  styles: [
    `
      .groupe { margin-bottom: var(--ui-s6); }
      .inbox { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--ui-line); }
      .item { display: grid; grid-template-columns: auto 1fr auto; align-items: start; gap: var(--ui-s3); padding: var(--ui-s4); border-bottom: 1px solid var(--ui-line); background: var(--ui-surface); box-shadow: inset 2px 0 0 transparent; }
      .item--bloquant { box-shadow: inset 2px 0 0 var(--ui-danger); }
      .item__icone { display: inline-flex; padding-top: 2px; color: var(--ui-warning); }
      .item__icone--bloquant { color: var(--ui-danger); }
      .item__icone--ok { color: var(--ui-success); }
      .item__titre { margin: 0; font-size: var(--ui-fs-md); font-weight: var(--ui-fw-medium); color: var(--ui-text); }
      .item__meta { margin: 2px 0 0; }
      .item--resolu .item__titre { color: var(--ui-text-2); font-weight: var(--ui-fw-regular); }
      .item__actions { display: flex; align-items: center; gap: var(--ui-s4); }
      .qualification { margin-top: var(--ui-s3); padding-top: var(--ui-s3); border-top: 1px solid var(--ui-line); }
      .petit-tableau { margin-bottom: var(--ui-s2); }
      .qualifier { display: flex; gap: var(--ui-s2); align-items: center; max-width: 560px; }
    `,
  ],
})
export class OngletControlesComponent implements OnInit {
  private readonly service = inject(DossierFinancierService);

  readonly entrepriseId = input.required<string>();
  readonly exceptions = input.required<ExceptionDossier[]>();
  readonly permissions = input.required<{ prepare: boolean; review: boolean; finalise: boolean }>();
  readonly aller = output<{ onglet: string; note?: string }>();
  readonly modifiee = output<void>();

  readonly options = signal<OptionEvenement[]>([]);
  readonly choix = signal<Record<string, string>>({});
  readonly erreurs = signal<Record<string, string>>({});
  readonly enCours = signal(false);
  readonly resolus = signal<string[]>([]);
  readonly groupes = computed(() => [
    { titre: 'Bloquants', items: this.exceptions().filter((e) => e.severity === 'BLOCKING') },
    { titre: 'Avertissements', items: this.exceptions().filter((e) => e.severity !== 'BLOCKING') },
  ]);
  readonly aDesFlux = computed(() => this.exceptions().some((e) => e.code === 'UNQUALIFIED_CASH_EVENT'));

  montant = formaterMontant;

  ngOnInit(): void {
    this.service.evenementsTresorerie(this.entrepriseId()).subscribe({ next: (o) => this.options.set(o), error: () => this.options.set([]) });
  }

  categorie(e: ExceptionDossier): string {
    return CATEGORIES[e.category] ?? e.category;
  }

  choisir(id: string, code: string): void {
    this.choix.update((c) => ({ ...c, [id]: code }));
    this.erreurs.update((m) => ({ ...m, [id]: '' }));
  }

  qualifier(e: ExceptionDossier): void {
    const evenement = this.choix()[e.id];
    this.enCours.set(true);
    this.service
      .qualifier(this.entrepriseId(), e.target['ecritureId'], evenement)
      .pipe(finalize(() => this.enCours.set(false)))
      .subscribe({
        next: () => {
          this.resolus.update((r) => [...r, e.title]);
          this.modifiee.emit();
        },
        error: (err) => this.erreurs.update((m) => ({ ...m, [e.id]: err.error?.message ?? 'Qualification refusée par le serveur.' })),
      });
  }
}
