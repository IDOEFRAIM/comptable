export type TypeElementFile = 'DOCUMENT' | 'ECRITURE';
export type StatutElementFile = 'EN_ATTENTE' | 'EN_COURS' | 'REJETE';

/** Une saisie faite hors ligne, gardée sur l'appareil jusqu'à son envoi au serveur. */
export interface ElementFile {
  id: string;
  /** E-mail de l'utilisateur qui a saisi : seule sa session peut envoyer l'élément. */
  utilisateur: string;
  entrepriseId: string;
  type: TypeElementFile;
  /** Texte affiché dans la liste d'attente (ex. « Pièce : facture.pdf »). */
  libelle: string;
  creeLe: number;
  statut: StatutElementFile;
  tentatives: number;
  /** Message du serveur quand l'élément a été refusé. */
  erreur?: string;
  // DOCUMENT
  typeDocument?: string;
  nomFichier?: string;
  typeMime?: string;
  fichier?: Blob;
  // ECRITURE
  requete?: unknown;
}

const NOM_BASE = 'warimara-file-attente';
const NOM_STORE = 'elements';

function ouvrir(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB indisponible'));
      return;
    }
    const demande = indexedDB.open(NOM_BASE, 1);
    demande.onupgradeneeded = () => demande.result.createObjectStore(NOM_STORE, { keyPath: 'id' });
    demande.onsuccess = () => resolve(demande.result);
    demande.onerror = () => reject(demande.error);
  });
}

async function transaction<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const base = await ouvrir();
  try {
    return await new Promise<T>((resolve, reject) => {
      const requete = action(base.transaction(NOM_STORE, mode).objectStore(NOM_STORE));
      requete.onsuccess = () => resolve(requete.result);
      requete.onerror = () => reject(requete.error);
    });
  } finally {
    base.close();
  }
}

/** Stockage durable (IndexedDB) de la file d'attente hors ligne : survit à la fermeture de l'application et au redémarrage de l'appareil. */
export const fileAttenteStore = {
  ajouter: (element: ElementFile): Promise<IDBValidKey> => transaction('readwrite', (s) => s.put(element)),
  remplacer: (element: ElementFile): Promise<IDBValidKey> => transaction('readwrite', (s) => s.put(element)),
  supprimer: (id: string): Promise<undefined> => transaction('readwrite', (s) => s.delete(id)),
  async lister(): Promise<ElementFile[]> {
    const tous = await transaction<ElementFile[]>('readonly', (s) => s.getAll());
    return tous.sort((a, b) => a.creeLe - b.creeLe);
  },
  /**
   * Supprime tout ce qui appartient à un autre utilisateur que celui qui vient de se connecter : les saisies d'un cabinet ne doivent jamais être envoyées (ni lisibles) depuis la session
   * d'une autre personne sur le même appareil. Les éléments du même utilisateur sont conservés, y compris après déconnexion ou expiration de session.
   */
  async purgerSauf(utilisateur: string | null): Promise<void> {
    const tous = await this.lister();
    await Promise.all(tous.filter((e) => e.utilisateur !== utilisateur).map((e) => this.supprimer(e.id)));
  },
};

/** À appeler quand un utilisateur se connecte : retire de l'appareil la file d'attente des autres utilisateurs. Sans erreur si IndexedDB est indisponible. */
export function purgerFileAutresUtilisateurs(utilisateur: string | null): void {
  fileAttenteStore.purgerSauf(utilisateur).catch(() => undefined);
}
