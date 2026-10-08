# Messages sortants — outbox, worker, adaptateur (Phase C+)

## Principe

La création d'une demande **n'envoie rien**. Elle écrit, dans la même transaction, la demande et un `OutboundMessage` en `PENDING`
(patron *outbox*). Un worker distinct envoie après le commit. Le fournisseur n'est donc jamais appelé dans la transaction métier,
et un crash ne perd ni ne duplique un message.

```
CollecteService.demander ──tx──▶ demande_piece (A_ENVOYER) + outbound_message (PENDING) + demande_piece_message
                                   │ commit
OutboundMessageWorker ◀────────────┘ (passe planifiée, ou déclenchement immédiat si collecte.envoi.immediat)
   1. tx courte : libérer les SENDING périmés ; réserver un lot (UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING id)
   2. tx courte : recalculer l'état réel (demande annulée/reçue ? alors le message est annulé ou allégé)
   3. hors transaction : MessageDeliveryAdapter.send(message)
   4. tx courte : SENT / PENDING (retry) / FAILED, mise à jour des demandes, audit
```

## Modèle `OutboundMessage`

`PENDING → SENDING → SENT | FAILED | CANCELLED`. Champs : canal (`WHATSAPP`, `EMAIL` non implémenté), destinataire, gabarit, type
(`INITIAL`, `REMINDER`), `payload_json` (libellés lisibles + clé interne `demandeId` jamais rendue), `attempt_count`, `max_attempts`,
`next_attempt_at`, `locked_at`, `provider_message_id`, `last_error`, `error_kind`, `idempotency_key`.
`SENT` signifie « accepté par le fournisseur », **jamais** « délivré ».

## Concurrence

* Réservation par `FOR UPDATE SKIP LOCKED` : deux workers (deux threads, deux instances) ne prennent jamais le même message
  (test : 2 threads sur 1 message → exactement 1 appel fournisseur).
* Un message resté `SENDING` plus de 10 minutes est libéré puis réessayé.
* Idempotence : clé SHA-256 + unicité `(entreprise_id, idempotency_key)` ; un message actif (PENDING/SENDING) par demande bloque tout doublon.

## Regroupement

Un message par **destinataire** : 10 pièces d'un même client → 10 demandes, 1 message (table de jointure `demande_piece_message`).
Volume vérifié : 100 pièces / 10 clients → 100 demandes / 10 messages, aucun doublon ni perte.
Destinataires : `collecte.destinataires=PREMIER` (un interlocuteur) ou `TOUS`. Contacts = utilisateurs `CLIENT` rattachés à
l'entreprise **avec téléphone** ; sans contact, la demande est bloquée (`ECHEC_ENVOI`, motif `NO_CONTACT`), aucun appel fournisseur,
visible dans la Collecte et la revue.

## Retry technique (≠ relance client)

| | Retry technique | Relance client |
|---|---|---|
| Objet | le **même** message n'est pas parti | le message est parti, le client n'a pas répondu |
| Compteurs | `attempt_count` sur le message | `nb_relances` sur la demande |
| Effet | demande inchangée, `next_attempt_at` = backoff (`5,30,120` min), plafond `collecte.retry.max-tentatives=4` | **nouveau** message `REMINDER` |
| Fin | plafond atteint → `FAILED`, demande `ECHEC_ENVOI` (`SEND_FAILED`) | relances épuisées → constat `NO_RESPONSE` |

Échec **permanent** (4xx hors 408/429 : numéro invalide…) : pas de retry, demande `ECHEC_ENVOI` (`INVALID_CONTACT`).

## Adaptateur

`MessageDeliveryAdapter` (`canal()`, `send(message) → DeliveryResult{SENT|RETRYABLE|PERMANENT}`). `WhatsAppDeliveryAdapter` s'appuie sur
`WhatsAppCloudApiClient` (ai-service) et utilise le gabarit approuvé `collecte.whatsapp.template-demande/-relance`
(3 paramètres positionnels, contrainte Meta : hors fenêtre de 24 h seul un gabarit est autorisé). Les adaptateurs sont résolus par canal
**à l'envoi**. Les tests utilisent un faux adaptateur : jamais de vrai WhatsApp.

## Interrupteurs (tous **désactivés** par défaut ; un interrupteur coupé n'est pas une erreur)

| Propriété | Effet |
|---|---|
| `collecte.envoi.actif` | le planificateur envoie la boîte d'envoi (retry technique inclus) toutes les `collecte.envoi.delai-ms` |
| `collecte.relance.actif` | la politique de relance s'évalue selon `collecte.relance.cron` |
| `collecte.envoi.immediat` | l'envoi part juste après la création (sinon la demande reste `A_ENVOYER`) ; la case « Envoyer maintenant » de l'aperçu ne peut qu'être **plus restrictive** |
| `collecte.lien-depot.base-url` / `.validite-jours` / `.secret` | lien de dépôt (absent → message sans lien) |

## Lien de dépôt public

`/api/depot/{jeton}` est la **seule** route anonyme de la collecte. Jeton = `base64url(idDemande).base64url(HMAC-SHA256(secret, idDemande))`,
comparé en temps constant, puis la demande réelle est relue : annulée, rapprochée ou expirée → même `404` que pour un jeton forgé
(aucune information ne fuit). Toutes les routes de collecte, de revue et de lineage restent réservées au cabinet (`CLIENT` → 403).
Le front n'envoie jamais la session d'un utilisateur connecté vers `/api/depot/**`.
