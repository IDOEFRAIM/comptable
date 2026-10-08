# Pré-clôture explicable et workflow de clôture

Phase E. On **étend** `ClotureReadinessService` (pas de V2) et on réutilise le pipeline existant `ControlDetector → ControlResult → ControlReconciler → Finding`.
Aucune seconde infrastructure de contrôle.

## Audit des contrôles existants (matrice E3)

Colonnes : **ControlResult** = publié par un détecteur du pipeline ; **Finding** = ouvre un constat ; **Bloquant** = peut empêcher la clôture ; **Pré-clôture** = apparaît
dans la checklist. Un contrôle « bloquant » l'est par sa gravité `BLOCKING` (seuls les points structurels le sont, voir plus bas).

| Contrôle | Domaine | Implémentation actuelle | ControlResult | Finding | Bloquant | Pré-clôture |
|---|---|---|---|---|---|---|
| `UNMATCHED_PAYMENT`, `AMBIGUOUS_BANK_MATCH`, `MATCH_TO_CONFIRM`, `BANK_DUPLICATE_TRANSACTION` | BANK | `DetecteurRapprochementBancaire` (résumé par relevé au-delà de 50) | oui | oui | non (WARNING / INFO) | via `banque` + point `findings` |
| `INTERNAL_TRANSFER_TO_CONFIRM / _AMBIGUOUS / _WITHOUT_ENTRY` | BANK | `DetecteurVirementsInternes` (E0.2) | oui | oui | non | via `findings` |
| Rapprochement bancaire (soldes, lignes non pointées) | BANK | `ControleTresorerieTiersCloture.banque` : lignes non pointées, écart solde comptable / relevé **avec causes du debugger** (E5) | via adapter CLOSING | oui | non (WARNING) ; NOT_APPLICABLE sans relevé | oui |
| `PAYMENT_WITHOUT_INVOICE`, `OVERPAYMENT`, `PAYMENT_ALLOCATION_NEEDS_REVIEW` | CUSTOMER / SUPPLIER (selon le compte 41 / 40) | `DetecteurPaiements` (résumé au-delà de 8) | oui | oui | non | via `findings` |
| `CLIENT_CREDIT_BALANCE`, `SUPPLIER_DEBIT_BALANCE` | CUSTOMER / SUPPLIER | `DetecteurSoldesTiers` | oui | oui | non | `clients` / `fournisseurs` |
| Lettrage des tiers | CUSTOMER | `ControleTresorerieTiersCloture.lettrage` (lignes non lettrées) | adapter | oui | non | oui |
| `MISSING_DOCUMENT` | DOCUMENT | `DetecteurPiecesManquantes` (résumé au-delà de 8) | oui | oui | non | `pieces_manquantes` |
| `EXACT_DUPLICATE`, `DUPLICATE_INVOICE`, `POSSIBLE_DUPLICATE`, `DOCUMENT_UNREADABLE`, `DOCUMENT_AMBIGUOUS`, `DOCUMENT_UNMATCHED`, `DOCUMENT_AMOUNT_MISMATCH` | DOCUMENT | `DetecteurDocuments` | oui | oui | non | via `findings` |
| `DOCUMENT_REQUEST_*`, `DOCUMENT_MATCH_TO_CONFIRM`, `DOCUMENT_RECEIVED_AMBIGUOUS` | DOCUMENT | `DetecteurCollecte` | oui | oui | non | via `findings` |
| Factures traitées non comptabilisées | DOCUMENT | `ControleDocumentsCloture` | adapter | oui | non | `documents_non_comptabilises` |
| `VAT_INCONSISTENCY` (ERROR), `TAX_DECLARATION_*` | VAT | `DetecteurIncoherenceTva` (invariant implémenté uniquement) ; fiscalité de clôture | oui | oui | non | `fiscalite` |
| `STOCK_INCONSISTENCY` | STOCK | `DetecteurStock` ; `ControleActifsFiscalCloture.stocks` | oui | oui | non | `stocks` |
| `FIXED_ASSET_WITHOUT_SOURCE` (ERROR si en service), `POSSIBLE_DOUBLE_ACQUISITION` (ERROR) | FIXED_ASSET | `DetecteurImmobilisations` | oui | oui | non | `immobilisations` (NOT_APPLICABLE), `amortissements`, `depreciations`, `provisions` |
| Écritures en brouillon | JOURNAL | `ClotureReadinessService` (comptage) ; `DRAFT_ENTRY_OLD` | adapter | oui | **oui** | `brouillons` |
| Équilibre débit / crédit | JOURNAL | `ControleEcrituresCloture.equilibre` | adapter | oui | **oui** | `equilibre_ecritures` |
| Comptes désactivés utilisés, mappings invalides | ACCOUNTING | `ControleEcrituresCloture` | adapter | oui | non | oui |
| `ACCOUNT_NOT_CONFIGURED` (WARNING), `ACCOUNT_AMBIGUOUS` / `ACCOUNT_INVALID` (ERROR) | ACCOUNTING | `RefusComptablePublisher` | oui | oui | non | via `findings` |
| Exercice absent / déjà clôturé | CLOSING | `ClotureReadinessService` | adapter | oui | **oui** si aucun exercice | `exercices` |
| Régularisations non finalisées | CLOSING | `ClotureReadinessService` | adapter | oui | non | `regularisations` |
| Findings ouverts de gravité BLOCKING | CLOSING | `ClotureReadinessService` (agrège les findings) | — | — | **oui** | `findings` |
| `caisse`, `rapprochement` | BANK | aucune logique certaine | — | — | — | NOT_APPLICABLE (jamais simulé OK) |

