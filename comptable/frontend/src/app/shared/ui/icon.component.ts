import { Component, computed, input } from '@angular/core';

/** Jeu d'icônes unique (style Feather : trait 1.8, extrémités arrondies). Aucune icône emoji dans l'application. */
const CHEMINS: Record<string, string> = {
  check: 'M20 6 9 17l-5-5',
  'check-circle': 'M22 11.08V12a10 10 0 1 1-5.93-9.14M22 4 12 14.01l-3-3',
  'alert-circle': 'M12 8v4M12 16h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z',
  'alert-triangle': 'M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01',
  x: 'M18 6 6 18M6 6l12 12',
  'x-circle': 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0zM15 9l-6 6M9 9l6 6',
  lock: 'M5 11h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM8 11V7a4 4 0 0 1 8 0v4',
  edit: 'M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z',
  minus: 'M5 12h14',
  circle: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z',
  more: 'M12 12h.01M19 12h.01M5 12h.01',
  'chevron-right': 'm9 18 6-6-6-6',
  'chevron-down': 'm6 9 6 6 6-6',
  'arrow-right': 'M5 12h14M12 5l7 7-7 7',
  refresh: 'M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15',
  eye: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6',
  clock: 'M12 6v6l4 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z',
  search: 'M21 21l-4.35-4.35M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z',
  calendar: 'M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z',
  inbox: 'M22 12h-6l-2 3h-4l-2-3H2M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z',
};

@Component({
  selector: 'app-icon',
  standalone: true,
  template: `<svg [attr.width]="taille()" [attr.height]="taille()" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path [attr.d]="chemin()" /></svg>`,
  styles: [`:host { display: inline-flex; flex: none; line-height: 0; }`],
})
export class IconComponent {
  readonly nom = input.required<string>();
  readonly taille = input(16);
  readonly chemin = computed(() => CHEMINS[this.nom()] ?? CHEMINS['circle']);
}
