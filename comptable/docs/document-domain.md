# Domaine Document (Phase C, lots C1 à C7, C19, C20)

## 1. Audit de l'existant (C1)

| Brique | Constat | Décision |
|---|---|---|
| `Document` (entité) | fichier + montants HT/TVA/TTC + date + `donneesOcr` (JSON libre : fournisseur, confiance, message) + `rapportFraude` + `ecritureId`. **Aucune identité du contenu, aucun numéro de facture, aucun tiers structuré** : le fournisseur n'existait que comme texte dans un JSON. | **ÉTENDRE** (V52) : hash, numéro + normalisé, devise, tiers, rapprochement, doublon confirmé, validation |
| `DocumentService` (upload web, canal externe, extraction externe ai-service) | trois chemins d'entrée qui fabriquent le même objet ; extraction synchrone à l'upload (jamais sur GET) | **GARDER**, tous les chemins passent maintenant par `DocumentPipeline.apresReception` |
| `OcrResult` / `HeuristiqueOcrExtractionService` / `DonneesExtraitesDTO` | extraction sans numéro de facture ni devise | **ÉTENDRE** : `numeroFacture`, `devise` ; l'heuristique lit « Facture n° … » (au moins un chiffre) ; ai-service renvoie `numeroFacture` |
| `AnalyseFraudeService` (alertes de fraude, `DOUBLON_POTENTIEL`) | doublon « strict » = même TTC ± 3 jours dans l'entreprise | **GARDER** comme alerte de fraude ; `DOCUMENT_AT_RISK` n'est plus émis quand le rapport ne contient QUE des doublons (couvert par les constats gradués) |
| `LocalDocumentStorageService` | disque local `{tenant}/{entreprise}/{uuid}-{nom}` ; l'isolation est le chemin | **GARDER** (voir §6) |
| `DemandePiece` + `RelancePieceService` + `RelanceScheduler` + `WhatsAppResolutionService` | demande libre, relance WhatsApp des demandes en retard, création depuis un constat de preuve manquante | **ÉTENDRE** : `ecriture_id`, `motif`, `canal`, statut `RAPPROCHEE` ; pas de nouvelle entité `DocumentRequest` |
| `WhatsAppIngestionService` | canal externe déjà idempotent sur `messageId` | **GARDER** |
| `DocumentController` `GET /documents` (liste non paginée par statut) | liste complète, sans filtre ni page | **GARDER pour le rôle CLIENT** ; le cabinet utilise le Hub paginé |
| `DocumentListComponent` (Angular) | liste + « Comptabiliser » | **GARDER pour le CLIENT** ; remplacé par `DocumentHubComponent` pour le cabinet |
| `ControleDocumentsCloture` | pièces TRAITE non comptabilisées à la clôture | **GARDER** (point de pré-clôture) |
| `DetecteurPiecesManquantes` (Phase B) | `Document.ecritureId` seulement | **ÉTENDRE** : une pièce rapprochée à la facture qui porte l'écriture justifie aussi l'écriture |
| `DocumentLiaisonService` (Phase B) | rattachement manuel pièce → écriture | **REFACTORER** : passe par `PieceEcritureLinker` (un seul endroit) |
| Tables `document` / `demande_piece` | pas d'index sur hash, numéro, tiers, statut+date | **ÉTENDRE** : seuls les index justifiés par une requête réelle (§7) |
| Rien à supprimer | aucun système documentaire parallèle n'a été créé | — |

Constats de l'audit : (a) `FactureFournisseur.numero` est un numéro **interne** (`genererNumeroFacture`) : le numéro du fournisseur n'existait nulle part → `numero_fournisseur(+_normalise)` ajouté ; (b) le fichier `demande.model.ts` du frontend avait des accents corrompus ; (c) les routes `/revue/**` et `/lineage/**` de la Phase B n'avaient aucun contrôle de rôle (voir rapport).

## 2. Identité forte (C2)

`content_hash` = SHA-256 hexadécimal du contenu réel, calculé côté backend (`ContentHasher`), jamais fourni par le client, stable, indexé **par entreprise** (`idx_document_hash (entreprise_id, content_hash)`). L'unicité n'est pas imposée en base : un doublon est un **constat** à traiter, pas un refus d'écriture (la pièce reste tracée), et le même fichier peut légitimement exister chez deux entreprises (preuve : `les_pieces_… _depuis_B`).

## 3. Métadonnées (C3)

