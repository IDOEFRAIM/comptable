import { Component, inject } from '@angular/core';
import { HorsLigneService } from './hors-ligne.service';

/** Bandeau d'état : mode hors ligne (lecture seule) et nouvelle version prête. */
@Component({
  selector: 'app-bandeau-connexion',
  standalone: true,
  template: `
    @if (!horsLigne.enLigne()) {
      <div class="bandeau bandeau-hors-ligne" role="status" aria-live="polite" data-zone="bandeau-hors-ligne">
        <strong>Mode hors ligne.</strong> Vous consultez les dernières données enregistrées sur cet appareil, en lecture seule. Les modifications redeviennent possibles dès le retour d’Internet.
      </div>
    }
    @if (horsLigne.miseAJourDisponible()) {
      <div class="bandeau bandeau-maj" role="status" aria-live="polite" data-zone="bandeau-mise-a-jour">
        Une nouvelle version de Warimara est prête.
        <button type="button" (click)="horsLigne.appliquerMiseAJour()" data-action="appliquer-mise-a-jour">Mettre à jour</button>
      </div>
    }
  `,
  styles: [
    `
      .bandeau { padding: 0.5rem 1.25rem; font-size: 0.8125rem; line-height: 1.4; display: flex; gap: 0.75rem; align-items: center; justify-content: center; flex-wrap: wrap; }
      .bandeau-hors-ligne { background: #fff4d6; color: #5c3b00; border-bottom: 1px solid #f2b705; }
      .bandeau-maj { background: var(--color-primary-light, #ecf6ef); color: var(--color-primary-active, #0b3f1d); border-bottom: 1px solid var(--color-primary-border, #b9ddc5); }
      .bandeau-maj button { border: 1px solid var(--color-primary, #14662f); background: var(--color-primary, #14662f); color: #fff; border-radius: 6px; padding: 0.2rem 0.7rem; font-size: 0.8125rem; cursor: pointer; }
    `,
  ],
})
export class BandeauConnexionComponent {
  readonly horsLigne = inject(HorsLigneService);
}
