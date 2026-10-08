import { Component, input } from '@angular/core';
import { IconComponent } from './icon.component';

/** État vide explicite : une icône discrète, un titre, une phrase d'explication, et au besoin une action projetée. */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="vide">
      <app-icon [nom]="icone()" [taille]="20" />
      <p class="titre">{{ titre() }}</p>
      @if (detail()) {
        <p class="detail">{{ detail() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: [`
    .vide { display: flex; flex-direction: column; align-items: center; gap: var(--ui-s1); padding: var(--ui-s6) var(--ui-s4); text-align: center; color: var(--ui-text-3); }
    .titre { margin: var(--ui-s1) 0 0; font-size: var(--ui-fs-md); font-weight: var(--ui-fw-semibold); color: var(--ui-text); }
    .detail { margin: 0; font-size: var(--ui-fs-sm); color: var(--ui-text-2); }
  `],
})
export class EmptyStateComponent {
  readonly titre = input.required<string>();
  readonly detail = input<string | null>(null);
  readonly icone = input('check-circle');
}
