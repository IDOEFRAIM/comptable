export interface GuideEtape {
  texte: string;
  /** Onglet du dossier (segment apres /entreprises/:id/) ; absent = simple indication. */
  onglet?: string;
}

export interface GuidePage {
  /** Segment d'URL de l'onglet du dossier, ex. 'facturation'. */
  onglet: string;
  titre: string;
  resume: string;
  etapes: GuideEtape[];
  /** Mots (sans accents, minuscules) permettant de retrouver ce guide depuis « Que voulez-vous faire ? ». */
  motsCles: string[];
}

/**
 * Guides d'utilisation par page : textes pedagogiques fixes, comme aide-contextuelle.service.ts.
 * Volontairement pas generes par un LLM : un guide doit rester exact et verifiable, et les
 * chiffres (bilan, TVA...) restent calcules par le moteur comptable, jamais par l'IA.
 */
export const GUIDES_PAGES: GuidePage[] = [
  {
    onglet: 'tableau-de-bord',
    titre: 'Tableau de bord',
    resume: "Vue d'ensemble du dossier : ce qui demande votre attention aujourd'hui.",
    etapes: [
      { texte: 'Repérez les compteurs « à traiter » : chacun mène à la page concernée.' },
      { texte: 'Commencez par les documents et les fournisseurs à traiter.', onglet: 'documents' },
    ],
    motsCles: ['accueil', 'resume', 'vue', 'ensemble', 'alerte', 'constat'],
  },
  {
    onglet: 'documents',
    titre: 'Documents',
    resume: 'Déposez les factures, reçus et bordereaux ; ils sont lus automatiquement par l’IA.',
    etapes: [
      { texte: 'Déposez une photo ou un PDF (facture, reçu Mobile Money, bordereau de banque).' },
      { texte: 'Un document marqué « Illisible » est à ressaisir à la main ou à renvoyer en meilleure qualité.' },
      { texte: 'Associez la pièce à une facture ou à une demande, ou marquez-la comme doublon.' },
      { texte: 'Les pièces douteuses passent par la validation avant comptabilisation.', onglet: 'validation' },
    ],
    motsCles: ['facture', 'recu', 'piece', 'depot', 'deposer', 'scan', 'photo', 'bordereau', 'achat', 'illisible', 'doublon'],
  },
  {
    onglet: 'validation',
    titre: 'Validation',
    resume: 'Contrôlez les pièces dont l’IA n’est pas sûre avant qu’elles ne soient comptabilisées.',
    etapes: [
      { texte: 'Ouvrez une pièce et comparez les montants lus avec l’original.' },
      { texte: 'Corrigez si besoin, puis validez : l’écriture est proposée.' },
    ],
    motsCles: ['valider', 'verifier', 'confiance', 'fraude', 'controle'],
  },
  {
    onglet: 'facturation',
    titre: 'Facturation',
    resume: 'Créez vos factures de vente et suivez leur paiement.',
    etapes: [
      { texte: 'Créez une facture : choisissez le client, ajoutez les lignes, la TVA se calcule.' },
      { texte: 'Envoyez-la au client, puis enregistrez les règlements reçus.' },
      { texte: 'Les factures impayées se relancent depuis le recouvrement.', onglet: 'recouvrement' },
    ],
    motsCles: ['facturer', 'vente', 'client', 'devis', 'rediger', 'facture de vente'],
  },
  {
    onglet: 'stocks',
    titre: 'Stocks',
    resume: 'Suivez les articles, leurs entrées et leurs sorties.',
    etapes: [
      { texte: 'Créez vos articles, puis enregistrez les entrées (achats) et les sorties (ventes).' },
      { texte: 'Consultez le niveau de stock et la valeur du stock.' },
    ],
    motsCles: ['stock', 'article', 'inventaire', 'marchandise', 'produit'],
  },
  {
    onglet: 'achats',
    titre: 'Achats',
    resume: 'Suivez les factures fournisseurs à payer.',
    etapes: [
      { texte: 'Les factures d’achat viennent des documents déposés.', onglet: 'documents' },
      { texte: 'Enregistrez le paiement fournisseur quand il est effectué.' },
    ],
    motsCles: ['fournisseur', 'achat', 'depense', 'payer'],
  },
  {
    onglet: 'tresorerie',
    titre: 'Trésorerie',
    resume: 'Suivez la banque et la caisse.',
    etapes: [
      { texte: 'Consultez les soldes et les mouvements.' },
      { texte: 'Pointez la banque avec la comptabilité.', onglet: 'rapprochement' },
    ],
    motsCles: ['banque', 'caisse', 'solde', 'tresorerie', 'argent', 'releve'],
  },
  {
    onglet: 'rapprochement',
    titre: 'Rapprochement bancaire',
    resume: 'Faites correspondre le relevé de banque avec vos écritures.',
    etapes: [
      { texte: 'Importez le relevé bancaire.' },
      { texte: 'Confirmez les rapprochements proposés ; pointez les lignes restantes à la main.' },
    ],
    motsCles: ['rapprocher', 'releve', 'banque', 'pointage', 'pointer'],
  },
  {
    onglet: 'ecritures',
    titre: 'Écritures',
    resume: 'Consultez et saisissez les écritures comptables.',
    etapes: [
      { texte: 'Saisissez une nouvelle écriture : débit et crédit doivent être égaux.', onglet: 'ecritures/nouvelle' },
      { texte: 'Les brouillons doivent être validés pour compter dans les états.' },
    ],
    motsCles: ['ecriture', 'journal', 'saisie', 'saisir', 'debit', 'credit', 'comptabiliser'],
  },
  {
    onglet: 'etats-financiers',
    titre: 'États financiers (bilan)',
    resume: 'Bilan, compte de résultat et notes annexes, calculés depuis vos écritures validées.',
    etapes: [
      { texte: 'Vérifiez d’abord que les écritures sont validées et la banque rapprochée.', onglet: 'ecritures' },
      { texte: 'Ouvrez les états financiers et contrôlez les constats éventuels.' },
      { texte: 'Générez ou exportez le bilan une fois les contrôles au vert.' },
    ],
    motsCles: ['bilan', 'resultat', 'etats', 'financiers', 'cloture', 'annexe', 'liasse'],
  },
  {
    onglet: 'fiscalite/tva',
    titre: 'TVA',
    resume: 'Préparez la déclaration de TVA depuis les écritures.',
    etapes: [
      { texte: 'Choisissez la période, vérifiez la TVA collectée et déductible.' },
      { texte: 'Corrigez les anomalies signalées avant de déclarer.' },
    ],
    motsCles: ['tva', 'declaration', 'impot', 'taxe', 'fiscal'],
  },
  {
    onglet: 'tiers',
    titre: 'Tiers',
    resume: 'Clients et fournisseurs du dossier.',
    etapes: [{ texte: 'Créez ou fusionnez les fiches ; un fournisseur ambigu se tranche ici.' }],
    motsCles: ['client', 'fournisseur', 'tiers', 'contact'],
  },
  {
    onglet: 'immobilisations',
    titre: 'Immobilisations',
    resume: 'Biens durables et leurs amortissements.',
    etapes: [{ texte: 'Ajoutez un bien (date, valeur, durée) : l’amortissement est calculé.' }],
    motsCles: ['immobilisation', 'amortissement', 'materiel', 'vehicule'],
  },
  {
    onglet: 'revision',
    titre: 'Révision',
    resume: 'Contrôles avant clôture.',
    etapes: [{ texte: 'Traitez les constats un par un : corrigez, justifiez ou acceptez.' }],
    motsCles: ['revision', 'controle', 'constat', 'audit', 'cloture'],
  },
  {
    onglet: 'demandes-pieces',
    titre: 'Demandes de pièces',
    resume: 'Réclamez à votre client les pièces manquantes.',
    etapes: [{ texte: 'Créez une demande ; le client dépose la pièce via son lien.' }],
    motsCles: ['demande', 'piece', 'manquante', 'relance', 'collecte'],
  },
];
