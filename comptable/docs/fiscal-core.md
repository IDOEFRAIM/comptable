# Fiscal Core (Phase G — G0)

Socle fiscal **versionné** de Comptable : la fiscalité est une couche distincte de la comptabilité SYSCOHADA.

```
Écritures validées ──► Classification fiscale ──► Cadre fiscal (règles vérifiées) ──► Calcul ──► Contrôles (Findings)
                                                                                                    └─► Déclaration ──► Revue ──► FINAL (snapshot immuable)
```

## 1. Ce qui est de l'infrastructure, ce qui est réglementaire

| Élément | Nature | État |
|---|---|---|
| Cadre fiscal versionné (`tax_framework`), cycle DRAFT → VERIFIED → RETIRED, hash de définition, sources obligatoires à la vérification | infrastructure | **READY** |
| Période déclarative (`tax_period`), distincte de l'exercice, cycle OPEN → PREPARING → READY_FOR_REVIEW → REVIEWED → FINAL | infrastructure | **READY** |
| Traitement fiscal (`TaxTreatment`) et décision humaine par opération (`tax_operation_treatment`) | infrastructure | **READY** |
| Ajustements justifiés, annulables, jamais supprimés (`tax_adjustment`) | infrastructure | **READY** |
| Déclaration (`tax_declaration`) et snapshot immuable (`tax_declaration_snapshot`, verrouillé par trigger) | infrastructure | **READY** |
| Taux de TVA, périodicité officielle, échéances légales, cases du formulaire, exceptions par pays | **réglementaire** | **aucune valeur dans le code ni dans la base** |
| Cadre fiscal VERIFIED pour une juridiction réelle | **réglementaire** | **absent** (aucune source officielle vérifiée fournie) |

> **Aucune règle fiscale n'est inventée.** L'application ne sème aucun cadre. Les taux de 10 et 5 que l'on voit dans les tests et les captures sont des **valeurs fictives** (créées par les tests dans un cabinet jetable) : elles n'existent dans aucune migration ni dans aucun code de production.

## 2. Cadre fiscal (`TaxFramework`)