| Besoin | Colonne / source |
|---|---|
| type | `type_document` (existant) : `FACTURE_ACHAT, FACTURE_VENTE, RECU, RELEVE_BANCAIRE, DOCUMENT_PAIE, DOCUMENT_SOCIAL, DOCUMENT_FISCAL, CONTRAT, AUTRE` |
| nom, mime, taille, hash | `nom_fichier_original`, `content_type`, `taille_octets`, `content_hash` |
| reçu le / canal | `created_at`, `canal_source` : `WEB` (= upload), `WHATSAPP`, `EMAIL`, `API`, `SCAN`, `MANUAL` (reconnus par le modèle, tous les canaux ne sont pas implémentés) |
| n° de facture fournisseur | `numero_facture` (valeur d'origine) + `numero_facture_normalise` |
| date, devise, montants | `date_piece`, `devise`, `montant_ht/tva/ttc` |
| tiers | `tiers_id` + `statut_classification` + `confiance_tiers` |
| statuts | `statut` (extraction : EN_ATTENTE → EN_COURS_TRAITEMENT → TRAITE / ERREUR / EN_ATTENTE_VALIDATION_HUMAINE) + `statut_rapprochement` (voir `document-matching.md`) + `valide_at` |

Pas de champ redondant : un `Document` porte *sa* donnée ; la facture, l'écriture et le constat portent la leur, liés par `cible_type/cible_id`, `ecriture_id` et `Finding`.

## 4. Numéro de facture (C4)

`NumeroFactureNormalizer` : accents retirés, majuscules, tout ce qui n'est pas lettre ou chiffre supprimé. `"FAC- 001/2026"` → `"FAC0012026"`. Volontairement pas de fuzzy : deux numéros normalisés égaux sont identiques. La valeur d'origine est toujours conservée ; le setter maintient les deux colonnes cohérentes. Mêmes règles sur `facture_fournisseur.numero_fournisseur_normalise` et `facture.numero_normalise`.

## 5. Cycle de traitement (C6) et extraction (C17)

Les statuts existants suffisent ; aucune nouvelle enum n'a été créée :

```
RECEIVED         → statut EN_ATTENTE / EN_COURS_TRAITEMENT          audit DOCUMENT_RECU
EXTRACTED        → statut TRAITE (ou ERREUR)                         audit DOCUMENT_EXTRAIT
CLASSIFIED       → statut_classification EXACT/PROBABLE/AMBIGU/INCONNU   audit DOCUMENT_CLASSE
MATCHING         → statut_rapprochement                              audit DOCUMENT_RAPPROCHE / _PROPOSE
MATCHED          → RAPPROCHE (cible_type/cible_id)
NEEDS_REVIEW     → A_CONFIRMER / AMBIGU + constats DOCUMENT_*        
VALIDATED        → valide_at / valide_par_id                         audit DOCUMENT_VALIDE
ARCHIVED         → non modélisé : une pièce validée sort du travail restant et reste consultable
```

Chaque champ extrait est conservé dans `donnees_ocr.champs` : `{valeur, confiance, source}`. Les moteurs actuels ne donnent qu'une confiance globale par pièce : c'est elle qui est stockée, avec le moteur comme source (honnêteté : pas de confiance par champ inventée).

**Règle IA** : l'IA lit (extraction, nom du fournisseur, numéro) ; une règle Java décide. Aucune écriture n'est créée par ce pipeline ; la comptabilisation passe toujours par nature → `AccountPurpose` → `AccountingAccountResolver` → `AccountingOperationEngine`.

## 6. Stockage (C19)

* **Métadonnées en base** (PostgreSQL), **binaire sur disque** : `LocalDocumentStorageService` écrit `{racine}/{tenantId}/{entrepriseId}/{uuid}-{nom assaini}` ; la base ne contient que le chemin. Le chemin résolu est contraint sous la racine (`startsWith`), le nom est assaini.
* Choix assumé : disque local suffit pour une instance unique (déploiement actuel : un backend + un volume). **Limites** : pas de multi-instance, sauvegarde du volume à organiser, pas de déduplication physique (le hash permet de la décider plus tard).
* Évolution : l'interface `DocumentStorageService` isole déjà le binaire ; une implémentation objet (S3 compatible, préfixe par tenant) se branche sans toucher au domaine. **Non migré** : aucune contrainte actuelle ne le justifie.

## 7. Performance (C20)

* extraction à l'**upload** seulement, jamais sur un GET ; le Hub ne fait aucun OCR ;
* pagination et filtres **en base** (`Specification`), taille de page bornée à 100 ;
* libellés d'une page (tiers, factures, constats) chargés **par lot** : 5 requêtes pour 25 lignes parmi 500 (mesuré) ;
* index créés, chacun justifié par une requête : `(entreprise_id, content_hash)` (doublon exact), `(entreprise_id, tiers_id, numero_facture_normalise)` (doublon de facture, matching), `(entreprise_id, cible_type, cible_id)` (liens retour, lineage), `(entreprise_id, statut, created_at desc)` (Hub), `facture_fournisseur(entreprise_id, fournisseur_id, numero_fournisseur_normalise)`, `facture(entreprise_id, numero_normalise)`, `demande_piece(ecriture_id)`.
* mesure (500 pièces, `DocumentVolumeTest`) : un passage de contrôles = 171 requêtes SQL, ~0,8 s ; la page du Hub = 5 requêtes.
* bug trouvé et corrigé grâce à cette mesure : l'évidence JSON d'un `Finding` (valeurs `BigDecimal`) n'était jamais égale à sa relecture, Hibernate émettait un `UPDATE` par constat à chaque flush (1 340 UPDATE pour 35 constats). `EvidenceJson` normalise les valeurs par un aller-retour JSON : 1 UPDATE.
