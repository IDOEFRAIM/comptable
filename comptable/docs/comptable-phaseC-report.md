# Rapport Phase C — de la pièce brute à l'opération comptable fiable

## 1. Avant / après

**AVANT** : un document = un fichier stocké. Le fournisseur n'était qu'un texte dans un JSON ; aucun numéro de facture, aucune empreinte du contenu ; un doublon n'était qu'une alerte de fraude sur le montant ; rien ne reliait une pièce à une facture ; l'absence de pièce n'apparaissait nulle part ; l'écran demandait de coller des UUID.

**APRÈS** :

```
fichier ─► hash SHA-256 ─► extraction (valeur, confiance, source) ─► tiers identifié (EXACT/PROBABLE/AMBIGU/INCONNU)
        ─► dédupliqué (fichier / facture / suspicion) ─► rapproché à une facture (EXACT auto, PROBABLE proposé, AMBIGU constat)
        ─► lié à la comptabilité (facture → proposition → écriture, lineage dans les deux sens)
        ─► exception si nécessaire (Finding → Révision)  ─► pièce manquante → demande → réception → constat résolu par le système
```

## 2. Lots

| Lot | Résultat |
|---|---|
| **C0** | Suite Maven complète **632 tests, BUILD SUCCESS, 0 échec, 0 erreur** sur la base de la Phase B ; `npm run build` OK ; `pytest` 328 ; écran Révision testé dans un navigateur (§4) |
| **C0.1** | Codes de refus **structurés** : `AccountResolutionCode`, `ResolvedAccount.code`, `CompteNonUtilisableException`, `Blocages`/`ResultatRegle.codes`. `RefusComptablePublisher` ne lit plus jamais le texte (`codeDe` et son test supprimés) |
| **C1** | Audit : `document-domain.md` §1 (existant / garder / étendre / supprimer / refactorer) |
| **C2–C4** | V52 : `content_hash`, `numero_facture(+_normalise)`, `devise`, `tiers_id`, rapprochement, doublon confirmé, validation ; canaux et types étendus ; numéro du fournisseur sur `facture_fournisseur` (le numéro existant est interne) |
| **C5, C16** | `DetecteurDocuments` : `EXACT_DUPLICATE`, `DUPLICATE_INVOICE` (ex-réservé, maintenant réel), `POSSIBLE_DUPLICATE`, `DOCUMENT_UNREADABLE`, `DOCUMENT_AMBIGUOUS`, `DOCUMENT_UNMATCHED`, `DOCUMENT_AMOUNT_MISMATCH` |
| **C6–C7** | `DocumentPipeline` ; `DocumentClassifier` ; statuts existants réutilisés (aucune nouvelle enum de cycle) |
| **C8–C9** | `DocumentMatcher` (EXACT/PROBABLE/AMBIGUOUS/UNMATCHED) ; document ↔ transaction **préparé via le lineage**, non automatisé (`document-matching.md` §4) |
| **C10** | `GET /lineage/documents/{id}` ; `documents` dans le lineage d'une écriture et d'une source |
| **C11–C13** | `PieceEcritureLinker` ; `DemandePiece` étendue (écriture, motif, canal, `RAPPROCHEE`) ; `demanderPourConstat` ; relances existantes réutilisées |
| **C14–C15** | Hub documents (synthèse + liste paginée + sélecteur de candidates + marquer doublon + valider + validation en masse **avec aperçu**) ; sélecteur de facture pour les immobilisations (plus d'UUID saisi) |
| **C17** | 9 événements d'audit documentaires |
| **C18** | multi-tenant prouvé (§3) |
| **C19–C20** | stockage audité (`document-domain.md` §6) ; index justifiés ; mesures (§5) |
| **C21** | scénario PostgreSQL de bout en bout + test de volume 500 pièces |

## 3. Preuves

* `DocumentPipelineIntegrationTest` (3 tests, PostgreSQL) : scénario complet — hash, doublon exact, `DUPLICATE_INVOICE`, PROBABLE → confirmation humaine, EXACT automatique, lineage dans les deux sens, `MISSING_DOCUMENT` ouvert puis **résolu SYSTEM** à la réception, matching AMBIGU **sans aucun choix automatique**, Inbox = exactement les exceptions, demande de pièce → réponse → rapprochée, doublon confirmé qui ne revient pas, constat écarté non recréé, validation en masse (une pièce non éligible ignorée), garde anti double comptabilisation (409), écart de montant, audit ; multi-tenant (pièce, candidates, lineage, hub, demande, constat de A inaccessibles à B ; même fichier chez B = pas de doublon, pas de fuite) ; proposition rejetée jamais reproposée.
* `DocumentVolumeTest` : **500 pièces** (465 saines, 15 fichiers en double, 5 factures en double, 10 fournisseurs ambigus, 5 illisibles) → l'Inbox reçoit **35 constats exactement** (15/5/10/5), rien pour les 465 saines ; compteurs du Hub cohérents.
* Unitaires : `NumeroFactureNormalizerTest`, `DocumentClassifierTest`, `DocumentMatcherTest` (9 cas), `CodesRefusStructuresTest`.
* `PhaseCSecuriteHttpTest` : un CLIENT reçoit 403 sur 22 routes de revue / lineage / Hub / actions ; le cabinet n'est jamais bloqué ; sans jeton rien n'est accessible.

## 4. Vérification dans un navigateur (C0)

Jeu de démonstration (`HubDemoSeedTest -Dseed.hub=true`, outil de développement désactivé par défaut) ; backend + frontend lancés ; connexion avec le compte de démonstration.

* **Révision** : chargement (7 constats), tuiles, filtre par tuile, recherche (« dupont » → 1), filtre statut (écartés / résolus), détail déplié, **écart avec motif** (bouton désactivé sans motif, le constat reste en « écartés » et n'est pas recréé), **relance des contrôles**, **Demander la pièce** (demande créée), **Traiter** → navigation vers la page de l'objet. *Non exercée* : la pagination de la Révision (7 constats < 25) ; le « résoudre » humain n'a pas de bouton dans la Révision (il existe côté tableau de bord) ; la pagination du Hub est couverte côté API (500 pièces).
* **Hub** : compteurs, lignes (tiers, n°, montant, statut, association, constats), **Confirmer** une proposition (À confirmer 1 → 0, Traités 1 → 2), **Associer…** (le sélecteur liste les 3 factures candidates avec la raison ; association → « Pièces manquantes » 3 → 2).
* Le test d'écran n'a pas permis de capture d'image (délai de rendu du volet) : vérifié par lecture du DOM.

## 5. Mesures

| Mesure | Résultat |
|---|---|
| 500 pièces déposées (pipeline complet) | ~15–25 s, soit 30–50 ms par pièce |
| passage complet des contrôles (500 pièces, 35 constats créés) | 171 requêtes, ~0,8 s |
| passage complet sur dossier sain (5 ou 40 factures justifiées) | 30 requêtes, ~100 ms, indépendant du volume |
| page du Hub (25 lignes parmi 500) | 5 requêtes |

## 6. Anomalies trouvées en route (corrigées)

1. **Sécurité (Phase B)** : `/api/entreprises/*/revue/**`, `/lineage/**` et `documents/*/lier-ecriture` n'avaient **aucun matcher de rôle** : un CLIENT (le dirigeant) pouvait lire et écarter les constats internes. Démontré par un test qui échouait, puis corrigé et couvert (`PhaseCSecuriteHttpTest`).
2. **Performance (Phase B)** : l'évidence JSON des constats provoquait un `UPDATE` par constat à chaque flush (1 340 UPDATE pour 35 constats) et un faux « constat mis à jour » à chaque passage. `EvidenceJson` : aller-retour JSON ; garde-fou dans le test de volume.
3. Les alertes `DOCUMENT_AT_RISK` doublonnaient les constats de doublon sur les mêmes pièces : elles sont ignorées quand le rapport de fraude ne contient que des doublons.
4. `demande.model.ts` : accents corrompus dans les libellés (corrigés).
5. `ControlRunService.executerSiPerime` en transaction indépendante (déjà Phase B) — confirmé nécessaire par le Hub, lui aussi lu depuis des transactions en lecture seule.

## 7. Composants supprimés ou remplacés

`RefusComptablePublisher.codeDe` et `RefusComptablePublisherTest` (lecture du texte) ; saisie manuelle de l'identifiant de facture (Immobilisations) ; liste documentaire non paginée pour le cabinet (conservée pour le CLIENT). Aucun système documentaire parallèle n'a été créé.

## 8. Limites et suites

* Document ↔ transaction bancaire : lien indirect seulement (§4 de `document-matching.md`).
* La confiance d'extraction est globale par pièce (moteurs actuels) ; une confiance par champ suppose un moteur qui la fournisse.
* Aucun envoi à la création d'une demande de pièce (relance des demandes en retard seulement) ; pas de demande en masse.
* La classification du **type** de document est celle déclarée à l'envoi (web/canal) ; l'IA ne reclasse pas encore le type.
* Messages de constats sans accents (convention du backend) ; le frontend ne les retraite pas.
* Pagination de la Révision non éprouvée dans un navigateur avec plus de 25 constats.
* Stockage local : suffisant pour une instance, à remplacer par un stockage objet au passage multi-instance (interface déjà isolée).
* Tests existants adaptés : fixtures `DocumentService` (nouveau collaborateur), aucun test supprimé sauf `RefusComptablePublisherTest` (remplacé par `CodesRefusStructuresTest`, qui teste le code structuré au lieu du texte).

## 9. Résultat final

Voir « Vérification finale » ci-dessous (ajouté à la fin de l'exécution).

## Vérification finale

* `./mvnw test` : **661 tests, BUILD SUCCESS, 0 échec, 0 erreur, 1 ignoré** (l'outil de démonstration `HubDemoSeedTest`, désactivé par défaut). Un passage précédent avait relevé 1 erreur (stub manquant dans `CabinetAnalyticsServiceTest` après l'ajout du statut `RAPPROCHEE`) ; corrigée, puis la suite COMPLÈTE relancée.
* `npm run build` : OK, sans avertissement. `pytest` (ai-service) : 328 passés.
