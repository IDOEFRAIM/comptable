import { GuideEtape } from './guides-pages';

/**
 * Une chose que l'utilisateur veut FAIRE (et non une page) : c'est ce que retrouve « Que voulez-vous faire ? ».
 * Contenu fixe, rédigé à partir des libellés réels des écrans (boutons, onglets) : pas d'IA générative, donc jamais
 * d'indication inexacte. Chaque tâche lève une confusion fréquente (ex. « facture » = à émettre OU reçue ?).
 */
export interface GuideTache {
  id: string;
  /** Libellé court, utilisé pour les suggestions cliquables. */
  court: string;
  /** Formulation naturelle, telle que l'utilisateur la dirait. */
  question: string;
  /** Réponse en une ou deux phrases, qui dit OÙ se fait l'action et la distingue des actions voisines. */
  reponse: string;
  /** Page du dossier où se fait l'action (segment après /entreprises/:id/). */
  onglet: string;
  /** Texte du bouton d'accès direct. */
  libelleBouton: string;
  etapes: GuideEtape[];
  /** Mots (sans accents, minuscules) et synonymes : la recherche accepte aussi le début d'un mot (« factu » → facture). */
  motsCles: string[];
  /** Vrai : réservé aux membres du cabinet (la page n'existe pas dans le menu d'un client). */
  cabinetSeulement: boolean;
}

/** Pages accessibles à un client (le reste du menu est réservé aux membres du cabinet). */
export const PAGES_CLIENT = ['documents', 'demandes-pieces'];

