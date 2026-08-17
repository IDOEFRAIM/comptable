import { Injectable } from '@angular/core';

export interface ContenuAide {
  titre: string;
  texte: string;
}

/**
 * Contenu d'aide contextuelle (Sprint 8), volontairement statique et embarque cote frontend :
 * ce sont des textes pedagogiques fixes rediges une fois pour toutes, pas des donnees
 * utilisateur ni du contenu genere - aucun besoin d'endpoint backend pour ca. La cle
 * (ex: 'inbox-validation') est choisie par la page qui pose <app-aide-contextuelle cle="...">.
 */
const CONTENUS_AIDE: Record<string, ContenuAide> = {
  'inbox-validation': {
    titre: 'Inbox de validation',
    texte:
      "Chaque pièce envoyée par vos clients (photo WhatsApp ou upload sur le portail) est lue automatiquement par l'IA. " +
      "Les pièces où l'IA n'est pas sûre à 95%, ou qui présentent un risque de fraude détecté par notre agent de contrôle, " +
      'repassent ici pour votre validation avant toute comptabilisation.',
  },
  'badge-confiance': {
    titre: "Confiance de l'IA",
    texte:
      "Pourcentage de certitude du modèle sur les montants et la date qu'il a lus sur la pièce. " +
      'Au-dessus de 95%, la lecture est jugée fiable. En dessous, lisez le motif affiché à côté du badge : ' +
      "il explique précisément ce qui a fait douter l'IA (photo floue, date peu lisible...).",
  },
  'badge-fraude': {
    titre: 'Alerte de fraude',
    texte:
      'Notre agent de contrôle vérifie automatiquement chaque pièce : cohérence de la TVA, montants suspicieusement ronds, ' +
      "doublons déjà enregistrés, et (si activé) incohérences visuelles sur les reçus Mobile Money. " +
      'Un badge rouge signifie une vérification manuelle obligatoire avant de comptabiliser.',
  },
  'rapprochement-semantique': {
    titre: 'Rapprochement sémantique Mobile Money',
    texte:
      "Une fois le rapprochement automatique par montant exact terminé, l'IA relit les libellés cryptiques " +
      "des lignes Mobile Money restantes (Wave, Orange Money, MTN Money) et propose une correspondance si elle est " +
      'sûre à plus de 90 %. En dessous de ce seuil, la ligne reste à pointer vous-même, comme avant.',
  },
  'tableau-de-bord': {
    titre: 'Tableau de bord',
    texte:
      "Vue d'ensemble de ce dossier client : résultat, soldes clés (banque, caisse), et ce qui a besoin de votre " +
      'attention (pièces à comptabiliser, écritures en brouillon, documents en erreur).',
  },
  'onboarding-whatsapp': {
    titre: 'Connexion WhatsApp Business',
    texte:
      "Une fois le numéro WhatsApp Business de votre cabinet relié (via Meta Business Manager), vos clients peuvent " +
      "vous envoyer une simple photo de facture ou de reçu Mobile Money par message. Elle est lue automatiquement " +
      "par l'IA et apparaît directement dans votre Inbox de validation, sans ressaisie.",
  },
};

const AIDE_PAR_DEFAUT: ContenuAide = {
  titre: 'Aide',
  texte: "Pas d'aide disponible pour cet élément pour le moment.",
};

@Injectable({ providedIn: 'root' })
export class AideContextuelleService {
  contenu(cle: string): ContenuAide {
    return CONTENUS_AIDE[cle] ?? AIDE_PAR_DEFAUT;
  }
}
