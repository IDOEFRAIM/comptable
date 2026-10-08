# Espace de révision

Phase E. On **étend** l'écran « Révision » existant (`/entreprises/:id/revision`, `RevisionComponent`) : pas de second écran. Réservé au cabinet
(`COLLABORATEUR`, `EXPERT_COMPTABLE`) : un `CLIENT` reçoit 403 sur toutes les routes `/revue/**` (test `PhaseCSecuriteHttpTest`, routes `espace`,
`investigation` et `investigations/**` ajoutées).

## Ce que l'écran répond d'abord

> **Prêt à clôturer : NON** — 2 bloquant(s) · 8 avertissement(s) · 3 à examiner · 5 résolu(s) aujourd'hui

`GET /revue/espace` (`RevueComptableService.espace`) :

* `pret` : `statutCloture == READY` (même vérité que la pré-clôture, `ClotureReadinessService.construireChecklist`) ;
* bloquants / avertissements (ERROR + WARNING) / à examiner (INFO) : compteurs des constats actifs ;
* `resolusAujourdhui` : constats `RESOLVED` depuis minuit (écartés exclus) ;
* `domaines[]` : par domaine, bloquants / avertissements / à examiner / total.

Des comptes, jamais un pourcentage. Sous le bandeau : le résumé de la pré-clôture (« 3 bloquants, 8 avertissements, 12 points en ordre, 4 sans objet »), chaque point
bloquant ou à avertissement avec son message, et un lien vers la clôture de l'exercice.

## Domaines

Banque, Clients, Fournisseurs, Documents, TVA, Stock, Immobilisations, Journal, Comptabilité, Clôture, Autres. Chaque case est un filtre cliquable (`?domaine=`) ;
le filtre agit côté base (`finding.domaine`, index `(entreprise_id, domaine, status)`).

## Drill-down

Clic sur un domaine → liste des constats de ce domaine (tuiles, filtres et recherche existants conservés). Chaque constat porte son domaine, sa gravité, son objet
(libellé, jamais un UUID), son explication, sa valeur et l'action recommandée.

## « Pourquoi ? »

Bouton sur chaque constat → `GET /revue/constats/{id}/investigation` (voir `docs/accounting-investigation.md`). Le panneau affiche : résumé, écart attendu / constaté,
**causes** (libellé lisible de chaque code, nature « À traiter » / « Explication » / « Information », montant, preuves), **chronologie**, **objets liés** (boutons
vers l'écran qui traite l'objet : tiers, écritures, rapprochement, documents, facturation, immobilisations, demandes de pièces), **actions possibles** (libellé + opération
existante à utiliser). L'investigation ne corrige rien.

## Actions

* **Voir le paiement / la facture / la transaction** : boutons des objets liés (navigation vers l'écran concerné).
* **Réaffecter** : l'action suggérée « Répartir le règlement » désigne « Banque › Règlements à répartir » ; l'affectation se fait dans l'écran Banque (service
  `PaymentAllocationService`, invariants inchangés).
* **Écarter avec justification** : bouton « Écarter » existant, motif obligatoire, audit `FINDING_REJETE`, le constat reste consultable et ne revient pas.
* **Relancer les contrôles** : `POST /revue/executer` ; les constats dont la cause a disparu se résolvent seuls (`SYSTEM`), le compteur « résolus aujourd'hui » l'affiche.

## Clôture

L'écran ne clôture pas : il mène à la clôture de l'exercice (« Aller à la clôture ») où le workflow s'applique : refus avec la liste des bloquants, puis, s'il ne reste que
des avertissements, saisie d'un **motif d'acceptation** (tracé : auteur, date, motif) avant la clôture irréversible.

## Mesures produit (calculables, aucun tableau analytique)

* constats ouverts, bloquants, avertissements : `espace` ;
* temps moyen de résolution : `finding.created_at → resolved_at` ;
* % résolu automatiquement : `resolution_type = 'SYSTEM'` / résolus ;
* causes les plus fréquentes : `finding.control_code` (actifs et historique) ;
* temps jusqu'à READY : horodatages `CONTROLES_EXECUTES` et `CLOTURE_CONTROLEE` du journal d'audit.
