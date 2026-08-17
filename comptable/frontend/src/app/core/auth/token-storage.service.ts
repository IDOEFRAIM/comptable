import { Injectable } from '@angular/core';

const CLE_TOKEN = 'comptable.jwt';

interface PayloadJwt {
  email?: string;
  roles?: string[];
}

/** Stockage du JWT. localStorage pour le MVP ; a durcir (cookie httpOnly) avant la mise en prod. */
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  getToken(): string | null {
    return localStorage.getItem(CLE_TOKEN);
  }

  setToken(token: string): void {
    localStorage.setItem(CLE_TOKEN, token);
  }

  clear(): void {
    localStorage.removeItem(CLE_TOKEN);
  }

  getEmail(): string | null {
    return this.decoderPayload()?.email ?? null;
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
