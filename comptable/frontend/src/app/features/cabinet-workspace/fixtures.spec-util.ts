import { Dashboard, DossierSummary, Echeance, Page, WorkItem } from './cabinet-workspace.model';

export function dossier(surcharge: Partial<DossierSummary> = {}): DossierSummary {
  return {
    entrepriseId: 'e-1',
    entrepriseNom: 'ABC SARL',
    identifiant: 'CI-ABJ-2020-B-1',
    exerciceId: 'x-1',
    exerciceDebut: '2026-01-01',
    exerciceFin: '2026-12-31',
    courant: true,
    collaborateur: { id: 'u-1', nom: 'Fatou Kone' },
    expert: null,
    statut: 'EN_COURS',
    statutLibelle: 'En cours',
    priorite: 'NORMAL',
    etapesPretes: 5,
    etapesTotal: 7,
    etapes: [
      { code: 'PIECES', libelle: 'Pièces', prete: true },
      { code: 'BANQUE', libelle: 'Banque', prete: true },
      { code: 'COMPTABILITE', libelle: 'Comptabilité', prete: true },
      { code: 'CONTROLES', libelle: 'Contrôles', prete: true },
      { code: 'PRE_CLOTURE', libelle: 'Pré-clôture', prete: true },
      { code: 'ETATS_FINANCIERS', libelle: 'États financiers', prete: false },
      { code: 'FINALISATION', libelle: 'Finalisation', prete: false },
    ],
    findingsActifs: 0,
    findingsBloquants: 0,
    piecesManquantes: 0,
    banqueExceptions: 0,
    prochaineEcheance: null,
    derniereActivite: new Date(Date.now() - 2 * 3600_000).toISOString(),
    tva: null,
    tvaLibelle: null,
    ...surcharge,
  };
}

export function item(surcharge: Partial<WorkItem> = {}): WorkItem {
  return {
    kind: 'FINDING',
    id: 'f-1',
    entrepriseId: 'e-1',
    entrepriseNom: 'ABC SARL',
    exerciceId: 'x-1',
    titre: 'Facture fournisseur manquante',
    domaine: 'Documents',
    impact: 'Bloque la clôture',
    priorite: 'BLOCKER',
    ancienneteJours: 5,
    depuis: null,
    responsable: { id: 'u-1', nom: 'Fatou Kone' },
    cible: 'REVISION',
    ...surcharge,
  };
}

export function page<T>(items: T[], total = items.length, numero = 0, taille = 25): Page<T> {
  return { items, page: numero, taille, total };
}

export function echeance(surcharge: Partial<Echeance> = {}): Echeance {
  return {
    id: 'ech-1',
    entrepriseId: 'e-1',
    entrepriseNom: 'ABC SARL',
    exerciceId: 'x-1',
    type: 'CLOTURE_ANNUELLE',
    typeLibelle: 'Clôture annuelle',
    libelle: 'Clôture 2026',
    date: '2026-10-12',
    responsable: null,
    statut: 'OUVERTE',
    periode: 'CETTE_SEMAINE',
    ...surcharge,
  };
}

export function dashboard(surcharge: Partial<Dashboard> = {}): Dashboard {
  return {
    perimetre: 'CABINET',
    summary: { dossiersActifs: 42, aTraiter: 12, enAttenteClient: 7, pretsPourRevue: 3, echeancesProches: 4 },
    workQueue: page([item()]),
    reviewQueue: [],
    deadlines: [],
    ...surcharge,
  };
}
