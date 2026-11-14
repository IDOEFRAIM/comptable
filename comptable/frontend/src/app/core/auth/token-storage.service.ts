import { Injectable } from '@angular/core';
import { viderCachesDonnees } from '../pwa/caches-donnees';
import { purgerFileAutresUtilisateurs } from '../pwa/file-attente.store';

const CLE_TOKEN = 'comptable.jwt';
const CLE_DERNIER_UTILISATEUR = 'comptable.dernier-utilisateur';
const CLE_EXPIREE = 'comptable.session-expiree';

/** Durée pendant laquelle une session expirée reste utilisable hors ligne (sur les dossiers déjà téléchargés). Au-delà, une connexion avec Internet est obligatoire. */
export const FENETRE_HORS_LIGNE_MS = 7 * 24 * 3600 * 1000;

interface PayloadJwt {
  email?: string;
  roles?: string[];
  mcp?: boolean;
}

/** Stockage du JWT. localStorage pour le MVP ; a durcir (cookie httpOnly) avant la mise en prod. */
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  getToken(): string | null {
    return localStorage.getItem(CLE_TOKEN);
  }

  setToken(token: string): void {
    const email = this.decoderPayload(token)?.email ?? null;
    // Les données gardées hors ligne ne sont supprimées que si une AUTRE personne se connecte : le même utilisateur qui se reconnecte (session expirée) retrouve son travail intact.
    if (localStorage.getItem(CLE_DERNIER_UTILISATEUR) !== email) {
      viderCachesDonnees();
    }
    localStorage.setItem(CLE_TOKEN, token);
    localStorage.removeItem(CLE_EXPIREE);
    if (email) {
      localStorage.setItem(CLE_DERNIER_UTILISATEUR, email);
    }
    // la file d'attente hors ligne de l'utilisateur précédent est supprimée ; celle du même utilisateur est conservée pour être envoyée
    purgerFileAutresUtilisateurs(email);
  }

  /** Déconnexion volontaire : tout ce qui est gardé hors ligne sur l'appareil est supprimé. */
  clear(): void {
    viderCachesDonnees();
    localStorage.removeItem(CLE_TOKEN);
    localStorage.removeItem(CLE_EXPIREE);
    localStorage.removeItem(CLE_DERNIER_UTILISATEUR);
  }

  /**
   * Le serveur a refusé la session (401 : expirée ou révoquée). On NE supprime PAS les données gardées hors ligne : sinon, si le réseau retombe avant la reconnexion, le comptable perd l'accès à son travail.
   * Il doit se reconnecter avec Internet ; sans Internet, il peut continuer (code PIN) pendant {@link FENETRE_HORS_LIGNE_MS}.
   */
  expirer(): void {
    if (this.getToken() && !localStorage.getItem(CLE_EXPIREE)) {
      localStorage.setItem(CLE_EXPIREE, String(Date.now()));
    }
  }

  sessionExpiree(): boolean {
    return localStorage.getItem(CLE_EXPIREE) !== null;
  }

  /** Session expirée mais encore dans la fenêtre de 7 jours : utilisable sans Internet. */
  peutContinuerHorsLigne(): boolean {
    const depuis = Number(localStorage.getItem(CLE_EXPIREE));
    return !!this.getToken() && this.sessionExpiree() && Date.now() - depuis < FENETRE_HORS_LIGNE_MS;
  }

  getEmail(): string | null {
    return this.decoderPayload(this.getToken())?.email ?? null;
  }

  /** Le jeton porte l'obligation de choisir un nouveau mot de passe (mot de passe temporaire ou réinitialisé). */
  doitChangerMotDePasse(): boolean {
    return this.decoderPayload(this.getToken())?.mcp === true;
  }

  getRoles(): string[] {
    return this.decoderPayload(this.getToken())?.roles ?? [];
  }

  private decoderPayload(token: string | null): PayloadJwt | null {
    if (!token) {
      return null;
    }
    try {
      const partiePayload = token.split('.')[1];
      return JSON.parse(atob(partiePayload.replace(/-/g, '+').replace(/_/g, '/')));
    } catch {
      return null;
    }
  }
}
