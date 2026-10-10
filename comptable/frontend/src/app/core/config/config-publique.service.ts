import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

/** Réglages d'affichage fournis par le serveur (voir ConfigurationPubliqueController). */
export interface ConfigurationPublique {
  inscriptionOuverte: boolean;
  production: boolean;
  paiementEnLigneIndisponible: boolean;
}

/**
 * Tant que la configuration n'est pas connue (ou si le serveur ne répond pas), l'interface choisit le côté PRUDENT : pas de lien d'inscription, pas de paiement en ligne, pas d'outil de simulation.
 * Le serveur reste l'autorité : il refuse de toute façon les appels correspondants.
 */
@Injectable({ providedIn: 'root' })
export class ConfigPubliqueService {
  private readonly http = inject(HttpClient);

  readonly configuration = signal<ConfigurationPublique | null>(null);

  readonly inscriptionOuverte = computed(() => this.configuration()?.inscriptionOuverte === true);
  /** Le paiement fiscal en ligne n'est proposé que s'il est réellement disponible (jamais un paiement fictif en production). */
  readonly paiementEnLigneDisponible = computed(() => this.configuration() !== null && !this.configuration()!.paiementEnLigneIndisponible);
  /** Les outils de test (« Simuler confirmation / échec ») n'existent que hors production. */
  readonly outilsDeTestVisibles = computed(() => this.configuration() !== null && !this.configuration()!.production);

  constructor() {
    this.http.get<ConfigurationPublique>('/api/config').subscribe({
      next: (c) => this.configuration.set(c),
      error: () => this.configuration.set(null),
    });
  }
}
