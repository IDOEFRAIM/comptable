import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface RefElement {
  id: string;
}
interface Exercice extends RefElement {
  statut?: string;
}

/** Pages de lecture d'un dossier gardées pour le hors ligne (toutes dans la liste blanche de cache-lectures.interceptor.ts). */
const LECTURES = [
  '',
  '/tableau-de-bord',
  '/documents',
  '/demandes-pieces/collecte',
  '/demandes-pieces/collecte/a-demander',
  '/demandes-pieces/collecte/synthese',
  '/comptes',
  '/journaux',
  '/exercices',
  '/tiers',
  '/demandes-pieces',
  '/ecritures',
  '/plan-comptable',
  '/plan-comptable/arbre',
  '/articles',
  '/commandes-achat',
  '/factures',
  '/factures-avoir',
  '/immobilisations',
  '/inventaires',
  '/regularisations',
  '/recouvrement/risques',
  '/tresorerie/position',
  '/revue/synthese',
  '/revue/espace',
  '/banque/synthese',
  '/fiscalite/tva/periodes',
];
const ETATS = ['dossier-financier', 'compte-resultat', 'bilan', 'tft', 'notes'];
const MAX_DETAILS_ECRITURES = 150;

export interface BilanTelechargement {
  reussies: number;
  echecs: number;
  date: number;
}

function cleStockage(entrepriseId: string): string {
  return `warimara.hors-ligne.dossier.${entrepriseId}`;
}

/**
 * « Télécharger ce dossier pour le hors ligne » : charge d'un coup les pages de lecture du dossier pour qu'elles restent consultables sans Internet (les lectures réussies sont gardées par
 * cache-lectures.interceptor.ts). Une page absente ou en erreur est simplement comptée comme échec : le téléchargement continue.
 */
@Injectable({ providedIn: 'root' })
export class DossierHorsLigneService {
  private readonly http = inject(HttpClient);
  readonly enCours = signal(false);

  derniereDate(entrepriseId: string): number | null {
    const brut = localStorage.getItem(cleStockage(entrepriseId));
    return brut ? Number(brut) : null;
  }

  async telecharger(entrepriseId: string): Promise<BilanTelechargement> {
    this.enCours.set(true);
    let reussies = 0;
    let echecs = 0;
    const lire = async <T>(chemin: string, params?: HttpParams): Promise<T | null> => {
      try {
        const corps = await firstValueFrom(this.http.get<T>(`/api/entreprises/${entrepriseId}${chemin}`, { params }));
        reussies++;
        return corps;
      } catch {
        echecs++;
        return null;
      }
    };
    try {
      const resultats = new Map<string, unknown>();
      for (const chemin of LECTURES) {
        resultats.set(chemin, await lire<unknown>(chemin));
      }
      // vue par défaut de la page Documents (mois courant, 1re page de 20) : mêmes paramètres, dans le même ordre, que document-hub.component.ts, sinon la clé de cache ne correspond pas
      const mois = new Date().toISOString().slice(0, 7);
      await lire<unknown>('/documents/hub/synthese', new HttpParams().set('periode', mois));
      await lire<unknown>('/documents/hub', new HttpParams().set('periode', mois).set('page', '0').set('taille', '20'));
      const exercices = (resultats.get('/exercices') as Exercice[] | null) ?? [];
      const exercice = exercices.find((e) => e.statut === 'OUVERT') ?? exercices[0];
      if (exercice) {
        for (const etat of ETATS) {
          await lire<unknown>(`/etats-financiers-syscohada/${etat}`, new HttpParams().set('exerciceId', exercice.id));
        }
      }
      const ecritures = (resultats.get('/ecritures') as RefElement[] | null) ?? [];
      for (const ecriture of ecritures.slice(0, MAX_DETAILS_ECRITURES)) {
        await lire<unknown>(`/ecritures/${ecriture.id}`);
      }
    } finally {
      this.enCours.set(false);
    }
    const date = Date.now();
    if (reussies > 0) {
      localStorage.setItem(cleStockage(entrepriseId), String(date));
    }
    return { reussies, echecs, date };
  }
}
