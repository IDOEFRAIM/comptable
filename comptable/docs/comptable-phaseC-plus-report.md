# Rapport final — Phase C+ : collecte active des pièces justificatives

Critère métier : *pièce manquante → demande → client contacté → client dépose → document traité → rapprochement exact → demande résolue →
constat résolu*, sans geste du comptable (copier un numéro, écrire un message, relancer, chercher le document, retrouver l'écriture,
fermer le constat à la main). Vérifié de bout en bout par `CollecteMatchingIT.parcoursComplet_exact…` (voir §16) et dans un vrai navigateur.

Base de référence avant C+ (fin de Phase C) : Maven 661 tests verts, Angular OK, pytest 328.

## 1. Architecture avant / après

Avant : une demande de pièce était une ligne `EN_ATTENTE` ; une relance quotidienne (`relance_piece_envoyee`) appelait directement WhatsApp
dans la transaction, sans lien de dépôt, sans retry, sans regroupement, sans rapprochement automatique de la réponse.
Après : outbox (`outbound_message`) + worker + adaptateur, politique de relance configurable, lien de dépôt signé, matcher pièce ↔ demande,
constats d'exception, Hub « Collecte ». `V53__collecte_pieces.sql` supprime `relance_piece_envoyee`. Détails : `document-request-domain.md`.

## 2. Modèle `DemandePiece`
Statuts `A_ENVOYER, EN_ATTENTE, RECU, RAPPROCHEE, ECHEC_ENVOI, ANNULEE, EXPIREE` ; instantané lisible de l'opération (tiers, montant, date,
description) ; `nb_relances`, `prochaine_relance_at`, `motif_blocage`, `resolu_par`, `token_expire_at` ; une seule demande ouverte par écriture.

## 3. `OutboundMessage`
Outbox transactionnelle, idempotente, regroupée par destinataire. Voir `outbound-message-architecture.md`.

## 4. Adaptateur WhatsApp
`WhatsAppDeliveryAdapter` (gabarit approuvé à 3 paramètres) ; 4xx hors 408/429 = échec permanent. Aucun adaptateur e-mail (limite §22).

## 5. Worker
`OutboundMessageWorker` : réservation `SKIP LOCKED`, fournisseur hors transaction, mises à jour en transactions courtes, recalcul de l'état
réel avant envoi (demande annulée → message annulé).

## 6. Concurrence
Test : 2 threads, 1 message → exactement 1 appel fournisseur. Messages `SENDING` périmés libérés après 10 min.

## 7. Retry technique
`attempt_count`, `next_attempt_at`, backoff `5,30,120` min, plafond 4 ; test : échec → PENDING → succès ; plafond → `FAILED` + `ECHEC_ENVOI` ;
échec permanent sans retry.

## 8. Relances
J+3 puis J+7 puis constat humain ; plafond de 2 messages/jour/destinataire ; état réel recalculé avant chaque relance ; compteurs technique ≠ client.

## 9. Regroupement
10 pièces d'un client → 10 demandes, 1 message. Volume : 100 pièces / 10 clients → 100 demandes / 10 messages, tous envoyés, sans doublon.

## 10. Dépôt public
`/api/depot/{jeton}` ; page Angular `/depot/:jeton` (entreprise, opération, montant, date ; aucun identifiant interne) ; même pipeline documentaire.

