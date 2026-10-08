# Relances de pièces — workflow (Phase C+)

Le comptable ne court plus derrière chaque client : le système demande, relance, reçoit et rapproche ; il ne reste que l'exception.

## De la pièce manquante à la résolution

1. Une opération est comptabilisée sans pièce → le contrôle `MISSING_DOCUMENT` ouvre un constat.
2. Hub **Collecte** (page Documents) : « Opérations sans pièce ». Le comptable coche une ou plusieurs lignes, clique « Demander N pièce(s) ».
3. **Aperçu calculé par le serveur** : nombre de pièces, destinataires, nombre de messages, texte exact. Rien n'est créé.
4. « Confirmer » : une demande par opération, un message par destinataire. Case « Envoyer maintenant » (sous réserve de `collecte.envoi.immediat`).
5. Le worker envoie ; la demande passe `EN_ATTENTE`, première relance programmée à J+3.
6. Le client ouvre le lien, dépose la photo/PDF (page publique, aucune donnée interne).
7. La pièce suit **exactement** le pipeline documentaire (analyse, fraude, rapprochement) ; le matcher la compare aux demandes ouvertes.
8. `EXACT` → demande et constat résolus automatiquement. `PROBABLE` → le comptable confirme en un clic. `AMBIGUOUS` → constat, le comptable choisit.

## Politique de relance

* Relance 1 : `collecte.relance.delais-jours` = `3,7` → 3 jours après l'envoi ; relance 2 : 7 jours après la relance 1 ; ensuite **plus de message automatique** :
  constat `DOCUMENT_REQUEST_NO_RESPONSE`, le comptable décide.
* Avant **chaque** relance, l'état réel est recalculé : demande encore ouverte ? pièce déjà reçue (ou en attente de décision) ? constat déjà résolu ?
  demande annulée ? expirée ? Dans tous ces cas aucun message ne part (pièce déjà arrivée → demande clôturée proprement).
* Plafond : `collecte.relance.max-messages-par-jour=2` par destinataire ; au-delà la relance est reportée au lendemain.
* Regroupement : toutes les relances dues d'un même client partent dans **un** message.
* Un client sans contact joignable : relance bloquée (`ECHEC_ENVOI`, motif explicite), jamais d'appel fournisseur.
* Expiration : `collecte.expiration-jours=60` sans réponse → `EXPIREE`.
* Compteurs distincts : `nb_relances` (client) ≠ `attempt_count` (technique). Un échec technique n'est jamais une relance.
* Relance manuelle (bouton) : seulement si la demande est `EN_ATTENTE` ; sinon 409.

## Cas d'exception

| Situation | Comportement |
|---|---|
| Fournisseur en panne | `PENDING` + backoff ; après 4 tentatives `FAILED`, demande `ECHEC_ENVOI` (`SEND_FAILED`), constat |
| Numéro refusé | `FAILED` sans retry, `INVALID_CONTACT` |
| Client sans téléphone | blocage immédiat `NO_CONTACT`, visible dans la Collecte |
| Pièce reçue après annulation | lien invalide (404), la pièce déposée autrement suit le Hub |
| Double clic / double envoi | 1 seule demande ouverte, 1 seul message actif |

## Mesures (`GET …/demandes-pieces/collecte/metriques`)

Pièces manquantes, demandes créées, % envoyées automatiquement, délai moyen de réception, nombre moyen de relances, % rapprochées
automatiquement, taux d'échec d'envoi, demandes nécessitant une intervention.
