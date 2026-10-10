import { Injectable, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';

/** Évènement non standard (Chromium, Edge, Android) déclenché quand l'application peut être installée. */
interface EvenementInstallation extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export const MESSAGE_HORS_LIGNE_ECRITURE =
  'Vous êtes hors ligne : cette action nécessite une connexion. Les pages déjà consultées restent lisibles en lecture seule ; reconnectez-vous à Internet pour modifier des données.';

/** État de connexion, installation de l'application et mises à jour du service worker. */
@Injectable({ providedIn: 'root' })
export class HorsLigneService {
  // absent des tests et du développement (aucun service worker) : le service reste utilisable
  private readonly miseAJour = inject(SwUpdate, { optional: true });
  private evenementInstallation: EvenementInstallation | null = null;

  readonly enLigne = signal(typeof navigator === 'undefined' ? true : navigator.onLine);
  readonly installable = signal(false);
  readonly miseAJourDisponible = signal(false);

  constructor() {
    window.addEventListener('online', () => {
      this.enLigne.set(true);
      if (this.miseAJour?.isEnabled) {
        this.miseAJour.checkForUpdate().catch(() => undefined);
      }
    });
    window.addEventListener('offline', () => this.enLigne.set(false));
    window.addEventListener('beforeinstallprompt', (evenement) => {
      evenement.preventDefault();
      this.evenementInstallation = evenement as EvenementInstallation;
      this.installable.set(true);
    });
    window.addEventListener('appinstalled', () => {
      this.evenementInstallation = null;
      this.installable.set(false);
    });
    if (this.miseAJour?.isEnabled) {
      this.miseAJour.versionUpdates.pipe(filter((e) => e.type === 'VERSION_READY')).subscribe(() => this.miseAJourDisponible.set(true));
    }
  }

  /** Ouvre la fenêtre d'installation du navigateur (le navigateur seul décide de la proposer). */
  async installer(): Promise<void> {
    const evenement = this.evenementInstallation;
    if (!evenement) {
      return;
    }
    await evenement.prompt();
    await evenement.userChoice;
    this.evenementInstallation = null;
    this.installable.set(false);
  }

  /** Active la nouvelle version téléchargée en arrière-plan puis recharge l'application. */
  appliquerMiseAJour(): void {
    if (!this.miseAJour?.isEnabled) {
      return;
    }
    this.miseAJour.activateUpdate().finally(() => document.location.reload());
  }
}
