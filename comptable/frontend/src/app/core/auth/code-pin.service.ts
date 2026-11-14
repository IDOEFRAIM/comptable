import { Injectable, inject } from '@angular/core';
import { TokenStorageService } from './token-storage.service';

const ITERATIONS = 150_000;
export const MAX_ESSAIS_PIN = 5;
const CLE_DEVERROUILLE = 'comptable.pin-deverrouille';

export type ResultatPin = 'ok' | 'incorrect' | 'bloque';

function enBase64(octets: Uint8Array): string {
  return btoa(String.fromCharCode(...octets));
}

function deBase64(texte: string): Uint8Array {
  return Uint8Array.from(atob(texte), (c) => c.charCodeAt(0));
}

async function empreinte(pin: string, sel: Uint8Array): Promise<string> {
  const cle = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: sel as BufferSource, iterations: ITERATIONS }, cle, 256);
  return enBase64(new Uint8Array(bits));
}

/**
 * Code PIN de l'appareil : il protège l'accès hors ligne quand la session a expiré (le mot de passe ne peut pas être vérifié sans Internet). Seule une empreinte salée (PBKDF2) est gardée, jamais le code.
 * Après {@link MAX_ESSAIS_PIN} erreurs, l'accès hors ligne est coupé : une connexion avec Internet est alors obligatoire.
 */
@Injectable({ providedIn: 'root' })
export class CodePinService {
  private readonly tokens = inject(TokenStorageService);

  private cle(suffixe: string): string {
    return `comptable.pin.${suffixe}.${this.tokens.getEmail() ?? ''}`;
  }

  defini(): boolean {
    return localStorage.getItem(this.cle('empreinte')) !== null;
  }

  static valide(pin: string): boolean {
    return /^\d{4,8}$/.test(pin);
  }

  async definir(pin: string): Promise<void> {
    if (!CodePinService.valide(pin)) {
      throw new Error('Le code PIN doit contenir 4 à 8 chiffres.');
    }
    const sel = crypto.getRandomValues(new Uint8Array(16));
    localStorage.setItem(this.cle('sel'), enBase64(sel));
    localStorage.setItem(this.cle('empreinte'), await empreinte(pin, sel));
    localStorage.removeItem(this.cle('essais'));
  }

  retirer(): void {
    for (const s of ['sel', 'empreinte', 'essais']) {
      localStorage.removeItem(this.cle(s));
    }
  }

  essaisRestants(): number {
    return Math.max(0, MAX_ESSAIS_PIN - Number(localStorage.getItem(this.cle('essais')) ?? 0));
  }

  async verifier(pin: string): Promise<ResultatPin> {
    const sel = localStorage.getItem(this.cle('sel'));
    const attendu = localStorage.getItem(this.cle('empreinte'));
    if (!sel || !attendu) {
      return 'ok';
    }
    if (this.essaisRestants() === 0) {
      return 'bloque';
    }
    if ((await empreinte(pin, deBase64(sel))) === attendu) {
      localStorage.removeItem(this.cle('essais'));
      sessionStorage.setItem(CLE_DEVERROUILLE, '1');
      return 'ok';
    }
    localStorage.setItem(this.cle('essais'), String(MAX_ESSAIS_PIN - this.essaisRestants() + 1));
    return this.essaisRestants() === 0 ? 'bloque' : 'incorrect';
  }

  /** Sans code PIN défini, l'accès hors ligne n'est pas protégé (comme avant) ; avec un code, il faut l'avoir saisi dans cette session. */
  deverrouille(): boolean {
    return !this.defini() || sessionStorage.getItem(CLE_DEVERROUILLE) === '1';
  }

  /** Le code PIN est demandé à nouveau à la prochaine ouverture (fermeture de l'application, ou reconnexion). */
  verrouiller(): void {
    sessionStorage.removeItem(CLE_DEVERROUILLE);
  }
}
