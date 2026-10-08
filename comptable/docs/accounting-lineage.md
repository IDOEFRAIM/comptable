# Traçabilité de la source comptable (Phase B, lot B0)

Toute conséquence comptable a une **cause métier identifiable**, et une même cause ne produit **jamais deux écritures**.
Ce document décrit le modèle, ses garanties (portées par la base, pas seulement par le code) et la navigation dans les deux sens.

## 1. La cause : `AccountingSource(type, id, event)`

| Champ | Sens | Exemples |
|---|---|---|
| `type` | l'objet métier à l'origine | `DOCUMENT`, `FACTURE`, `FACTURE_AVOIR`, `FACTURE_FOURNISSEUR`, `AVOIR_FACTURE_FOURNISSEUR`, `MOUVEMENT_STOCK`, `IMMOBILISATION`, `EXERCICE`, `INVENTAIRE`, `REGULARISATION` |
| `id` | identifiant de cet objet | UUID |
| `event` | l'événement métier qui produit l'écriture | `COMPTABILISATION`, `VALIDATION`, `ACQUISITION`, `DOTATION_2027`, `ENTREE` / `SORTIE` / `AJUSTEMENT`, `CLOTURE` |

*(entreprise, type, id, event)* est la **clé d'idempotence métier**. Elle est portée par `accounting_operation_proposal`
(`source_type`, `source_id`, `source_event`, tous `NOT NULL`) et garantie par un **index unique partiel** (`uq_aop_source_event`,
migration V50) : au plus une proposition *active* (donc au plus une écriture) par cause. Une proposition `REJECTED` libère la cause.

Cas particuliers :
* une opération saisie sans objet métier devient **sa propre cause** (`OPERATION_SAISIE`, identifiant neuf) ;
* une proposition `NEEDS_REVIEW` (configuration incomplète) est **ré-évaluée sur place** au rejeu, au lieu de rester figée sur une ancienne explication ;
* les flux « Document manuel » et « IA » partagent la même cause `DOCUMENT/COMPTABILISATION` : un document ne peut pas être comptabilisé deux fois en passant d'un flux à l'autre ;
* la dotation d'amortissement porte l'année dans l'événement (`DOTATION_2027`) : N et N+1 sont deux causes distinctes.

Le moteur (`AccountingOperationEngine`) est la seule porte : toutes ses méthodes `proposerEtValider*` reçoivent un `AccountingSource`.
La seule autre production d'écriture légitime est la saisie manuelle (`EcritureComptableService.creerManuelle`), identifiée comme telle.

## 2. Facture ↔ immobilisation : une seule acquisition

Une immobilisation référence sa conséquence comptable par `immobilisation.acquisition_proposal_id` (relation traçable, pas un booléen
`comptabilisee`). Deux chemins, mutuellement exclusifs :

1. **Lier** (`POST …/immobilisations/{id}/lier-acquisition`, `{sourceType, sourceId}`) : l'immobilisation pointe vers la proposition d'achat *déjà
   approuvée* de la facture. Aucune seconde écriture. Contrôles : proposition d'achat approuvée et unique pour la source ; la facture débite le
   compte d'actif du bien ; **capacité de valeur** (somme des biens déjà rattachés + ce bien ≤ débit facturé sur ce compte).
2. **Comptabiliser directement** (`…/comptabiliser-acquisition`) : source = l'immobilisation elle-même. **Garde anti-double-comptage** : si une facture
   d'achat approuvée débite le même compte pour la même valeur sans être rattachée à un bien, la demande est refusée (409) tant que l'utilisateur ne
   confirme pas explicitement (`confirmerAbsenceDeFacture`). La confirmation est tracée dans l'audit.

Rejouer l'un ou l'autre ne crée rien : la cause existe déjà (409).

Si le doublon passe malgré tout (confirmation explicite, ou saisie manuelle), le détecteur `DetecteurImmobilisations` ouvre un constat
`POSSIBLE_DOUBLE_ACQUISITION` ; un bien sans aucune source ouvre `FIXED_ASSET_WITHOUT_SOURCE` (voir `control-and-finding-architecture.md`).

## 3. Navigation dans les deux sens (sans heuristique)

`GET /api/entreprises/{e}/lineage/ecritures/{ecritureId}` → écriture → proposition → source (→ document). Origine : `MOTEUR` ou `SAISIE_MANUELLE`
(sans proposition).

`GET /api/entreprises/{e}/lineage/sources/{sourceType}/{sourceId}` → source → toutes ses conséquences comptables (une par événement).

Le lien proposition → écriture est `ecriture_id` ; le lien document → écriture est `Document.ecritureId` ; aucune jointure floue (montant, libellé, date).
Une entité d'une autre entreprise répond **404** (même convention que `EntrepriseAccessService`).

## 4. Migration V50

* `source_event` ajouté à `accounting_operation_proposal`, rétro-rempli (`COMPTABILISATION` par défaut, `VALIDATION` pour les factures, etc.), puis `NOT NULL` ;
* index unique partiel `uq_aop_source_event` ;
* `immobilisation.acquisition_proposal_id` rétro-rempli depuis l'ancien `acquisition_ecriture_id` (supprimé).

## 5. Audit

`PROPOSITION_COMPTABLE_CREEE` / `_A_REVOIR` / `_REJETEE`, `SOURCE_COMPTABLE_LIEE` (liaison facture ↔ immobilisation), `IMMOBILISATION_ACQUISE`
(avec `confirmationSansFacture`). Les détails de chaque événement portent la source `type/id/event`.