Lecture de la matrice : un point n'est jamais « OK » si le système ne peut pas l'affirmer. Aucune règle réglementaire n'a été inventée : TVA, stock et immobilisations ne
reposent que sur les invariants déjà implémentés (cohérence, source comptable, double acquisition).

**Écarts constatés par l'audit, traités en Phase E** : (1) la pré-clôture n'expliquait pas l'écart bancaire → les causes du debugger sont jointes à chaque relevé en écart ;
(2) aucun domaine commun entre la pré-clôture et la revue → `ControlDomain` (une seule table de correspondance, aucune nouvelle logique de contrôle) ; (3) statut
`READY_WITH_REVIEW` → renommé `READY_WITH_WARNINGS` ; (4) pas de comptes, pas d'horodatage → `resume` et `controlesExecutesLe`.
**Écarts connus non traités** : le point `lettrage` repose encore sur le lettrage et non sur les affectations ; les avoirs ne sont pas des affectations.

## Statuts et règle de décision

* `NOT_READY` : au moins un point `BLOCKING`.
* `READY_WITH_WARNINGS` : aucun bloquant, au moins un avertissement.
* `READY` : aucun bloquant, aucun avertissement.
* `NOT_APPLICABLE` est conservé : jamais converti en `OK`.
* **Aucun pourcentage** : `resume = {bloquants, avertissements, enOrdre, nonApplicables}` (« 3 bloquants, 8 avertissements, 12 en ordre »).

Chaque point porte : `message` (court), `explanation` (chiffrée), `domaine`, `entityType` / `entityId` / `relatedEntityIds` pour naviguer, `metadata` structurée.
Exemple banque : par relevé, solde comptable, solde du relevé, écart, lignes non pointées, **et les causes** (`BANK_TRANSACTION_UNMATCHED`, `BOOKED_NOT_ON_STATEMENT`,
`OPENING_BALANCE_MISMATCH`, `BANK_DIFFERENCE_UNEXPLAINED`, avec montants).

## Domaines

`ControlDomain` : BANK, CUSTOMER, SUPPLIER, DOCUMENT, VAT, STOCK, FIXED_ASSET, JOURNAL, ACCOUNTING, CLOSING, OTHER. Dérivé du **code du contrôle** (table unique) ; pour les
règlements, du type de tiers (compte 41 / 40). Stocké sur `finding.domaine` (V60) pour filtrer et compter côté base.

