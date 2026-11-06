import { GUIDES_TACHES, GuideTache, PAGES_CLIENT } from './guides-taches';

/** Minuscules sans accents ni ponctuation : « Facturé ! » et « facture » se valent. */
export function normaliser(texte: string): string {
  return texte
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[’'`´]/g, ' ')
    .replace(/[^a-z0-9 ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Mots qui ne disent rien de l'intention (« comment faire une … ») et fausseraient le classement. */
const MOTS_VIDES = new Set([
  'comment', 'faire', 'fait', 'une', 'des', 'les', 'mon', 'mes', 'ses', 'sur', 'dans', 'pour', 'avec', 'que', 'qui', 'quoi', 'est', 'sont',
  'je', 'veux', 'voudrais', 'peux', 'puis', 'dois', 'vous', 'nous', 'ils', 'elle', 'ceci', 'cela', 'ce', 'ca', 'aux', 'par', 'pas', 'plus', 'ou',
  'et', 'au', 'du', 'de', 'la', 'le', 'un', 'en', 'a', 'il', 'on', 'ne', 'se', 'si', 'ai', 'aide', 'besoin',
]);

export interface Motif {
  mots: string[];
}

/** Mots utiles de la question (≥ 3 lettres, hors mots vides). */
export function motsUtiles(question: string): string[] {
  return normaliser(question)
    .split(' ')
    .filter((m) => m.length >= 3 && !MOTS_VIDES.has(m));
}

function commencePar(mots: string[], debut: string): boolean {
  return mots.some((m) => m.startsWith(debut));
}

/**
 * Score d'une tâche pour une question. Le DÉBUT d'un mot suffit (« factu » trouve facture, facturer, facturation) :
 * 5 points si le mot est dans la formulation de la tâche, 3 si c'est un mot-clé ou synonyme, 1 s'il apparaît dans la
 * réponse. Une tâche qui répond à TOUS les mots saisis passe devant celles qui n'en couvrent qu'un.
 */
export function scoreTache(tache: GuideTache, mots: string[]): number {
  if (mots.length === 0) {
    return 0;
  }
  const question = normaliser(tache.question).split(' ');
  const cles = tache.motsCles.map(normaliser);
  const reponse = normaliser(tache.reponse);
  let score = 0;
  let couverts = 0;
  for (const mot of mots) {
    let points = 0;
    if (commencePar(question, mot)) {
      points += 5;
    }
    if (commencePar(cles, mot)) {
      points += 3;
    }
    if (reponse.includes(mot)) {
      points += 1;
    }
    if (points > 0) {
      couverts++;
    }
    score += points;
  }
  return couverts === mots.length ? score + 4 : score;
}

/** Une tâche est proposée à un client seulement si sa page existe dans son menu. */
export function accessible(tache: GuideTache, membreCabinet: boolean): boolean {
  return membreCabinet || (!tache.cabinetSeulement && PAGES_CLIENT.includes(tache.onglet));
}

export function rechercher(question: string, membreCabinet: boolean, taches: GuideTache[] = GUIDES_TACHES, limite = 4): GuideTache[] {
  const mots = motsUtiles(question);
  return taches
    .filter((t) => accessible(t, membreCabinet))
    .map((tache, rang) => ({ tache, rang, score: scoreTache(tache, mots) }))
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score || a.rang - b.rang)
    .slice(0, limite)
    .map((r) => r.tache);
}

/** Questions les plus courantes, proposées en un clic quand la zone de recherche est vide. */
export const SUGGESTIONS_IDS = ['facture-vente', 'facture-fournisseur', 'importer-releve', 'bilan', 'tva', 'relancer-impayes'];

export function suggestions(membreCabinet: boolean, taches: GuideTache[] = GUIDES_TACHES): GuideTache[] {
  const ids = membreCabinet ? SUGGESTIONS_IDS : ['facture-fournisseur', 'deposer-piece-demandee'];
  return ids.map((id) => taches.find((t) => t.id === id)).filter((t): t is GuideTache => !!t && accessible(t, membreCabinet));
}