`jurisdiction` (code pays) · `taxType` (TVA uniquement pour l'instant) · `version` · `effectiveFrom` / `effectiveTo` · `status` · `rules` (JSON versionné) · `sourceReferences` · `definitionHash` (SHA-256 du contenu) · `verifiedBy` / `verifiedAt`.

* **Création** (expert-comptable) : toujours **DRAFT**. Les règles ne sont jamais modifiées ensuite : une correction est une **nouvelle version** (conflit 409 sinon).
* **Vérification** (expert) : exige **au moins une référence de source** (422 sinon), trace `CADRE_FISCAL_VERIFIE` avec le hash.
* **Retrait** : `RETIRED`, jamais retourné par la résolution.
* **Portée** : `tenant_id` NULL = cadre de plateforme (aucun semé) ; sinon cadre du cabinet, **prioritaire**. L'API actuelle ne gère que les cadres du cabinet ; un cadre de plateforme se ferait par migration ou outil d'administration (non fourni).
* **Résolution** pour (juridiction, type, date) : VERIFIED d'abord (cabinet avant plateforme, le plus récent), sinon le dernier DRAFT (retourné avec `verifie = false` : affichable, **jamais finalisable**).
* **Règles TVA** (`VatRules`) : `rates` (code, libellé, pourcentage), `periodicity`, `due` (règle d'échéance : mois après la fin de période + jour du mois), `toleranceAmount`, `rateMatchTolerancePoints`, `lines` (lignes de déclaration, codes officiels fournis par la source). Sans `lines`, la déclaration utilise une structure **générique interne explicitement non officielle**.

API (`/api/cabinet/fiscal/cadres`, expert pour toute mutation, collaborateur en lecture) : `GET`, `POST`, `POST /{id}/verifier`, `POST /{id}/retirer`. Audit : `CADRE_FISCAL_CREE`, `CADRE_FISCAL_VERIFIE`, `CADRE_FISCAL_RETIRE`. Un cadre d'un autre cabinet est introuvable (404).

## 3. Période fiscale (`TaxPeriod`)

Une période déclarative n'est pas un exercice comptable : elle porte ses propres dates (une seule par entreprise et type d'impôt sur une plage donnée ; chevauchement refusé en 409 ; 14 mois maximum). Statuts : `OPEN`, `PREPARING`, `READY_FOR_REVIEW`, `REVIEWED`, `FINAL`. **Toute modification après READY_FOR_REVIEW (décision, ajustement, écriture, nouveau cadre vérifié) ramène à PREPARING et annule la revue** (détection par empreinte des montants).

## 4. Classification fiscale (`TaxTreatment`)

`OUTPUT_TAX`, `INPUT_TAX`, `EXEMPT`, `OUT_OF_SCOPE`, `NON_DEDUCTIBLE`, `PARTIALLY_DEDUCTIBLE`, `TO_REVIEW`. **Indépendante du compte** : aucun « compte 443 = TVA collectée » universel. Les deux comptes de TVA d'une entreprise sont ceux de son **paramétrage comptable** (purposes `TVA_COLLECTEE` / `TVA_RECUPERABLE`, résolveur existant). Ordre de détermination :

1. **décision humaine** justifiée (`tax_operation_treatment`) ;
2. **sens de la TVA comptabilisée** (crédit sur le compte de TVA collectée → `OUTPUT_TAX` ; débit sur la TVA récupérable → `INPUT_TAX`) — certain structurellement ;
3. sinon **`TO_REVIEW`** + constat. Jamais « le premier mapping trouvé ».

## 5. Findings fiscaux (réutilisation de `Finding`)

Pas de `TaxAlert` : les anomalies sont des `Finding` (catégorie TAX, entité `TAX_OPERATION` ou `TAX_PERIOD`), donc visibles dans l'Inbox de révision, le Cabinet Workspace et l'espace TVA. Codes : `TAX_TREATMENT_MISSING`, `TAX_RATE_UNRESOLVED`, `VAT_BASE_MISMATCH`, `VAT_AMOUNT_MISMATCH`, `DUPLICATE_TAX_DOCUMENT`, `TAX_PERIOD_INCOMPLETE`. Gravité ERROR (WARNING pour « aucun cadre vérifié ») : ils **ne bloquent pas** la clôture comptable (ils ne comptent pas dans le statut comptable du dossier). Publication **idempotente** (un constat actif par contrôle et par objet), **résolution automatique** (source SYSTEM) quand la cause disparaît. Déclenchement : à l'ouverture de la période, à chaque décision / ajustement / recalcul, et **proactivement** à chaque écriture validée couvrant une période non finalisée (après commit, coalescé par transaction).

## 6. Audit et droits

Audit : `TRAITEMENT_FISCAL_MODIFIE`, `AJUSTEMENT_FISCAL_AJOUTE`, `AJUSTEMENT_FISCAL_ANNULE`, `DECLARATION_FISCALE_REVUE`, `DECLARATION_FISCALE_FINALISEE`, `PERIODE_FISCALE_OUVERTE`, `CADRE_FISCAL_*`. Droits : collaborateur et expert préparent (période, classification, justification, ajustements) ; **expert seul** : revue, finalisation, gestion des cadres ; **client : aucun accès** (403). Autre cabinet : 404.

## 7. Gate

| Composant | Verdict |
|---|---|
| **Fiscal Core** (cadre versionné, période, classification, ajustements, snapshot immuable, droits, audit, multi-tenant) | **READY** |
| **Regulatory Pack** (taux, échéances, cases officielles, exceptions pays) | **BLOCKED** : aucune source officielle vérifiée n'a été fournie ; rien n'est codé |

## 8. Infrastructure G3A (suite de G0)

Détail et chiffres : `fiscal/g3a-infrastructure-report.md`. Ajouts indépendants de la réglementation :

* **Échéances** : `TaxDeadlineGenerator` ne calcule une période ou une échéance que depuis la règle (`periodicity` + `due`) d'un cadre VERIFIED. L'ancien `EcheancierTvaGenerator` (« 15 du mois suivant ») est **supprimé**.
* **Report de crédit** : `VatRules.carryForward` (optionnel, absent par défaut). Sans cadre VERIFIED qui le prévoit, aucun crédit n'est validé ni reporté.
* **Constat de cadre** : `TAX_FRAMEWORK_NOT_VERIFIED`, un par période, résolu automatiquement quand un cadre VERIFIED est installé.
* **Concurrence** : `tax_period.version` (jeton `expectedVersion`, 409 si périmé).
* **Gates** : Fiscal Core READY ; VAT Infrastructure READY ; BF Regulatory Pack **BLOCKED** ; BF VAT Finalization **BLOCKED**.

## 9. G3A.1 — invariants de robustesse

`tax_period.version` : toute mutation fonctionnelle d'une période l'incrémente exactement une fois par transaction (`PeriodVersion`) ; `expectedVersion` est obligatoire sur toute mutation utilisateur de l'API (422 si absent, 409 si périmé). Statut de déclaration (`TaxPeriod.status`) et statut de règlement (`payment.status`) sont distincts : le règlement n'altère jamais le snapshot FINAL. Horloge unique : `TimeConfig` (`Clock`). Détail : `fiscal/g3a-infrastructure-report.md`.
