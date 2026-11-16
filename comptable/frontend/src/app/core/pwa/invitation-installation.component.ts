import { Component, computed, inject } from '@angular/core';
import { HorsLigneService } from './hors-ligne.service';

/**
 * Propose l'installation de Warimara dès l'ouverture (page de connexion, puis dans l'application) : un seul bouton, sans
 * consigne technique. Elle disparaît une fois l'application installée, ou une semaine si le comptable répond « Plus tard ».
 * Sur iPhone/iPad, qui n'ont pas de bouton d'installation, elle indique le geste à faire.
 */
@Component({
  selector: 'app-invitation-installation',
  standalone: true,
  template: `
    @if (visible()) {
      <section class="invitation" role="region" aria-label="Installer Warimara" data-zone="invitation-installation">
        <div class="texte">
          <strong>Installez Warimara sur cet appareil</strong>
          <span>Il s’ouvre comme un logiciel, démarre plus vite et reste utilisable quand la connexion coupe.</span>
          @if (!horsLigne.installable()) {
            <span class="geste">Touchez <strong>Partager</strong>, puis <strong>Sur l’écran d’accueil</strong>.</span>
          }
        </div>
        <div class="actions">
          @if (horsLigne.installable()) {
            <button type="button" class="installer" (click)="horsLigne.installer()" data-action="installer-application-invitation">Installer</button>
          }
          <button type="button" class="plus-tard" (click)="horsLigne.masquerInvitation()" data-action="masquer-invitation">Plus tard</button>
        </div>
      </section>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .invitation {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.75rem 1rem;
        flex-wrap: wrap;
        padding: 0.75rem 1rem;
        border: 1px solid var(--color-primary, #146832);
        border-radius: 10px;
        background: var(--color-primary-light, #e8f4ec);
        color: var(--color-primary-active, #0f4f26);
        font-size: 0.8125rem;
        line-height: 1.4;
      }
      .texte {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
        flex: 1 1 14rem;
      }
      .texte strong {
        font-size: 0.9rem;
      }
      .actions {
        display: flex;
        gap: 0.5rem;
        align-items: center;
      }
      .installer {
        padding: 0.5rem 1.1rem;
        border-radius: 8px;
        border: 0;
        background: var(--color-primary, #146832);
        color: #fff;
        font-weight: 600;
        cursor: pointer;
      }
      .plus-tard {
        padding: 0.5rem 0.75rem;
        border: 0;
        background: transparent;
        color: inherit;
        text-decoration: underline;
        cursor: pointer;
      }
    `,
  ],
})
export class InvitationInstallationComponent {
  readonly horsLigne = inject(HorsLigneService);

  /** Proposée seulement quand on peut vraiment installer (le navigateur l'autorise, ou iPhone/iPad) et que ce n'est pas déjà fait. */
  readonly visible = computed(
    () => !this.horsLigne.installee() && !this.horsLigne.invitationMasquee() && (this.horsLigne.installable() || this.horsLigne.sousIos),
  );
}