## 11. Sécurité
Seule `/api/depot/**` est anonyme ; le jeton HMAC est l'autorisation et la demande réelle est relue. `CLIENT` : 403 sur toutes les routes internes
(`CollecteHttpIT`), anonyme : 401/403, cabinet jamais bloqué. Jeton altéré, signature d'une autre demande, inconnu, demande annulée / résolue /
expirée : 404 identique. Correctif trouvé pendant la phase : la réponse à `/repondre` renvoyait le DTO complet à un `CLIENT` (identifiants de constat et
d'écriture) → projection `DemandePieceClientDto`.

## 12. Rapprochement
EXACT / PROBABLE / AMBIGUOUS / UNMATCHED (`DemandeMatcher`), association manuelle par liste de candidates. Correctif trouvé au navigateur : une
demande déjà « Reçue » via son lien restait invisible pour sa propre pièce ; elle reste candidate pour cette pièce seulement.
Le niveau `AMBIGUOUS` était affiché `PROBABLE` dans la liste des candidates : corrigé.

## 13. Constats
`DOCUMENT_REQUEST_NO_CONTACT / SEND_FAILED / NO_RESPONSE`, `DOCUMENT_MATCH_TO_CONFIRM`, `DOCUMENT_RECEIVED_AMBIGUOUS` ; résolution `SYSTEM` (exact) ou `MANUAL`.

## 14. Audit
`DOCUMENT_REQUEST_CREATED / SENT / SEND_FAILED / REMINDER_SENT / CANCELLED / RECEIVED / MATCHED / RESOLVED / EXPIRED` (tests : CREATED, SENT, CANCELLED,
REMINDER_SENT, RECEIVED, RESOLVED vérifiés).

## 15. Frontend
Hub « Collecte » (compteurs : manquantes, à demander, boîte d'envoi, demandées, à relancer, échecs/sans contact, reçues ; liste avec client, date,
opération, montant, statut, dernier message, nombre de relances, prochaine relance ; actions envoyer / relancer / annuler / historique / voir la pièce ;
sélection multiple → aperçu serveur → confirmation avec « Envoyer maintenant ») ; panneau « Associer à une demande… » dans le Hub documents ;
page publique de dépôt ; vue simplifiée pour le rôle `CLIENT`.

## 16. Tests navigateur (réels, preview backend 8081 + frontend 4201, PostgreSQL)
Connexion cabinet de démo → Documents → Collecte : 4 opérations sans pièce → « Tout sélectionner » → aperçu (4 pièces, 1 message, texte exact, numéro masqué) →
confirmation → 4 demandes / 1 message ; le fournisseur local répond 404 : échec permanent affiché avec motif lisible (aucun vrai WhatsApp) →
page publique ouverte par le vrai lien, vrai upload multipart → « pièce envoyée » → demande `RECU`, pièce `A_CONFIRMER` (pas résolue) →
Hub documents : « Associer à une demande… » propose la demande visée en « correspondance probable » → confirmation : demande `RAPPROCHEE` (`HUMAIN`), constat
`RESOLVED`/`MANUAL`, compteurs de la Collecte mis à jour → annulation d'une demande : l'opération redevient « à demander » → « Envoyer » remet en boîte d'envoi.
Bugs trouvés et corrigés grâce à ce test : 500 `LazyInitializationException` sur `envoyer/relancer/marquer-recue` (test de régression ajouté), panneau sans la demande
indicée, Collecte non rafraîchie après une décision du Hub, motifs techniques affichés bruts. Non exercé en navigateur : relance manuelle (nécessite un fournisseur
qui accepte ; couverte en tests d'intégration).

## 17. Volume
`CollecteWorkflowIT.volume_cent…` : 100 pièces, 10 clients → 100 demandes, 10 messages, 10 appels fournisseur, aucun doublon, aucune perte.

## 18. Multi-tenant
Annulation d'une demande d'une autre entreprise refusée ; lecture croisée refusée (403/404) ; association pièce A ↔ demande B refusée ; jeton lié à sa demande ;
`CLIENT` sans accès aux API internes.

## 19. Résultat Maven
`./mvnw test` exécuté en entier après le dernier correctif : **687 tests, 0 échec, 0 erreur, 2 ignorés** (les deux outils de seed de démonstration), BUILD SUCCESS.
Nouvelles classes de la phase : `CollecteWorkflowTest` (14), `CollecteHttpTest` (9), `CollecteMatchingTest` (5), `CollecteUnitTest` (4).
Correction : une première version de ce rapport annonçait 659 tests ; les classes d'intégration étaient alors nommées `*IT`, que `./mvnw test` n'exécute pas
(elles n'avaient été lancées qu'avec `-Dtest`). Renommées en `*Test`, elles tournent maintenant dans la suite (659 + 28 = 687).
Écart avec l'ancienne référence de 661 : `RelancePieceServiceTest` (−6, ancienne relance remplacée), `RefusComptablePublisherTest` (−3, classification par texte remplacée par
des codes machine), un test d'annulation de `DemandePieceServiceTest` (−1, logique déplacée dans `CollecteService`), + nouveaux tests de la phase.

## 20. Résultat Angular
`npm run build` : OK (aucune erreur, aucun avertissement).

## 21. Résultat pytest
328 passed.

## 22. Limites restantes
* Seul le canal WhatsApp existe ; `EMAIL` est un canal déclaré sans adaptateur.
* Les contacts sont les utilisateurs `CLIENT` rattachés avec un téléphone ; pas de carnet de contacts du tiers.
* WhatsApp impose un gabarit approuvé hors fenêtre de 24 h : le gabarit `relance_pieces_comptables` doit exister côté Meta avant activation.
* Tous les interrupteurs (`collecte.envoi.actif`, `collecte.relance.actif`, `collecte.envoi.immediat`) sont désactivés par défaut : rien ne part tant qu'on ne les active pas.
* L'aperçu d'envoi n'affiche pas le lien de dépôt (il n'existe qu'à l'envoi).
* Le worker est dans l'application ; une file externe n'est pas nécessaire à ce volume.
* La page de dépôt n'a pas de limitation de débit par adresse IP (le jeton est non devinable, la taille et le type de fichier sont contrôlés).
* La phase D (banque, paie, TFT, annexes) n'est pas commencée, conformément au cadrage.
