import { Injectable, inject } from '@angular/core';
import { TokenStorageService } from '../auth/token-storage.service';
import { PREFIXE_BROUILLON } from './brouillons-stockage';

/** Un brouillon plus vieux que 7 jours est ignoré (et supprimé) : il ne correspond plus à une saisie en cours. */
export const DUREE_MAX_BROUILLON_MS = 7 * 24 * 3600 * 1000;

export interface BrouillonLu<T> {
  valeurs: T;
  /** Moment du dernier enregistrement. */
  le: number;
}

/**
 * Enregistrement d'une saisie EN COURS sur l'appareil, à chaque modification, pour survivre à une coupure de courant, à la fermeture de l'onglet ou à un plantage : le comptable retrouve sa saisie telle
 * qu'il l'a laissée. Un brouillon est propre à un utilisateur, à un dossier et à un formulaire ; il est supprimé dès que la saisie est enregistrée, à la déconnexion et quand une autre personne se connecte.
 */
@Injectable({ providedIn: 'root' })
export class BrouillonLocalService {
  private readonly tokens = inject(TokenStorageService);

  private cle(formulaire: string, entrepriseId: string): string {
    return `${PREFIXE_BROUILLON}${this.tokens.getEmail() ?? ''}.${entrepriseId}.${formulaire}`;
  }

  sauver(formulaire: string, entrepriseId: string, valeurs: unknown): void {
    try {
      localStorage.setItem(this.cle(formulaire, entrepriseId), JSON.stringify({ le: Date.now(), valeurs }));
    } catch {
      // stockage plein ou indisponible : la saisie continue normalement, simplement sans filet de sécurité
    }
  }

  lire<T>(formulaire: string, entrepriseId: string): BrouillonLu<T> | null {
    try {
      const cle = this.cle(formulaire, entrepriseId);
      const brut = localStorage.getItem(cle);
      if (!brut) {
        return null;
      }
      const lu = JSON.parse(brut) as { le?: number; valeurs?: T };
      if (typeof lu.le !== 'number' || lu.valeurs === undefined || Date.now() - lu.le > DUREE_MAX_BROUILLON_MS) {
        localStorage.removeItem(cle);
        return null;
      }
      return { valeurs: lu.valeurs, le: lu.le };
    } catch {
      return null;
    }
  }

  effacer(formulaire: string, entrepriseId: string): void {
    try {
      localStorage.removeItem(this.cle(formulaire, entrepriseId));
    } catch {
      // rien à effacer
    }
  }
}
