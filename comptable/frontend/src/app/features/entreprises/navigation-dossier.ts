/**
 * Menu d'un dossier, rangé dans l'ordre du travail du comptable : on reçoit et saisit les pièces, on suit la banque,
 * on gère ventes et achats, puis on clôture et on déclare. Les réglages du dossier (plan comptable, invitations)
 * servent rarement : ils sont en bas. Les chemins (`lien`) sont ceux des routes : ne pas les renommer.
 */
export interface EntreeMenu {
  lien: string;
  libelle: string;
  icone: string;
  /** Une phrase affichée au survol : à quoi sert la page, en mots de comptable. */
  aide: string;
  /** Vrai : page réservée au cabinet (un client n'y a pas accès côté API). */
  cabinet: boolean;
}

export interface GroupeMenu {
  titre: string | null;
  entrees: EntreeMenu[];
}

export const MENU_DOSSIER: GroupeMenu[] = [
  {
    titre: null,
    entrees: [
      { lien: 'tableau-de-bord', libelle: 'Tableau de bord', icone: 'home', aide: 'Vue d’ensemble du dossier et raccourcis', cabinet: true },
    ],
  },
  {
    titre: 'Pièces et saisie',
    entrees: [
      { lien: 'documents', libelle: 'Documents', icone: 'upload', aide: 'Déposer et suivre les factures et reçus', cabinet: false },
      { lien: 'demandes-pieces', libelle: 'Pièces demandées', icone: 'inbox', aide: 'Pièces réclamées au client', cabinet: false },
      { lien: 'validation', libelle: 'À valider', icone: 'check-circle', aide: 'Pièces lues par l’IA à vérifier avant comptabilisation', cabinet: true },
      { lien: 'operations', libelle: 'Saisie rapide', icone: 'edit', aide: 'Achat, vente, règlement ou avoir : l’écriture est proposée pour vous', cabinet: true },
      { lien: 'ecritures', libelle: 'Écritures', icone: 'list', aide: 'Toutes les écritures du dossier, saisie manuelle', cabinet: true },
    ],
  },
  {
    titre: 'Banque et trésorerie',
    entrees: [
      { lien: 'rapprochement', libelle: 'Banque', icone: 'bank', aide: 'Relevés bancaires et Mobile Money, rapprochement', cabinet: true },
      { lien: 'tresorerie', libelle: 'Trésorerie', icone: 'wallet', aide: 'Soldes et prévisions de trésorerie', cabinet: true },
      { lien: 'recouvrement', libelle: 'Recouvrement', icone: 'clock', aide: 'Factures clients impayées et relances', cabinet: true },
    ],
  },
  {
    titre: 'Ventes et achats',
    entrees: [
      { lien: 'facturation', libelle: 'Facturation', icone: 'file', aide: 'Devis, factures et avoirs clients', cabinet: true },
      { lien: 'achats', libelle: 'Achats', icone: 'cart', aide: 'Commandes et réceptions fournisseurs', cabinet: true },
      { lien: 'stocks', libelle: 'Stocks', icone: 'box', aide: 'Articles, entrées et sorties de stock', cabinet: true },
      { lien: 'immobilisations', libelle: 'Immobilisations', icone: 'building', aide: 'Biens durables et amortissements', cabinet: true },
      { lien: 'tiers', libelle: 'Clients et fournisseurs', icone: 'users', aide: 'Fiches tiers (clients, fournisseurs)', cabinet: true },
    ],
  },
  {
    titre: 'Clôture et déclarations',
    entrees: [
      { lien: 'revision', libelle: 'Révision', icone: 'search', aide: 'Contrôles du dossier avant clôture', cabinet: true },
      { lien: 'regularisations', libelle: 'Régularisations', icone: 'refresh', aide: 'Charges et produits constatés d’avance, à payer, à recevoir', cabinet: true },
      { lien: 'rapports', libelle: 'Balance et grand livre', icone: 'book', aide: 'Balance, grand livre, journal, balance âgée, export FEC', cabinet: true },
      { lien: 'etats-financiers', libelle: 'États financiers', icone: 'chart', aide: 'Bilan, compte de résultat, TFT et notes SYSCOHADA', cabinet: true },
      { lien: 'fiscalite', libelle: 'Fiscalité', icone: 'calendar', aide: 'TVA et déclarations fiscales', cabinet: true },
      { lien: 'conformite', libelle: 'Conformité', icone: 'shield', aide: 'Obligations légales du dossier', cabinet: true },
    ],
  },
  {
    titre: 'Réglages du dossier',
    entrees: [
      { lien: 'fiche', libelle: 'Fiche de l’entreprise', icone: 'building', aide: 'IFU, RCCM, adresse, coordonnées bancaires : ce qui figure sur vos devis et factures', cabinet: true },
      { lien: 'plan-comptable', libelle: 'Plan comptable', icone: 'grid', aide: 'Comptes SYSCOHADA du dossier', cabinet: true },
      { lien: 'parametres-comptables', libelle: 'Paramètres comptables', icone: 'settings', aide: 'Journaux, exercices et comptes par défaut', cabinet: true },
      { lien: 'inviter', libelle: 'Inviter un utilisateur', icone: 'user-plus', aide: 'Donner un accès au client ou à un collaborateur', cabinet: true },
    ],
  },
];

/** Le menu visible selon le rôle : un client ne voit que les pages qui lui sont ouvertes, et aucun groupe vide. */
export function menuPour(membreCabinet: boolean): GroupeMenu[] {
  return MENU_DOSSIER.map((g) => ({ ...g, entrees: g.entrees.filter((e) => membreCabinet || !e.cabinet) })).filter((g) => g.entrees.length > 0);
}

/** Libellé de la page ouverte (premier segment après l'id du dossier), pour le fil d'Ariane. */
export function libellePage(segment: string): string | null {
  for (const g of MENU_DOSSIER) {
    const e = g.entrees.find((x) => x.lien === segment);
    if (e) {
      return e.libelle;
    }
  }
  return null;
}

/**
 * Ce que chaque page permet sans Internet (application installée, dossier téléchargé) :
 * - `saisie` : consultation + saisie gardée sur l'appareil puis envoyée au retour d'Internet (dépôt de pièce, écriture en brouillon) ;
 * - `lecture` : consultation seule des dernières données téléchargées ;
 * - `connexion` : inutilisable hors ligne (calculs et actions qui dépendent du serveur : IA, balance du jour, validation, clôture…).
 * Doit rester aligné sur dossier-hors-ligne.service.ts (pages téléchargées) et hors-ligne.interceptor.ts (saisies acceptées).
 */
export type DisponibiliteHorsLigne = 'saisie' | 'lecture' | 'connexion';

const HORS_LIGNE: Record<string, DisponibiliteHorsLigne> = {
  documents: 'saisie',
  ecritures: 'saisie',
  operations: 'saisie',
  'tableau-de-bord': 'lecture',
  'demandes-pieces': 'lecture',
  tiers: 'saisie',
  'plan-comptable': 'lecture',
  tresorerie: 'lecture',
  recouvrement: 'lecture',
  facturation: 'saisie',
  achats: 'lecture',
  stocks: 'lecture',
  immobilisations: 'lecture',
  revision: 'lecture',
  regularisations: 'lecture',
  rapprochement: 'lecture',
  'etats-financiers': 'lecture',
  fiscalite: 'lecture',
};

export function disponibiliteHorsLigne(lien: string): DisponibiliteHorsLigne {
  return HORS_LIGNE[lien] ?? 'connexion';
}