/** L'ORDRE COMPTE : à score égal, la tâche la plus haute passe devant. Les deux sens de « facture » (à émettre / reçue) restent donc en tête. */
export const GUIDES_TACHES: GuideTache[] = [
  {
    id: 'facture-vente',
    court: 'Facture client',
    question: 'Faire une facture à un client (facture de vente)',
    reponse:
      'Se fait dans Facturation, onglet « Factures ». Ne confondez pas avec une facture REÇUE d’un fournisseur : celle-là se dépose dans Documents.',
    onglet: 'facturation',
    libelleBouton: 'Ouvrir Facturation',
    etapes: [
      { texte: 'Ouvrez l’onglet « Factures » puis cliquez sur « + Nouvelle facture ».' },
      { texte: 'Choisissez le client, la date, l’objet et ajoutez les lignes : la TVA se calcule toute seule. Cliquez sur « Créer » (la facture reste en brouillon).' },
      { texte: 'Choisissez le « Compte de vente… » puis « Valider (génère l’écriture) » : la facture est comptabilisée.' },
      { texte: 'Le paiement s’enregistre depuis l’écran Banque, en affectant le règlement reçu à la facture.', onglet: 'rapprochement' },
    ],
    motsCles: ['facture', 'facturer', 'facturation', 'vente', 'vendre', 'client', 'emettre', 'creer', 'rediger', 'faire', 'envoyer'],
    cabinetSeulement: true,
  },
  {
    id: 'facture-fournisseur',
    court: 'Facture fournisseur',
    question: 'Enregistrer une facture reçue d’un fournisseur (facture d’achat)',
    reponse:
      'Se fait dans Documents : on dépose la facture (PDF ou photo), l’IA la lit, puis on la comptabilise. Ne confondez pas avec une facture à ÉMETTRE à un client (Facturation).',
    onglet: 'documents',
    libelleBouton: 'Ouvrir Documents',
    etapes: [
      { texte: 'Choisissez le type de document et le fichier (PDF, JPG ou PNG), puis cliquez sur « Déposer le document ».' },
      { texte: 'L’IA lit la pièce (fournisseur, montants, TVA). Une pièce « Illisible » est à renvoyer en meilleure qualité.' },
      { texte: 'Les pièces douteuses passent par « Validation » : comparez avec l’original puis « Valider cette pièce ».', onglet: 'validation' },
      { texte: 'Cliquez sur « Comptabiliser » pour générer l’écriture.' },
    ],
    motsCles: ['facture', 'fournisseur', 'achat', 'recu', 'piece', 'deposer', 'depot', 'scanner', 'photo', 'justificatif', 'depense', 'comptabiliser'],
    cabinetSeulement: false,
  },
  {
    id: 'devis',
    court: 'Devis',
    question: 'Faire un devis puis le transformer en facture',
    reponse: 'Se fait dans Facturation, onglet « Devis ». Une fois accepté par le client, il se convertit en facture en un clic.',
    onglet: 'facturation',
    libelleBouton: 'Ouvrir Facturation',
    etapes: [
      { texte: 'Ouvrez l’onglet « Devis » puis « + Nouveau devis », remplissez-le et cliquez sur « Créer ».' },
      { texte: 'Cliquez sur « Envoyer », puis « Accepter » ou « Refuser » selon la réponse du client.' },
      { texte: 'Un devis accepté se transforme avec « Convertir en facture ».' },
    ],
    motsCles: ['devis', 'proposition', 'commerciale', 'accepter', 'refuser', 'convertir', 'chiffrer'],
    cabinetSeulement: true,
  },
  {
    id: 'avoir',
    court: 'Avoir',
    question: 'Corriger ou annuler une facture déjà validée (avoir)',
    reponse: 'Une facture validée ne se modifie pas : on l’annule, ou on émet un avoir (erreur de prix, remise, retour de marchandise).',
    onglet: 'facturation',
    libelleBouton: 'Ouvrir Facturation',
    etapes: [
      { texte: 'Dans l’onglet « Factures », repérez la facture validée.' },
      { texte: 'Cliquez sur « Créer un avoir », indiquez le motif (Erreur de prix, Remise accordée, Retour marchandise, Autre) et les quantités à créditer.' },
      { texte: 'Cliquez sur « Créer l’avoir (brouillon) », puis validez-le depuis l’onglet « Avoirs ».' },
    ],
    motsCles: ['avoir', 'annuler', 'corriger', 'rectifier', 'rembourser', 'retour', 'remise', 'erreur', 'facture'],
    cabinetSeulement: true,
  },
  {
    id: 'payer-fournisseur',
    court: 'Payer un fournisseur',
    question: 'Enregistrer le paiement d’un fournisseur',
    reponse: 'Se fait dans Opérations courantes, avec « Règlement » : vous indiquez le fournisseur et le moyen de paiement.',
    onglet: 'operations',
    libelleBouton: 'Ouvrir Opérations courantes',
    etapes: [
      { texte: 'Cliquez sur « Règlement » puis choisissez le fournisseur.' },
      { texte: 'Indiquez le moyen de paiement (Espèces, Chèque, Carte, Mobile money) et le montant.' },
      { texte: 'Contrôlez la proposition d’écriture et cliquez sur « Valider ».' },
    ],
    motsCles: ['payer', 'paiement', 'reglement', 'regler', 'fournisseur', 'decaissement', 'verser', 'cheque', 'especes', 'mobile', 'money'],
    cabinetSeulement: true,
  },
  {
    id: 'encaisser-client',
    court: 'Paiement client',
    question: 'Enregistrer le paiement reçu d’un client',
    reponse: 'Le paiement d’une facture se lit dans les règlements affectés, à l’écran Banque : on affecte le règlement reçu à la ou aux factures concernées.',
    onglet: 'rapprochement',
    libelleBouton: 'Ouvrir Banque',
    etapes: [
      { texte: 'Importez le relevé bancaire si ce n’est pas déjà fait (« + Importer un relevé »).' },
      { texte: 'Dans « Règlements à répartir », cliquez sur « Répartir… » en face du règlement reçu.' },
      { texte: 'Choisissez la ou les factures à régler puis « Affecter » : la facture passe en « Partiellement payée » ou « Réglée ».' },
    ],
    motsCles: ['encaisser', 'encaissement', 'paiement', 'client', 'regle', 'reglement', 'recu', 'impaye', 'facture', 'affecter', 'repartir'],
    cabinetSeulement: true,
  },
  {
    id: 'relancer-impayes',
    court: 'Impayés',
    question: 'Relancer les clients qui n’ont pas payé',
    reponse: 'Se voit dans Recouvrement : les clients en retard sont classés par risque, avec une action conseillée. Aucune relance n’est envoyée automatiquement.',
    onglet: 'recouvrement',
    libelleBouton: 'Ouvrir Recouvrement',
    etapes: [
      { texte: 'Repérez les clients au risque le plus élevé : le solde dû et l’action recommandée sont indiqués.' },
      { texte: 'Contactez le client vous-même : l’application suggère, elle n’envoie rien.' },
      { texte: 'Quand il paie, affectez son règlement à la facture depuis l’écran Banque.', onglet: 'rapprochement' },
    ],
    motsCles: ['relancer', 'relance', 'impaye', 'retard', 'recouvrement', 'creance', 'client', 'solde', 'facture', 'payer'],
    cabinetSeulement: true,
  },
  {
    id: 'importer-releve',
    court: 'Relevé bancaire',
    question: 'Importer un relevé de banque',
    reponse: 'Se fait à l’écran Banque : vous importez le fichier CSV, vérifiez l’aperçu, puis confirmez. Un relevé importé deux fois ne crée aucun doublon.',
    onglet: 'rapprochement',
    libelleBouton: 'Ouvrir Banque',
    etapes: [
      { texte: 'Cliquez sur « + Importer un relevé » et choisissez le compte et le fichier.' },
      { texte: 'Cliquez sur « Aperçu » : rien n’est enregistré tant que vous n’avez pas confirmé.' },
      { texte: 'Vérifiez les lignes valides et les doublons, puis « Confirmer l’import ».' },
    ],
    motsCles: ['importer', 'import', 'releve', 'banque', 'csv', 'fichier', 'compte', 'bancaire', 'transactions'],
    cabinetSeulement: true,
  },
  {
    id: 'rapprocher-banque',
    court: 'Rapprocher la banque',
    question: 'Rapprocher la banque avec la comptabilité',
    reponse: 'Se fait à l’écran Banque, une fois le relevé importé : l’application propose des correspondances, vous confirmez ou corrigez.',
    onglet: 'rapprochement',
    libelleBouton: 'Ouvrir Banque',
    etapes: [
      { texte: 'Parcourez les onglets « Rapprochées », « À confirmer », « Ambiguës » et « Sans correspondance ».' },
      { texte: 'Pour les correspondances sûres, utilisez « Valider les correspondances fortes… ».' },
      { texte: 'Pour les autres, ouvrez la ligne (« Pourquoi ? » explique la proposition) puis « Confirmer », « Rejeter » ou « Choisir un autre mouvement… ».' },
    ],
    motsCles: ['rapprocher', 'rapprochement', 'banque', 'pointage', 'pointer', 'correspondance', 'releve', 'ambigu', 'confirmer'],
    cabinetSeulement: true,
  },
  {
    id: 'saisir-ecriture',
    court: 'Saisir une écriture',
    question: 'Saisir une écriture comptable à la main',
    reponse: 'Se fait dans Écritures, « Nouvelle écriture » : le total des débits doit égaler le total des crédits.',
    onglet: 'ecritures/nouvelle',
    libelleBouton: 'Ouvrir la saisie d’écriture',
    etapes: [
      { texte: 'Choisissez le journal et la date, puis saisissez chaque ligne : compte, débit ou crédit.' },
      { texte: 'Contrôlez que l’écriture est équilibrée, puis enregistrez : elle reste en brouillon.' },
      { texte: 'Validez-la depuis la liste des écritures : seules les écritures validées comptent dans les états.', onglet: 'ecritures' },
    ],
    motsCles: ['ecriture', 'saisir', 'saisie', 'journal', 'debit', 'credit', 'od', 'operation', 'diverse', 'manuelle', 'comptabiliser'],
    cabinetSeulement: true,
  },
  {
    id: 'bilan',
    court: 'Bilan',
    question: 'Faire le bilan et le compte de résultat',
    reponse: 'Se fait dans États financiers : bilan, compte de résultat et notes sont calculés depuis vos écritures VALIDÉES. Vérifiez-les avant de produire le dossier.',
    onglet: 'etats-financiers',
    libelleBouton: 'Ouvrir États financiers',
    etapes: [
      { texte: 'Vérifiez d’abord que les écritures sont validées et la banque rapprochée.', onglet: 'ecritures' },
      { texte: 'Ouvrez les états financiers et traitez les contrôles signalés (onglet « Contrôles »).' },
      { texte: 'Une fois les contrôles au vert, finalisez le dossier : il devient un document figé que vous pouvez exporter.' },
    ],
    motsCles: ['bilan', 'resultat', 'compte', 'etats', 'financiers', 'cloture', 'annexe', 'liasse', 'syscohada', 'exercice', 'finaliser'],
    cabinetSeulement: true,
  },
  {
    id: 'tva',
    court: 'TVA',
    question: 'Préparer la déclaration de TVA',
    reponse:
      'Se fait dans Fiscalité › TVA : l’application calcule la TVA collectée et déductible depuis vos écritures. Au Burkina Faso, la finalisation reste indisponible tant que le cadre réglementaire n’est pas vérifié.',
    onglet: 'fiscalite/tva',
    libelleBouton: 'Ouvrir la TVA',
    etapes: [
      { texte: 'Cliquez sur « Ouvrir une période » et choisissez le mois ou la période.' },
      { texte: 'Contrôlez la TVA collectée, la TVA déductible et traitez les opérations « à revoir ».' },
      { texte: 'Cliquez sur « Marquer prête pour revue » ; la finalisation exige un cadre réglementaire vérifié.' },
    ],
    motsCles: ['tva', 'declaration', 'declarer', 'impot', 'taxe', 'fiscal', 'fiscalite', 'collectee', 'deductible', 'dgi'],
    cabinetSeulement: true,
  },
  {
    id: 'demander-piece',
    court: 'Pièce manquante',
    question: 'Réclamer une pièce manquante à un client',
    reponse:
      'Se fait dans Documents, section « Collecte des pièces » en haut de page : l’application liste les opérations sans justificatif et vous les demandez au client, qui dépose la pièce.',
    onglet: 'documents',
    libelleBouton: 'Ouvrir Documents',
    etapes: [
      { texte: 'Dans « Collecte des pièces », ouvrez le filtre « Pièces manquantes » : ce sont les opérations sans justificatif.' },
      { texte: 'Sélectionnez-les, cliquez sur « Demander … pièce(s) », vérifiez l’« Aperçu avant envoi » puis « Confirmer la demande ».' },
      { texte: 'Utilisez « Envoyer » puis « Relancer » tant que la pièce n’est pas reçue ; le suivi se fait dans « Demandées », « À relancer » et « Reçues ».' },
    ],
    motsCles: ['demander', 'demande', 'piece', 'manquante', 'justificatif', 'relancer', 'collecte', 'client', 'reclamer', 'envoyer'],
    cabinetSeulement: true,
  },
  {
    id: 'deposer-piece-demandee',
    court: 'Pièce demandée',
    question: 'Envoyer la pièce que mon cabinet m’a demandée',
    reponse: 'Se fait dans Pièces demandées : la liste montre ce que votre cabinet attend, et vous déposez le fichier en face de chaque demande.',
    onglet: 'demandes-pieces',
    libelleBouton: 'Ouvrir Pièces demandées',
    etapes: [
      { texte: 'Repérez la demande concernée dans la liste.' },
      { texte: 'Cliquez sur « Déposer la pièce » et choisissez le fichier (PDF ou photo).' },
    ],
    motsCles: ['piece', 'demandee', 'demande', 'cabinet', 'envoyer', 'deposer', 'depot', 'justificatif', 'repondre', 'manquante'],
    cabinetSeulement: false,
  },
  {
    id: 'avant-cloture',
    court: 'Avant la clôture',
    question: 'Savoir ce qui bloque avant la clôture',
    reponse: 'Se voit dans Révision : les constats sont classés par gravité, avec la cause et ce qu’il faut faire pour chacun.',
    onglet: 'revision',
    libelleBouton: 'Ouvrir la Révision',
    etapes: [
      { texte: 'Regardez les compteurs « Bloquants » et « Avertissements » en haut de page.' },
      { texte: 'Ouvrez un constat : « Causes » et « Actions possibles » disent quoi faire (corriger, demander la pièce, écarter avec justification).' },
      { texte: 'Quand il n’y a plus de bloquant, passez à la clôture.', onglet: 'etats-financiers' },
    ],
    motsCles: ['cloture', 'cloturer', 'bloquant', 'bloque', 'revision', 'controle', 'constat', 'anomalie', 'erreur', 'audit', 'verifier', 'pret'],
    cabinetSeulement: true,
  },
  {
    id: 'rapports',
    court: 'Balance / FEC',
    question: 'Voir la balance, le grand livre ou exporter le FEC',
    reponse: 'Se fait dans Rapports : balance générale, grand livre, journal, balance âgée et export FEC (écritures validées uniquement).',
    onglet: 'rapports',
    libelleBouton: 'Ouvrir Rapports',
    etapes: [
      { texte: 'Choisissez le rapport : Balance générale, Grand livre, Journal des opérations, Balance âgée ou Export FEC.' },
      { texte: 'Pour le FEC, cliquez sur « Générer » puis « Télécharger le FEC ».' },
      { texte: 'Dans le grand livre d’un compte lettrable, vous pouvez lettrer (automatiquement ou la sélection).' },
    ],
    motsCles: ['balance', 'grand', 'livre', 'journal', 'fec', 'export', 'exporter', 'rapport', 'lettrage', 'lettrer', 'agee', 'telecharger'],
    cabinetSeulement: true,
  },
  {
    id: 'immobilisation',
    court: 'Immobilisation',
    question: 'Ajouter un bien durable (immobilisation) et son amortissement',
    reponse: 'Se fait dans Immobilisations : le plan d’amortissement apparaît à la mise en service du bien.',
    onglet: 'immobilisations',
    libelleBouton: 'Ouvrir Immobilisations',
    etapes: [
      { texte: 'Ajoutez le bien (date, valeur, durée), puis « Comptabiliser l’acquisition » ou « Lier à une facture déjà comptabilisée ».' },
      { texte: 'Cliquez sur « Mettre en service » : le plan d’amortissement est calculé.' },
      { texte: 'Utilisez « Sortir du patrimoine » quand le bien est vendu ou mis au rebut.' },
    ],
    motsCles: ['immobilisation', 'amortissement', 'materiel', 'vehicule', 'bien', 'durable', 'acquisition', 'patrimoine', 'dotation'],
    cabinetSeulement: true,
  },
  {
    id: 'stock',
    court: 'Stock',
    question: 'Suivre le stock et faire un inventaire',
    reponse: 'Se fait dans Stocks : articles, mouvements (entrées et sorties) et inventaires.',
    onglet: 'stocks',
    libelleBouton: 'Ouvrir Stocks',
    etapes: [
      { texte: 'Onglet « Articles » : consultez le stock actuel, le coût moyen (PUMP) et la valeur de chaque article.' },
      { texte: 'Onglet « Inventaires » : saisissez les quantités comptées puis « Valider l’inventaire » ; les écarts sont valorisés.' },
    ],
    motsCles: ['stock', 'article', 'inventaire', 'marchandise', 'produit', 'pump', 'mouvement', 'entree', 'sortie'],
    cabinetSeulement: true,
  },
  {
    id: 'inviter',
    court: 'Donner accès',
    question: 'Donner accès à un client ou à un collaborateur',
    reponse: 'Se fait dans Inviter : l’application crée l’accès et affiche un mot de passe temporaire à transmettre.',
    onglet: 'inviter',
    libelleBouton: 'Ouvrir Inviter',
    etapes: [
      { texte: 'Saisissez l’e-mail de la personne et son rôle, puis validez.' },
      { texte: 'Notez le mot de passe temporaire affiché et transmettez-le par un canal sûr : la personne devra le changer à sa première connexion.' },
    ],
    motsCles: ['inviter', 'invitation', 'acces', 'utilisateur', 'client', 'collaborateur', 'compte', 'mot', 'passe', 'droits'],
    cabinetSeulement: true,
  },
  {
    id: 'plan-comptable',
    court: 'Plan comptable',
    question: 'Ajouter des comptes au plan comptable',
    reponse: 'Se fait dans Plan comptable : on importe les comptes du référentiel SYSCOHADA ou on renomme un compte existant.',
    onglet: 'plan-comptable',
    libelleBouton: 'Ouvrir Plan comptable',
    etapes: [
      { texte: 'Cliquez sur « Importer depuis SYSCOHADA » et choisissez les comptes (ou « Importer tout le référentiel actif »).' },
      { texte: 'Cliquez sur un compte pour voir son origine, son sens normal et modifier son libellé.' },
    ],
    motsCles: ['plan', 'comptable', 'compte', 'comptes', 'syscohada', 'referentiel', 'importer', 'libelle', 'numero'],
    cabinetSeulement: true,
  },
  {
    id: 'comptes-automatiques',
    court: 'Comptes automatiques',
    question: 'Choisir les comptes utilisés automatiquement (TVA, banque, caisse…)',
    reponse: 'Se fait dans Paramètres comptables : chaque « nature » (TVA collectée, banque, caisse…) est reliée à un compte, utilisé pour les écritures générées.',
    onglet: 'parametres-comptables',
    libelleBouton: 'Ouvrir Paramètres comptables',
    etapes: [
      { texte: 'Repérez dans le tableau les natures dont le statut indique qu’aucun compte n’est configuré.' },
      { texte: 'Choisissez le compte voulu et cliquez sur « Enregistrer ».' },
    ],
    motsCles: ['parametre', 'parametres', 'comptable', 'compte', 'automatique', 'tva', 'banque', 'caisse', 'configurer', 'configuration', 'nature'],
    cabinetSeulement: true,
  },
  {
    id: 'tableau-de-bord',
    court: 'Où j’en suis',
    question: 'Voir ce qui demande mon attention aujourd’hui',
    reponse: 'Se voit dans le Tableau de bord du dossier : compteurs de documents à traiter, écritures en brouillon, échéances et actions prioritaires.',
    onglet: 'tableau-de-bord',
    libelleBouton: 'Ouvrir le Tableau de bord',
    etapes: [
      { texte: 'Les compteurs en haut mènent chacun à la page concernée.' },
      { texte: 'Traitez les « Actions prioritaires » de haut en bas.' },
    ],
    motsCles: ['tableau', 'bord', 'accueil', 'resume', 'vue', 'ensemble', 'priorite', 'prioritaire', 'attention', 'echeance', 'traiter'],
    cabinetSeulement: true,
  },
];
