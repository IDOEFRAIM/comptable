# Revue comptable / Inbox (Phase B, lots B3 et B5)

> « Que doit faire le comptable maintenant ? » — et seulement ça.

La revue est une **projection de lecture** sur les Findings actifs : aucune table propre, aucun calcul parallèle.

## 1. API (par entreprise, accès vérifié, 404 si l'entreprise n'est pas celle du tenant)

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/api/entreprises/{e}/revue/synthese` | tuiles cliquables + compteurs par gravité/catégorie + dernière exécution des contrôles |
| GET | `/api/entreprises/{e}/revue/constats` | liste filtrée, triée, paginée (une ligne = une décision) |
| GET | `/api/entreprises/{e}/revue/constats/{id}` | un constat (explicabilité complète) |
| POST | `/api/entreprises/{e}/revue/executer` | relance les contrôles (+ rapprochement des cas certains) |
| POST | `/api/entreprises/{e}/revue/constats/{id}/ecarter` | écarte avec **motif obligatoire** |
| POST | `/api/entreprises/{e}/documents/{d}/lier-ecriture` | rattache une pièce à une écriture (ferme `MISSING_DOCUMENT`) |
| POST | `/api/entreprises/{e}/immobilisations/{i}/lier-acquisition` | rattache un bien à une facture comptabilisée |

### Tuiles

`BLOQUANTS` (gravité BLOCKING), `ERREURS` (ERROR), `ANOMALIES` (catégories ANOMALY, THIRD_PARTY, STOCK, FIXED_ASSET),
`PIÈCES MANQUANTES` (`MISSING_DOCUMENT`), `IMPUTATIONS` (`ACCOUNT_*`), `RAPPROCHEMENTS` (catégorie RECONCILIATION).
Les tuiles de gravité *croisent* les tuiles par nature (une ligne peut compter dans deux tuiles) : un clic = un filtre.
Un seul `GROUP BY` calcule toutes les tuiles.

### Filtres de la liste

`tuile`, `severite`, `categorie`, `statut` (défaut : à traiter ; `RESOLVED` / `DISMISSED` pour l'historique), `entityType`, `code`,
`du`/`au` (date de **création** du constat), `q` (recherche simple : intitulé, explication, id d'objet, code), `page`, `taille` (≤ 100).
Tri : BLOCKING > ERROR > WARNING > INFO, puis détection la plus récente.

### Explicabilité d'une ligne

| Question | Champ |
|---|---|
| Quoi ? | `message`, `code`, `category`, `severity` |
| Quel objet ? | `entityType` + `entityId` (+ `relatedEntityIds` pour la navigation) |
| Pourquoi ? | `explanation` |
| Quelles valeurs ? | `evidence` (montants, soldes, candidats…) |
| Quelle action ? | `suggestedAction` |
| Et ensuite ? | `status`, `resolutionType`, `resolutionComment`, `resolvedAt` |

## 2. Écran Angular « Révision » (`/entreprises/:id/revision`)

* tuiles cliquables (compteur + filtre actif), bandeau rouge « constats bloquants : clôture impossible » ;
* filtres gravité / catégorie / objet / statut / période + recherche (anti-rebond 300 ms) ;
* une carte par décision : gravité, catégorie, objet, date de détection, intitulé, explication, action à faire, détail déplié (valeurs, code, objets liés) ;
* **Traiter** navigue vers la page de l'objet (document → validation, écriture → écritures, ligne de relevé → rapprochement, immobilisation, tiers, article…) ;
* **Écarter** demande un motif ; **Relancer les contrôles** réaligne le dossier sur l'état réel et annonce combien de constats sont nés / résolus tout seuls.

Le tableau de bord existant garde sa liste de priorités ; « Rejeter » y demande désormais un motif. L'Inbox cabinet (`/api/cabinet/inbox`) expose en plus
`code`, `categorie`, `gravite`.

L'écran Immobilisations gagne « Lier à une facture déjà comptabilisée », la source comptable du bien et la case de confirmation anti-double comptage.

## 3. Pré-clôture (B5) : mêmes contrôles, pas un second système

`ClotureReadinessService` consomme :
1. les **Findings actifs** (gravité `controlSeverity` : BLOCKING bloque, ERROR/WARNING avertissent) — le point `findings` liste les ids de constats,
   la répartition par catégorie et la phrase d'explication ;
2. ses **contrôles propres à la clôture** (`ClotureControle` : écritures, documents, trésorerie/tiers, actifs/fiscalité).

Une consultation par un utilisateur réutilise le dernier passage des contrôles s'il est récent ; une **clôture force un passage frais**
(`actualiserControles`) avant de relire la checklist. Un point n'est `OK` que si le système peut l'affirmer.

### Exemple : rapprochement bancaire

Le point `banque` compare, **par relevé**, le solde comptable du compte à la date de fin au solde annoncé par la banque (même calcul que l'écran de
rapprochement) et compte les lignes non pointées :

```
2 ligne(s) de relevé non pointée(s) sur 1 relevé(s).
Compte 521 : solde comptable 12 875 450 contre solde du relevé 12 625 450, écart -250 000 ;
2/2 ligne(s) non pointée(s) (-250 000), l'écart s'explique par ces lignes.
```

`metadata.releves[]` : `releveId`, `compte`, `lignes`, `nonPointees`, `montantNonPointe`, `soldeComptable`, `soldeReleve`, `ecartSolde`,
`ecartExpliqueParNonPointees` ; `relatedEntityIds` = les ids des lignes non pointées ; `entityId` = le premier relevé concerné. Un écart de solde
non nul est un avertissement même si toutes les lignes sont pointées. Le point reste `NOT_APPLICABLE` s'il n'y a aucun relevé (jamais simulé OK).

## 4. Garanties

* **Multi-tenant** : chaque route vérifie l'accès à l'entreprise ; un constat d'une autre entreprise répond 404 ; les requêtes de revue filtrent toujours par `entreprise_id`.
* **Performance** : liste paginée en base (Specification), un `GROUP BY` pour la synthèse, détecteurs par agrégats (jamais ligne par ligne sur les opérations saines),
  pas de recalcul à chaque GET (TTL). Mesure : un passage complet des détecteurs sur un dossier sain = **22 requêtes SQL, ~110 ms, identique pour 5 ou 40
  factures justifiées** (`ControlesPerformanceTest`).
* **Écarter ≠ supprimer** : motif obligatoire, ligne conservée, audit.
