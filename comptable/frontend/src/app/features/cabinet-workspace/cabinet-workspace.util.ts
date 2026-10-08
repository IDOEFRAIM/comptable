import { DossierSummary, PrioriteDossier, StatutDossier, WorkItem } from './cabinet-workspace.model';

/** « il y a 2 h », « il y a 3 jours » : lecture rapide de l'activité réelle (jamais un flux d'événements). */
export function ilYA(iso: string | null, maintenant: Date = new Date()): string {
  if (!iso) {
    return '—';
  }
  const minutes = Math.max(0, Math.floor((maintenant.getTime() - new Date(iso).getTime()) / 60000));
  if (minutes < 2) {
    return 'à l’instant';
  }
  if (minutes < 60) {
    return `il y a ${minutes} min`;
  }
  const heures = Math.floor(minutes / 60);
  if (heures < 24) {
    return `il y a ${heures} h`;
  }
  const jours = Math.floor(heures / 24);
  return jours === 1 ? 'hier' : `il y a ${jours} jours`;
}

/** « Depuis 5 jours » pour l'ancienneté d'un élément de la file À traiter. */
export function depuisJours(jours: number): string {
  if (jours <= 0) {
    return 'Aujourd’hui';
  }
  return jours === 1 ? 'Depuis 1 jour' : `Depuis ${jours} jours`;
}

/** Libellé court d'une échéance : date au format local. */
export function dateCourte(iso: string): string {
  const [a, m, j] = iso.split('-');
  return `${j}/${m}/${a}`;
}

/** Écran d'entrée d'un dossier selon son statut : on ouvre là où l'action suivante se fait. */
export function routeDossier(statut: StatutDossier, entrepriseId: string): string[] {
  const base = ['/entreprises', entrepriseId];
  switch (statut) {
    case 'PRET_POUR_REVUE':
    case 'EN_REVUE':
    case 'PRET_A_CLOTURER':
    case 'CLOTURE':
    case 'FINALISE':
      return [...base, 'etats-financiers'];
    case 'EN_ATTENTE_CLIENT':
      return [...base, 'demandes-pieces'];
    case 'A_DEMARRER':
      return [...base, 'tableau-de-bord'];
    default:
      return [...base, 'revision'];
  }
}

/** Écran source d'un élément de la file À traiter (cible fournie par le backend). */
export function routeSource(item: WorkItem): string[] {
  const base = ['/entreprises', item.entrepriseId];
  switch (item.cible) {
    case 'DEMANDES':
      return [...base, 'demandes-pieces'];
    case 'BANQUE':
      return [...base, 'rapprochement'];
    case 'ETATS_FINANCIERS':
      return [...base, 'etats-financiers'];
    case 'FISCALITE':
      return [...base, 'fiscalite', 'tva'];
    default:
      return [...base, 'revision'];
  }
}

export function libelleEtapes(d: DossierSummary): string {
  return `${d.etapesPretes} / ${d.etapesTotal}`;
}

/** Un dossier est « urgent » à l'écran seulement pour les deux priorités les plus hautes : la barre latérale n'est jamais décorative. */
export function urgent(p: PrioriteDossier): boolean {
  return p === 'BLOCKER' || p === 'HIGH';
}
