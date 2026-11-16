import { ElementFile } from './file-attente.store';

/** Brouillon à déposer dans un formulaire pour que le comptable corrige une saisie refusée, et page où l'ouvrir. */
export interface RepriseSaisie {
  /** Nom du formulaire dans les brouillons locaux (voir BrouillonLocalService). */
  formulaire: 'operation' | 'ecriture';
  valeurs: unknown;
  /** Route de la page de saisie, avec l'identifiant du dossier. */
  route: string[];
}

type Requete = Record<string, unknown>;

const texte = (v: unknown): string => (typeof v === 'string' ? v : '');
const nombre = (v: unknown): number => Number(v) || 0;

/**
 * Remet une saisie refusée dans son formulaire (le sens inverse de ce que fait la page à l'envoi), pour la corriger au lieu de tout ressaisir.
 * Renvoie null pour ce qui ne se corrige pas dans un formulaire (dépôt de pièce, création de tiers) : il reste « Réessayer » et « Abandonner ».
 */
export function reprendreSaisie(element: ElementFile): RepriseSaisie | null {
  const r = (element.requete ?? null) as Requete | null;
  if (!r) {
    return null;
  }
  if (element.type === 'ECRITURE') {
    const lignes = Array.isArray(r['lignes']) ? (r['lignes'] as Requete[]) : [];
    return {
      formulaire: 'ecriture',
      route: ['/entreprises', element.entrepriseId, 'ecritures', 'nouvelle'],
      valeurs: {
        journalId: texte(r['journalId']),
        dateEcriture: texte(r['dateEcriture']),
        libelle: texte(r['libelle']),
        numeroPiece: texte(r['numeroPiece']),
        lignes: lignes.map((l) => ({ compteId: texte(l['compteId']), libelle: texte(l['libelle']), debit: nombre(l['debit']), credit: nombre(l['credit']), tiersId: texte(l['tiersId']) })),
      },
    };
  }
  if (element.type === 'OPERATION') {
    const categorie = /operations\/(achat|vente|reglement|avoir)\/proposition$/.exec(element.chemin ?? '')?.[1];
    if (!categorie) {
      return null;
    }
    const type = texte(r['type']);
    return {
      formulaire: 'operation',
      route: ['/entreprises', element.entrepriseId, 'operations'],
      valeurs: {
        categorie: categorie.toUpperCase(),
        sens: type.endsWith('_FOURNISSEUR') || type === 'AVOIR_ACHAT' ? 'FOURNISSEUR' : 'CLIENT',
        date: texte(r['date']),
        libelle: texte(r['libelle']),
        reference: texte(r['reference']),
        montant: nombre(r['montantHt'] ?? r['montant']),
        compteId: texte(r['compteChargeId'] ?? r['compteProduitId'] ?? r['compteId']),
        montantTtcAttendu: r['montantTtcAttendu'] == null ? null : nombre(r['montantTtcAttendu']),
        moyenPaiement: texte(r['moyenPaiement']) || 'VIREMENT',
        natureAchat: texte(r['natureAchat']) || 'MARCHANDISE',
        natureVente: texte(r['natureVente']) || 'VENTE_MARCHANDISE',
        tiersId: texte(r['fournisseurId'] ?? r['clientId'] ?? r['tiersId']),
      },
    };
  }
  return null;
}

/** Une phrase de conseil, en mots de comptable, pour une saisie refusée : ce qui s'est passé et ce qu'il reste à faire. */
export function conseilPourRefus(element: ElementFile): string {
  const message = element.erreur ?? '';
  if (/cl[ôo]tur/i.test(message)) {
    return 'La période est clôturée. Reprenez la saisie avec une date dans un exercice ouvert, ou faites rouvrir l’exercice.';
  }
  switch (element.statutHttp) {
    case 403:
      return 'Vous n’avez plus accès à ce dossier. Demandez l’accès à votre cabinet, puis abandonnez cette saisie.';
    case 404:
      return 'Un élément utilisé n’existe plus (dossier, compte ou tiers). Reprenez la saisie avec des éléments qui existent.';
    case 409:
      return 'Le dossier a changé depuis votre saisie. Vérifiez-le, puis reprenez la saisie.';
    case 413:
      return 'Le fichier est trop volumineux. Abandonnez cet envoi et déposez une version plus légère.';
    case 429:
      return 'Trop d’envois à la fois. Attendez quelques minutes, puis réessayez.';
  }
  switch (element.type) {
    case 'TIERS':
      return 'Le tiers n’a pas pu être créé. Vérifiez la configuration des comptes clients et fournisseurs (Paramètres comptables), puis réessayez.';
    case 'DOCUMENT':
      return 'Le fichier a été refusé (format ou taille). Abandonnez cet envoi et redéposez la pièce.';
    default:
      return 'Le serveur a refusé cette saisie. Reprenez-la, corrigez-la (date, compte, montants), puis enregistrez de nouveau.';
  }
}
