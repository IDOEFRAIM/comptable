import { DatePipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FileAttenteService } from './file-attente.service';
import { HorsLigneService } from './hors-ligne.service';
import { SynchronisationAutoService } from './synchronisation-auto.service';

/** Pastille toujours visible dans l'entête : le comptable sait à tout moment s'il est connecté, depuis quand il ne l'est plus, et si ses données sont à jour. */
@Component({
  selector: 'app-etat-connexion',
  standalone: true,
  imports: [DatePipe, RouterLink],
  template: `
    <span class="etat" [class.hors]="!horsLigne.enLigne()" [class.sync]="sync.progression() !== null" role="status" aria-live="polite" data-zone="etat-connexion" [title]="detail()">
      <span class="point" aria-hidden="true"></span>
      <span class="texte">
        @if (!horsLigne.enLigne()) {
          Hors ligne
          @if (horsLigne.horsLigneDepuis(); as depuis) {
            <span class="precision">depuis {{ depuis | date: 'HH:mm' }}</span>
          }
        } @else if (texteMiseAJour()) {
          {{ texteMiseAJour() }}
        } @else {
          Connecté
          @if (sync.derniere(); as d) {
            <span class="precision">à jour {{ d | date: 'HH:mm' }}</span>
          }
        }
        @if (file.enAttente().length > 0) {
          <span class="attente">· {{ file.enAttente().length }} en attente</span>
        }
        @if (file.refuses().length > 0) {
          <a routerLink="/a-resoudre" class="a-resoudre" data-action="aller-a-resoudre-entete">· {{ file.refuses().length }} à résoudre</a>
        }
      </span>
    </span>
  `,
  styles: [
    `
      .etat { display: inline-flex; align-items: center; gap: 0.4rem; padding: 0.25rem 0.65rem; border-radius: 999px; font-size: 0.75rem; font-weight: 600; background: var(--color-success-bg, #ecf6ef); color: var(--color-success, #14662f); white-space: nowrap; }
      .point { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
      .hors { background: var(--color-warning-bg, #fff4d6); color: var(--color-warning, #8a5a00); }
      .sync { background: #eff8ff; color: #0b3b6e; }
      .sync .point { animation: pulse 1s ease-in-out infinite; }
      .precision, .attente { font-weight: 500; opacity: 0.85; }
      .a-resoudre { color: #b42318; font-weight: 700; text-decoration: underline; }
      @keyframes pulse { 50% { opacity: 0.3; } }
      @media (max-width: 760px) { .precision { display: none; } }
      @media (prefers-reduced-motion: reduce) { .sync .point { animation: none; } }
    `,
  ],
})
export class EtatConnexionComponent {
  readonly horsLigne = inject(HorsLigneService);
  readonly sync = inject(SynchronisationAutoService);
  readonly file = inject(FileAttenteService);

  readonly texteMiseAJour = computed(() => {
    const p = this.sync.progression();
    return p ? `Mise à jour ${Math.min(p.fait + 1, p.total)}/${p.total}…` : null;
  });

  readonly detail = computed(() =>
    this.horsLigne.enLigne()
      ? 'Connecté. Vos dossiers récents sont mis à jour automatiquement pour le travail sans Internet.'
      : 'Hors ligne : vous travaillez sur les dernières données enregistrées sur cet appareil. Vos saisies partiront au retour d’Internet.',
  );
}
