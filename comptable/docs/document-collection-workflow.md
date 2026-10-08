# Collecte des pièces manquantes (Phase C, lots C11 à C13)

## 1. Un document n'est pas une demande de document

L'absence de pièce n'est **pas** un statut de `Document` (il n'y a pas de document) : elle est portée par le constat `MISSING_DOCUMENT` sur une **écriture**. La **demande** est ce qu'on envoie au client ; c'est `DemandePiece` (existant, étendu) — pas de nouvelle entité `DocumentRequest`.

```
écriture d'achat sans justificatif
   │  DetecteurPiecesManquantes (ControlResult)
   ▼
Finding MISSING_DOCUMENT  (entité ECRITURE_COMPTABLE)         « MISSING »
   │  Révision : « Demander la pièce »  (POST /demandes-pieces/depuis-constat/{id})
   ▼
DemandePiece EN_ATTENTE (ecriture_id, motif, canal, date limite)   « REQUESTED »  — audit DOCUMENT_DEMANDE
   │  le client répond sur la demande (…/repondre) ou la pièce arrive par un autre canal
   ▼
Document reçu → RECU                                          « RECEIVED »
   │  rattachement : à l'écriture (pièce_ecriture_linker) ou par rapprochement à la facture
   ▼
DemandePiece RAPPROCHEE  +  Finding RESOLVED / SYSTEM         « MATCHED » — audit DOCUMENT_RECU_APRES_DEMANDE
   │  validation humaine éventuelle (Document.valide_at)
   ▼
« VALIDATED »
```

## 2. Détection (C11)

`DetecteurPiecesManquantes` : écriture issue du moteur (achat, avoir d'achat, acquisition d'immobilisation) sans `Document` lié. **Une écriture est justifiée** si une pièce porte son `ecriture_id` **ou** si une pièce est rapprochée à la `FactureFournisseur` qui porte cette écriture. Au-delà de 20 écritures sans pièce : un seul constat agrégé (`PIECES_MANQUANTES`) dont `relatedEntityIds` donne accès à chaque écriture. Une écriture saisie à la main n'est pas concernée (pas de proposition du moteur).

## 3. Une seule façon de justifier une écriture : `PieceEcritureLinker`

Le rattachement manuel (`lier-ecriture`), le rapprochement à une facture, la réponse à une demande passent tous par lui : pose `Document.ecritureId`, ferme `MISSING_DOCUMENT` **immédiatement** (le détecteur le fermerait de toute façon au passage suivant), clôt les demandes en `EN_ATTENTE`/`RECU` de cette écriture en `RAPPROCHEE`. Refuse (false → 409) si une autre pièce justifie déjà l'écriture.

## 4. Demande et relances (C12, C13)

* **Création** (`DemandePieceService.demanderPourConstat`) : seulement pour un constat `MISSING_DOCUMENT` ouvert portant sur une écriture ; une seule demande par constat (409 sinon) ; canal facultatif (`MANUEL` par défaut) ; échéance et message facultatifs.
* **Réponse** : `repondre` dépose la pièce et, si la demande vise une écriture, la rattache à celle-ci (constat résolu, demande `RAPPROCHEE`).
* **Relances (audit)** : `RelancePieceService` + `RelanceScheduler` relancent déjà, par WhatsApp, les demandes `EN_ATTENTE` dont la date limite est dépassée, vers les clients rattachés à l'entreprise, avec trace (`RelancePieceEnvoyee`). **Réutilisées telles quelles** : une demande issue d'un constat bénéficie donc de la relance dès qu'elle a une date limite. Pas de moteur omnicanal ni marketing.
* **Statistiques** : une demande `RAPPROCHEE` compte comme résolue dans les indicateurs cabinet et KPI produit.

## 5. Ce qui n'est pas fait (volontairement)

* envoi e-mail/WhatsApp *à la création* d'une demande (seule la relance d'une demande en retard existe aujourd'hui) ;
* demande en masse : « relancer plusieurs demandes » repose sur le planificateur existant ; un bouton de masse sans aperçu serait dangereux ;
* demande pour un constat agrégé (`PIECES_MANQUANTES`) : traiter d'abord les écritures une à une depuis `relatedEntityIds`.
