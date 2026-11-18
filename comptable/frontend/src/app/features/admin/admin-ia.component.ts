import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminService, MOTIF_MIN, UsageIa, UsageIaCabinet } from './admin.service';
import { STYLES_ADMIN } from './admin-styles';

/**
 * Usage de l'IA par cabinet et plafond mensuel d'appels. Les jetons et le coût en argent ne sont PAS mesurés (le microservice IA ne les remonte pas) : on compte les appels, les échecs et la latence.
 * Un plafond atteint ne coupe que l'IA du cabinet : les fonctions reviennent à la saisie manuelle, comme quand le microservice est indisponible.
 */
@Component({
  selector: 'app-admin-ia',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  template: `
    <div class="page">
      <header>
        <h1>IA : usage par cabinet</h1>
        <p class="intro">
          Appels au microservice IA (extraction de pièces, rapprochement, Copilot…). <strong>Le coût en argent et les jetons ne sont pas mesurés</strong> : le plafond est donc un nombre d’appels par mois.
          Un cabinet qui atteint son plafond continue de travailler, sans l’IA.
        </p>
      </header>

      <form class="filtres" (ngSubmit)="charger()">
        <label>Mois<input type="month" name="mois" [(ngModel)]="mois" (change)="charger()" data-zone="filtre-mois" /></label>
      </form>

      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }

      @if (usage(); as u) {
        <div class="cartes" data-zone="totaux-ia">
          <div class="carte"><span class="secondaire">Appels</span><strong>{{ u.appels | number }}</strong></div>
          <div class="carte" [class.alerte]="u.echecs > 0"><span class="secondaire">En échec</span><strong>{{ u.echecs | number }}</strong></div>
          <div class="carte" [class.alerte]="u.refusesPlafond > 0"><span class="secondaire">Refusés (plafond)</span><strong>{{ u.refusesPlafond | number }}</strong></div>
          <div class="carte"><span class="secondaire">Sans cabinet (tâches de fond)</span><strong>{{ u.appelsSansCabinet | number }}</strong></div>
        </div>

        @if (panneau(); as c) {
          <form class="panneau" (ngSubmit)="enregistrer()" data-zone="edition-plafond">
            <strong>Plafond mensuel d’appels IA : {{ c.cabinet }}</strong>
            <label>Appels par mois (vide = pas de plafond)<input type="number" min="1" name="plafond" [(ngModel)]="plafondSaisi" data-zone="champ-plafond" /></label>
            <label>Motif (obligatoire, tracé dans le journal)<textarea name="motif" rows="2" [(ngModel)]="motif" data-zone="motif-action"></textarea></label>
            <p class="secondaire">Un plafond inférieur à l’usage du mois bloque l’IA de ce cabinet jusqu’au mois suivant. Rien d’autre n’est coupé.</p>
            <div class="actions">
              <button type="submit" class="principal" [disabled]="enCours() || motifTropCourt()" data-action="confirmer-plafond">{{ enCours() ? 'En cours…' : 'Enregistrer' }}</button>
              <button type="button" (click)="panneau.set(null)">Annuler</button>
            </div>
          </form>
        }

        <div class="tableau">
          <table data-zone="table-usage-ia">
            <thead><tr><th>Cabinet</th><th>Appels</th><th>Échecs</th><th>Latence moyenne</th><th>Plafond</th><th>Dernier appel</th><th></th></tr></thead>
            <tbody>
              @for (c of u.cabinets; track c.cabinetId) {
                <tr>
                  <td><strong>{{ c.cabinet }}</strong></td>
                  <td>
                    {{ c.appels | number }}
                    @if (c.plafond) {
                      <span class="secondaire"> / {{ c.plafond | number }}</span>
                      @if (c.appels >= c.plafond) {
                        <span class="badge suspendu" data-zone="plafond-atteint">Plafond atteint</span>
                      } @else if (c.appels >= c.plafond * 0.8) {
                        <span class="badge verrouille">Plus de 80 %</span>
                      }
                    }
                    @if (c.refusesPlafond > 0) {
                      <br /><span class="secondaire">{{ c.refusesPlafond }} refusé(s)</span>
                    }
                  </td>
                  <td>{{ c.echecs | number }}</td>
                  <td>{{ c.latenceMoyenneMs === null ? '—' : (c.latenceMoyenneMs | number) + ' ms' }}</td>
                  <td>{{ c.plafond ? (c.plafond | number) + ' / mois' : 'Aucun' }}</td>
                  <td>{{ c.dernierAppel ? (c.dernierAppel | date: 'dd/MM/yyyy HH:mm') : '—' }}</td>
                  <td><button type="button" class="petit" (click)="ouvrir(c)" data-action="plafond">Plafond…</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <h2>Par type d’appel</h2>
        @if (u.operations.length === 0) {
          <p class="vide" data-zone="aucun-appel">Aucun appel à l’IA ce mois-ci.</p>
        } @else {
          <div class="tableau">
            <table data-zone="table-operations-ia">
              <thead><tr><th>Type</th><th>Appels</th><th>Échecs</th><th>Latence moyenne</th></tr></thead>
              <tbody>
                @for (o of u.operations; track o.operation) {
                  <tr><td>{{ o.operation }}</td><td>{{ o.appels | number }}</td><td>{{ o.echecs | number }}</td><td>{{ o.latenceMoyenneMs === null ? '—' : (o.latenceMoyenneMs | number) + ' ms' }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </div>
  `,
  styles: [
    STYLES_ADMIN,
    `
      h2 { margin: 0.5rem 0 0; font-size: 1.05rem; }
      .cartes { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 0.75rem; }
      .carte { background: #fff; border: 1px solid var(--border-subtle, #eaecf0); border-radius: 10px; padding: 0.8rem 1rem; display: flex; flex-direction: column; gap: 0.2rem; }
      .carte strong { font-size: 1.5rem; }
      .carte.alerte { border-color: #f2b705; background: #fffaeb; }
    `,
  ],
})
export class AdminIaComponent implements OnInit {
  private readonly admin = inject(AdminService);

  readonly usage = signal<UsageIa | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly panneau = signal<UsageIaCabinet | null>(null);
  readonly enCours = signal(false);
  mois = '';
  plafondSaisi: number | null = null;
  motif = '';

  ngOnInit(): void {
    this.charger();
  }

  charger(): void {
    this.erreur.set(null);
    this.admin.usageIa(this.mois || undefined).subscribe({
      next: (u) => {
        this.usage.set(u);
        this.mois = u.mois;
      },
      error: (e) => this.erreur.set(e.error?.message ?? 'Impossible de charger l’usage de l’IA.'),
    });
  }

  motifTropCourt(): boolean {
    return this.motif.trim().length < MOTIF_MIN;
  }

  ouvrir(c: UsageIaCabinet): void {
    this.panneau.set(c);
    this.plafondSaisi = c.plafond;
    this.motif = '';
    this.erreur.set(null);
  }

  enregistrer(): void {
    const c = this.panneau();
    if (!c || this.motifTropCourt()) {
      return;
    }
    this.enCours.set(true);
    this.admin.modifierPlafondIa(c.cabinetId, this.plafondSaisi || null, this.motif.trim()).subscribe({
      next: () => {
        this.enCours.set(false);
        this.panneau.set(null);
        this.charger();
      },
      error: (e) => {
        this.enCours.set(false);
        this.erreur.set(e.error?.message ?? 'Le plafond n’a pas pu être modifié.');
      },
    });
  }
}
