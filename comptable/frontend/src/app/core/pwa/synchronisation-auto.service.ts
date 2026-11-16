import { Injectable, inject, signal } from '@angular/core';
import { TokenStorageService } from '../auth/token-storage.service';
import { DossierHorsLigneService } from './dossier-hors-ligne.service';
import { HorsLigneService } from './hors-ligne.service';

/** Un dossier téléchargé depuis moins de 4 h est considéré à jour : on ne re-télécharge pas (la data mobile coûte cher). */
export const FRAICHEUR_MS = 4 * 3600 * 1000;
export const MAX_DOSSIERS_AUTO = 3;
const INTERVALLE_MS = 15 * 60 * 1000;
const DELAI_DEMARRAGE_MS = 5000;

interface ConnexionNavigateur {
  saveData?: boolean;
  effectiveType?: string;
}

/**
 * Garde à jour, sans intervention, les dossiers récemment ouverts : le comptable n'a plus à penser à « Télécharger ce dossier » avant de partir. Se déclenche à l'ouverture d'un dossier, au retour d'Internet et
 * toutes les 15 minutes ; ignore les dossiers déjà frais et les connexions très lentes ou en économie de données.
 */
@Injectable({ providedIn: 'root' })
export class SynchronisationAutoService {
  private readonly horsLigne = inject(HorsLigneService);
  private readonly telechargement = inject(DossierHorsLigneService);
  private readonly tokens = inject(TokenStorageService);

  /** Avancement (fait / total) pendant une mise à jour, sinon null. */
  readonly progression = signal<{ fait: number; total: number } | null>(null);
  /** Date de la dernière mise à jour réussie d'un dossier récent. */
  readonly derniere = signal<number | null>(null);
  private demarre = false;

  /** À appeler une fois, au démarrage de l'espace de travail. */
  demarrer(): void {
    if (this.demarre || typeof window === 'undefined') {
      return;
    }
    this.demarre = true;
    this.derniere.set(this.derniereMiseAJour());
    window.addEventListener('online', () => void this.synchroniser());
    setInterval(() => void this.synchroniser(), INTERVALLE_MS);
    setTimeout(() => void this.synchroniser(), DELAI_DEMARRAGE_MS);
  }

  /** Un dossier vient d'être ouvert : il devient « récent » et est téléchargé s'il ne l'a jamais été ou s'il est périmé. */
  noterOuverture(entrepriseId: string): void {
    if (!entrepriseId) {
      return;
    }
    const recents = [entrepriseId, ...this.recents().filter((id) => id !== entrepriseId)].slice(0, MAX_DOSSIERS_AUTO);
    localStorage.setItem(this.cle(), JSON.stringify(recents));
    void this.synchroniser();
  }

  recents(): string[] {
    try {
      const brut = JSON.parse(localStorage.getItem(this.cle()) ?? '[]');
      return Array.isArray(brut) ? brut.filter((id): id is string => typeof id === 'string') : [];
    } catch {
      return [];
    }
  }

  /** Dossiers récents jamais téléchargés ou périmés. */
  aMettreAJour(): string[] {
    return this.recents().filter((id) => {
      const date = this.telechargement.derniereDate(id);
      return date === null || Date.now() - date > FRAICHEUR_MS;
    });
  }

  async synchroniser(): Promise<void> {
    if (this.progression() !== null || !this.peutSynchroniser()) {
      return;
    }
    const aFaire = this.aMettreAJour();
    if (aFaire.length === 0) {
      return;
    }
    this.progression.set({ fait: 0, total: aFaire.length });
    try {
      for (const id of aFaire) {
        if (!this.peutSynchroniser()) {
          break;
        }
        await this.telechargement.telecharger(id);
        this.progression.set({ fait: (this.progression()?.fait ?? 0) + 1, total: aFaire.length });
      }
    } finally {
      this.progression.set(null);
      this.derniere.set(this.derniereMiseAJour());
    }
  }

  private peutSynchroniser(): boolean {
    return !!this.tokens.getToken() && !this.tokens.sessionExpiree() && this.horsLigne.enLigne() && !this.telechargement.enCours() && !this.connexionLimitee();
  }

  private connexionLimitee(): boolean {
    const connexion = (navigator as Navigator & { connection?: ConnexionNavigateur }).connection;
    return connexion?.saveData === true || connexion?.effectiveType === 'slow-2g' || connexion?.effectiveType === '2g';
  }

  private derniereMiseAJour(): number | null {
    const dates = this.recents()
      .map((id) => this.telechargement.derniereDate(id))
      .filter((d): d is number => d !== null);
    return dates.length > 0 ? Math.min(...dates) : null;
  }

  private cle(): string {
    return `warimara.dossiers-recents.${this.tokens.getEmail() ?? ''}`;
  }
}
