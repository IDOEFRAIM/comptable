import { Injectable, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';

/** Évènement non standard (Chromium, Edge, Android) déclenché quand l'application peut être installée. */
interface EvenementInstallation extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const CLE_INVITATION = 'warimara.invitation-installation';
const PAUSE_INVITATION_MS = 7 * 24 * 3600 * 1000;

function estInstallee(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }
  return window.matchMedia?.('(display-mode: standalone)').matches === true || (navigator as { standalone?: boolean }).standalone === true;
}

function estAppareilApple(): boolean {
  if (typeof navigator === 'undefined') {
    return false;
  }
  const ua = navigator.userAgent;
  return /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
}

function invitationRecemmentRefusee(): boolean {
  try {
    const refusee = Number(localStorage.getItem(CLE_INVITATION));
    return refusee > 0 && Date.now() - refusee < PAUSE_INVITATION_MS;
  } catch {
    return false;
  }
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
  /** Heure à laquelle la connexion a été perdue (null quand on est en ligne). */
  readonly horsLigneDepuis = signal<number | null>(typeof navigator !== 'undefined' && !navigator.onLine ? Date.now() : null);
  readonly installable = signal(false);
  readonly miseAJourDisponible = signal(false);
  /** Vrai quand Warimara tourne déjà comme application installée (fenêtre à part, sans barre d'adresse). */
  readonly installee = signal(estInstallee());
  /** iPhone et iPad n'ont pas de bouton d'installation : il faut passer par « Partager ». */
  readonly sousIos = estAppareilApple();
  /** Le comptable a dit « Plus tard » : on ne le redemande pas avant {@link PAUSE_INVITATION_MS}. */
  readonly invitationMasquee = signal(invitationRecemmentRefusee());

  constructor() {
    window.addEventListener('online', () => {
      this.enLigne.set(true);
      this.horsLigneDepuis.set(null);
      if (this.miseAJour?.isEnabled) {
        this.miseAJour.checkForUpdate().catch(() => undefined);
      }
    });
    window.addEventListener('offline', () => {
      this.enLigne.set(false);
      this.horsLigneDepuis.set(Date.now());
    });
    window.addEventListener('beforeinstallprompt', (evenement) => {
      evenement.preventDefault();
      this.evenementInstallation = evenement as EvenementInstallation;
      this.installable.set(true);
    });
    window.addEventListener('appinstalled', () => {
      this.evenementInstallation = null;
      this.installable.set(false);
      this.installee.set(true);
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

  /** « Plus tard » : l'invitation disparaît et ne revient pas avant une semaine. */
  masquerInvitation(): void {
    this.invitationMasquee.set(true);
    try {
      localStorage.setItem(CLE_INVITATION, String(Date.now()));
    } catch {
      /* stockage indisponible : l'invitation reviendra au prochain chargement, sans conséquence */
    }
  }

  /** Active la nouvelle version téléchargée en arrière-plan puis recharge l'application. */
  appliquerMiseAJour(): void {
    if (!this.miseAJour?.isEnabled) {
      return;
    }
    this.miseAJour.activateUpdate().finally(() => document.location.reload());
  }
}
