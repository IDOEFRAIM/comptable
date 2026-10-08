import { Component, input } from '@angular/core';

/** En-tête de section : titre, compteur discret, sous-titre éventuel ; les actions de la section se projettent à droite. */
@Component({
  selector: 'app-section-header',
  standalone: true,
  template: `
    <header class="entete-section">
      <div>
        <h3 class="ui-section-title">{{ titre() }}@if (compteur() !== null) { <span class="compteur">{{ compteur() }}</span> }</h3>
        @if (sousTitre()) {
          <p class="ui-meta sous-titre">{{ sousTitre() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </header>
  `,
  styles: [`
    .entete-section { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--ui-s4); margin-bottom: var(--ui-s3); }
    .compteur { margin-left: var(--ui-s2); font-weight: var(--ui-fw-medium); color: var(--ui-text-3); font-variant-numeric: tabular-nums; }
    .sous-titre { margin: var(--ui-s1) 0 0; }
    .actions { display: flex; align-items: center; gap: var(--ui-s2); }
  `],
})
export class SectionHeaderComponent {
  readonly titre = input.required<string>();
  readonly compteur = input<number | null>(null);
  readonly sousTitre = input<string | null>(null);
}
