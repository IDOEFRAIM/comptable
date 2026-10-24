import { HttpClient, HttpContext, HttpContextToken, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { TokenStorageService } from '../auth/token-storage.service';
import { normaliserErreur } from '../http/messages-erreur';
import { ElementFile, fileAttenteStore } from './file-attente.store';
import { HorsLigneService } from './hors-ligne.service';

/** Marque une requête rejouée par la synchronisation : l'intercepteur hors ligne ne doit pas la remettre en file. */
export const REJEU_FILE_ATTENTE = new HttpContextToken<boolean>(() => false);

const INTERVALLE_REESSAI_MS = 60_000;

function identifiant(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Saisies faites hors ligne, gardées sur l'appareil puis envoyées au retour de la connexion. Seules deux actions sont autorisées hors ligne, parce qu'elles sont sûres à rejouer plus tard :
 * - le dépôt d'une pièce (le serveur détecte lui-même les fichiers en double) ;
 * - la création d'une écriture en BROUILLON (elle ne compte dans aucun état tant qu'elle n'est pas validée en ligne).
 * Tout le reste (validation, clôture, banque, import, facturation…) exige une connexion. Chaque élément est envoyé un par un, dans l'ordre ; un refus du serveur (exercice clôturé, donnée invalide…) met
 * l'élément en « Refusé » avec le message du serveur, sans bloquer les suivants.
 */
@Injectable({ providedIn: 'root' })
export class FileAttenteService {
  private readonly http = inject(HttpClient);
  private readonly horsLigne = inject(HorsLigneService);
  private readonly tokens = inject(TokenStorageService);

  readonly elements = signal<ElementFile[]>([]);
  readonly synchronisation = signal(false);
  /** Résultat de la dernière synchronisation, affiché dans le bandeau. */
  readonly rapport = signal<string | null>(null);
  /** Confirmation brève après une mise en file (« Enregistré hors ligne… »). */
  readonly confirmation = signal<string | null>(null);

  readonly enAttente = computed(() => this.elements().filter((e) => e.statut !== 'REJETE'));
  readonly refuses = computed(() => this.elements().filter((e) => e.statut === 'REJETE'));

  constructor() {
    void this.charger().then(() => this.synchroniser());
    window.addEventListener('online', () => void this.synchroniser());
    setInterval(() => {
      if (this.enAttente().length > 0) {
        void this.synchroniser();
      }
    }, INTERVALLE_REESSAI_MS);
  }

  private utilisateur(): string {
    return this.tokens.getEmail() ?? '';
  }

  async charger(): Promise<void> {
    try {
      const tous = await fileAttenteStore.lister();
      const moi = this.utilisateur();
      // un élément resté « en cours » (fermeture pendant l'envoi) redevient simplement en attente
      this.elements.set(tous.filter((e) => e.utilisateur === moi).map((e) => (e.statut === 'EN_COURS' ? { ...e, statut: 'EN_ATTENTE' as const } : e)));
    } catch {
      this.elements.set([]);
    }
  }

  async enfilerDocument(entrepriseId: string, typeDocument: string, fichier: File): Promise<void> {
    await this.ajouter({
      id: identifiant(),
      utilisateur: this.utilisateur(),
      entrepriseId,
      type: 'DOCUMENT',
      libelle: `Pièce : ${fichier.name}`,
      creeLe: Date.now(),
      statut: 'EN_ATTENTE',
      tentatives: 0,
      typeDocument,
      nomFichier: fichier.name,
      typeMime: fichier.type,
      fichier,
    });
  }

  async enfilerEcriture(entrepriseId: string, requete: { libelle?: string; lignes?: { debit?: number; credit?: number }[] }): Promise<void> {
    const total = (requete.lignes ?? []).reduce((somme, ligne) => somme + (Number(ligne.debit) || 0), 0);
    await this.ajouter({
      id: identifiant(),
      utilisateur: this.utilisateur(),
      entrepriseId,
      type: 'ECRITURE',
      libelle: `Écriture en brouillon : ${requete.libelle || 'sans libellé'} (${total.toLocaleString('fr-FR')})`,
      creeLe: Date.now(),
      statut: 'EN_ATTENTE',
      tentatives: 0,
      requete,
    });
  }

  private async ajouter(element: ElementFile): Promise<void> {
    await fileAttenteStore.ajouter(element);
    this.elements.update((liste) => [...liste, element]);
    this.confirmation.set('Enregistré sur cet appareil : sera envoyé automatiquement au retour d’Internet.');
    setTimeout(() => this.confirmation.set(null), 6000);
  }

  async supprimer(id: string): Promise<void> {
    await fileAttenteStore.supprimer(id);
    this.elements.update((liste) => liste.filter((e) => e.id !== id));
  }

  async reessayer(id: string): Promise<void> {
    const element = this.elements().find((e) => e.id === id);
    if (!element) {
      return;
    }
    await this.maj({ ...element, statut: 'EN_ATTENTE', erreur: undefined });
    await this.synchroniser();
  }

  private async maj(element: ElementFile): Promise<void> {
    await fileAttenteStore.remplacer(element);
    this.elements.update((liste) => liste.map((e) => (e.id === element.id ? element : e)));
  }

  /** Envoie les éléments en attente, un par un. Sans effet hors ligne, sans session ou si une synchronisation est déjà en cours. */
  async synchroniser(): Promise<void> {
    if (this.synchronisation() || !this.horsLigne.enLigne() || !this.tokens.getToken() || this.enAttente().length === 0) {
      return;
    }
    this.synchronisation.set(true);
    let envoyes = 0;
    let refuses = 0;
    let interrompu: string | null = null;
    try {
      for (const element of this.enAttente()) {
        await this.maj({ ...element, statut: 'EN_COURS' });
        try {
          await firstValueFrom(this.envoyer(element));
          await this.supprimer(element.id);
          envoyes++;
        } catch (erreur) {
          const e = erreur instanceof HttpErrorResponse ? erreur : new HttpErrorResponse({ status: 0 });
          if (e.status === 0 || e.status === 401 || e.status === 502 || e.status === 503 || e.status === 504) {
            // réseau coupé, serveur indisponible ou session expirée : l'élément reste en attente, on réessaiera
            await this.maj({ ...element, statut: 'EN_ATTENTE', tentatives: element.tentatives + 1 });
            interrompu = e.status === 401 ? 'Session expirée : reconnectez-vous pour terminer l’envoi.' : 'Connexion instable : l’envoi reprendra automatiquement.';
            break;
          }
          await this.maj({ ...element, statut: 'REJETE', tentatives: element.tentatives + 1, erreur: normaliserErreur(e).error.message });
          refuses++;
        }
      }
    } finally {
      this.synchronisation.set(false);
    }
    const parties = [`${envoyes} élément(s) envoyé(s)`];
    if (refuses > 0) {
      parties.push(`${refuses} refusé(s) par le serveur (voir le détail)`);
    }
    if (interrompu) {
      parties.push(interrompu);
    }
    this.rapport.set(`Synchronisation : ${parties.join(' — ')}.`);
  }

  private envoyer(element: ElementFile) {
    const contexte = new HttpContext().set(REJEU_FILE_ATTENTE, true);
    const base = `/api/entreprises/${element.entrepriseId}`;
    if (element.type === 'DOCUMENT') {
      const formData = new FormData();
      formData.append('typeDocument', element.typeDocument ?? 'AUTRE');
      formData.append('fichier', new File([element.fichier as Blob], element.nomFichier ?? 'piece', { type: element.typeMime }));
      return this.http.post(`${base}/documents`, formData, { context: contexte });
    }
    return this.http.post(`${base}/ecritures`, element.requete, { context: contexte });
  }
}
