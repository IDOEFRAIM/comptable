# Demande de pièce — domaine (Phase C+)

Une **demande de pièce** (`DemandePiece`) réclame au client le justificatif d'une opération comptabilisée sans pièce. Elle naît d'un
constat `MISSING_DOCUMENT` (un constat = une écriture) et se termine quand la pièce arrive, ou par décision humaine.

## Cycle de vie

| Statut | Sens | Ouverte ? |
|---|---|---|
| `A_ENVOYER` | créée, message en boîte d'envoi, rien n'est parti | oui |
| `EN_ATTENTE` | message accepté par le fournisseur ; on attend le client (relances automatiques) | oui |
| `RECU` | une pièce est arrivée mais n'est pas encore rattachée avec certitude (à confirmer) | oui |
| `RAPPROCHEE` | pièce rattachée à l'opération : demande résolue, constat résolu | non |
| `ECHEC_ENVOI` | envoi impossible (aucun contact, numéro refusé, échecs répétés) : motif visible, action humaine | oui |
| `ANNULEE` | annulée par le comptable | non |
| `EXPIREE` | sans réponse après `collecte.expiration-jours` | non |

Il n'existe pas de synonyme hérité : un seul statut par situation. `EN_ATTENTE` signifie « envoyée, en attente du client ».
Au plus **une demande ouverte par écriture** (index unique partiel `uq_demande_ouverte_ecriture`) : un double clic ne crée rien de plus.

## Responsabilités (aucune ne déborde sur une autre)

| Classe | Rôle |
|---|---|
| `DemandePieceService` | cycle de vie de la demande (création manuelle, réponse du client, listes) |
| `CollecteService` | orchestration : aperçu, demande groupée, envoi, relance manuelle, annulation ; écrit l'outbox |
| `OutboundMessageWorker` | livraison technique d'un message (réservation, appel fournisseur, retry technique) |
| `RelanceCollecteService` | politique de relance client (J+3, J+7, plafond quotidien, expiration) |
| `DemandeMatcher` | une pièce reçue répond-elle à une demande ? (EXACT / PROBABLE / AMBIGUOUS / UNMATCHED) |
| `DepotClientService` + `DepotLienService` | dépôt public sécurisé par lien signé |
| `PieceEcritureLinker` | lien pièce ↔ opération ↔ écriture ; ferme la demande et résout le constat |
| `FindingService` | exception humaine (constat) |

Aucun `@Lazy` n'a été introduit : `RelanceCollecteService → CollecteService → OutboundMessageWorker` est un graphe sans cycle.

## Rapprochement pièce ↔ demande

| Niveau | Règle | Effet |
|---|---|---|
| `EXACT` | même TTC (±0,01), **même tiers identifié**, date à ≤ 31 jours, une seule demande | rattachée seule : demande `RAPPROCHEE` (`resolu_par = SYSTEME`), constat `RESOLVED` / `SYSTEM` |
| `PROBABLE` | même TTC et (même tiers ou date à ≤ 5 jours), une seule demande ; ou pièce déposée par le lien d'une demande précise | pièce `A_CONFIRMER`, demande `RECU` (plus de relance) ; le comptable confirme → constat `RESOLVED` / `MANUAL` |
| `AMBIGUOUS` | plusieurs demandes également plausibles | **jamais la première** : pièce `AMBIGU`, constat `DOCUMENT_RECEIVED_AMBIGUOUS`, le comptable choisit parmi la liste |
| `UNMATCHED` | rien de plausible | la pièce suit le traitement normal du Hub |

Un dépôt ne résout **jamais** la demande à lui seul. L'IA ne produit aucune écriture : elle extrait, le matcher compare.

Association manuelle : `GET /documents/{id}/demandes-candidates` (demandeId, description, montant, date, tiers, niveau
`EXACT|PROBABLE|AMBIGUOUS|POSSIBLE`) puis `POST /documents/{id}/associer-demande`. Aucun identifiant n'est saisi à la main.
Refus : demande d'une autre entreprise (404), demande close (409), opération déjà justifiée par une autre pièce (409).

## Constats associés

`DOCUMENT_REQUEST_NO_CONTACT`, `DOCUMENT_REQUEST_SEND_FAILED`, `DOCUMENT_REQUEST_NO_RESPONSE` (relances épuisées),
`DOCUMENT_MATCH_TO_CONFIRM`, `DOCUMENT_RECEIVED_AMBIGUOUS`, produits par `DetecteurCollecte` et résolus par le réconciliateur quand la cause disparaît.

## Audit

`DOCUMENT_REQUEST_CREATED`, `_SENT`, `_SEND_FAILED`, `_REMINDER_SENT`, `_CANCELLED`, `_RECEIVED`, `_MATCHED`, `_RESOLVED`, `_EXPIRED`
(+ `DOCUMENT_RECU_APRES_DEMANDE`, événement historique conservé).

## Vue du client

Le rôle `CLIENT` reçoit `DemandePieceClientDto` (id, description, montant, date, échéance, état `A_FOURNIR|RECUE|CLOTUREE`) :
jamais d'identifiant de constat ou d'écriture, de code de contrôle, de motif interne, d'erreur fournisseur ni d'état d'envoi — y compris
dans la réponse à son propre dépôt (`/repondre`).
