import { Injectable } from '@angular/core';
import { viderCachesDonnees } from '../pwa/caches-donnees';
import { purgerFileAutresUtilisateurs } from '../pwa/file-attente.store';

const CLE_TOKEN = 'comptable.jwt';

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
    viderCachesDonnees(); // nouvelle session : aucune donnée de la précédente ne doit rester lisible hors ligne
    localStorage.setItem(CLE_TOKEN, token);
    // la file d'attente hors ligne de l'utilisateur précédent est supprimée ; celle du même utilisateur est conservée pour être envoyée
    purgerFileAutresUtilisateurs(this.getEmail());
  }

  clear(): void {
    viderCachesDonnees();
    localStorage.removeItem(CLE_TOKEN);
  }

  getEmail(): string | null {
    return this.decoderPayload()?.email ?? null;
  }

  /** Le jeton porte l'obligation de choisir un nouveau mot de passe (mot de passe temporaire ou réinitialisé). */
  doitChangerMotDePasse(): boolean {
    return this.decoderPayload()?.mcp === true;
  }

  getRoles(): string[] {
    return this.decoderPayload()?.roles ?? [];
  }

  private decoderPayload(): PayloadJwt | null {
    const token = this.getToken();
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