## Fraîcheur (contrôles recalculés avant de décider)

* `ClotureExerciceService.cloturer*` appelle `actualiserControles` (exécution complète des détecteurs) **puis** construit la checklist, dans la **même transaction** : une écriture
  problématique apparue après un « READY » est vue au moment de clôturer (test `nouvelleEcritureProblematiqueApresReady_estVueAuMomentDeCloturer`).
* `evaluer()` (consultation) utilise `executerSiPerime` (TTL) et publie `controlesExecutesLe` + `evaluatedAt` : on sait sur quelle exécution la décision s'appuie.
* **Course (race) évaluée** : deux clôtures simultanées du même exercice sont sérialisées par un verrou pessimiste sur la ligne `exercice_comptable`
  (`ExerciceComptableRepository.verrouiller`) ; la seconde voit l'exercice clôturé (409). Il reste une fenêtre étroite entre le calcul de la checklist et le passage de
  l'exercice à `CLOTURE` pendant laquelle une écriture validée par un autre utilisateur ne serait pas vue ; la fermer demanderait de verrouiller aussi la validation des
  écritures — non fait, documenté comme limite.

## Workflow de clôture (E7)

`POST /api/entreprises/{id}/exercices/{exerciceId}/cloturer` avec corps facultatif `{avertissementsConfirmes, motif}`
(`ClotureExerciceService.cloturerAvecConfirmation`) :

1. verrou de l'exercice, vérification d'accès (404 hors tenant) ;
2. contrôles relancés ;
3. checklist évaluée ;
4. **BLOCKING** → `422` avec la liste explicite, audit `CLOTURE_REFUSEE` ;
5. sinon **avertissements** sans confirmation → `409 AVERTISSEMENTS_A_CONFIRMER : …liste…` ; confirmation sans motif → `422` ;
6. confirmation motivée → audit `CLOTURE_AVERTISSEMENTS_ACCEPTES` (auteur, date, motif, liste des avertissements) ;
7. clôture, audit `CLOTURE_TERMINEE`.

L'appel interne historique `cloturer(entrepriseId, exerciceId, principal)` conserve sa sémantique (les avertissements ne bloquent pas) pour les usages internes.

## Événements d'audit (noms français, convention du projet)

| Mission | Action |
|---|---|
| REVIEW_STARTED | `REVUE_OUVERTE` (nouvelle passe : contrôles périmés relancés à l'ouverture de l'espace de revue) |
| CONTROL_RUN | `CONTROLES_EXECUTES` (relance humaine) ; les exécutions automatiques restent des événements système |
| FINDING_OPENED | `FINDING_CREE` (système) |
| FINDING_RESOLVED | `FINDING_RESOLU_AUTO` (système) / `FINDING_RESOLU` (humain) |
| FINDING_DISMISSED | `FINDING_REJETE` (motif obligatoire) |
| INVESTIGATION_OPENED | `INVESTIGATION_OUVERTE` |
| CLOSING_READINESS_CHECKED | `CLOTURE_CONTROLEE` |
| CLOSING_REFUSED | `CLOTURE_REFUSEE` |
| CLOSING_WARNING_ACCEPTED | `CLOTURE_AVERTISSEMENTS_ACCEPTES` |
| EXERCISE_CLOSED | `CLOTURE_TERMINEE` |

## Un constat écarté reste écarté

Politique de la phase B inchangée : `FindingService.declarer` ne rouvre pas un constat `DISMISSED` (`ECARTE_CONSERVE`). Un constat dont la cause disparaît se résout seul
(`SYSTEM`) et reste en base et dans l'audit.

## Performances (10 000 écritures)

Voir `docs/comptable-phaseE-report.md` : la pré-clôture fait 31 requêtes SQL à 10 000 écritures, sans requête par ligne.
