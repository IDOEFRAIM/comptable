# Phase F1 — Audit du reporting existant (avant toute nouvelle classe)

Date de l'audit : 2026-10-04. Aucune rubrique réglementaire n'a été codée. Cet audit décide ce qui devient le moteur officiel, ce qui est refactoré, ce qui est supprimé.

## 1. Matrice des composants

| Composant | Rôle | Source de données | Hardcode SYSCOHADA ? | Fiable ? | Décision |
|---|---|---|---|---|---|
| `EtatsFinanciersService` (+ `EtatsFinanciersController`, `BilanDto`, `CompteResultatDto`, `SectionFinanciereDto`, `PosteFinancierDto`) | « Bilan » et « Compte de résultat » en 6 + 4 sections libres | `soldeNetAvant` / `mouvementNetPeriode` **par compte** (une requête par compte), classe par `reference.getClasse()` ou 1er chiffre | **Oui** : `66`/`77` = « financier », `41`→actif, `40`→passif, classes 1-5 / 6-7 | **Non** (voir §3) | **À remplacer** par le moteur de définitions ; ne pas le faire évoluer |
| `BalanceService` | Balance générale (ouverture / mouvements / clôture) | par compte : `soldeNetAvant` + `rechercherMouvementsValides` (charge **toutes** les lignes en mémoire) | Non | Exacte, mais **N+1 et mémoire** (3 requêtes par compte, lignes chargées) | **À conserver comme contrat, à refaire en agrégat SQL** : devient le moteur de balance (F4) |
| `GrandLivreService` | Grand livre d'un compte | `soldeNetAvant` + mouvements du compte | Non | Fiable pour **un** compte | **À conserver** (drill-down compte → écritures) ; ajouter la pagination |
| `ExportFecService` | Export FEC d'un exercice | `findByExerciceIdAndStatutFetchAll` (écritures validées) | Non (aucun préfixe de compte) | Fiable (écritures validées uniquement) | **À conserver** tel quel |
| `ClotureExerciceService.genererEcritureDeterminationResultat` | Écriture de détermination du résultat à la clôture | `mouvementNetPeriode` par compte de classe 6/7 + `ClotureRule` (comptes de résultat par **rôle**, pas par numéro) | Classe 6/7 via `determinerClasse` (copie de celle du reporting) | Correct à la clôture ; la règle des comptes de résultat passe par les rôles | **À conserver**, mais la classification 6/7 doit venir du référentiel central |
| `CashPositionService` | Position de trésorerie par canal (banque / caisse / mobile) | `soldeNetJusquau` par compte de classe 5 | **Oui** : `startsWith("5")`, `startsWith("57")` | Correct pour une position, **pas** un TFT | **À conserver** pour la position ; le TFT n'en dérive pas |
| `FinancialSnapshotService`, `CashForecastService` | Indicateurs de gestion / prévision | `soldeTresorerie` (`numero like '5%'`), `mouvementNetClasseSurPeriode('6%'/'7%')` | **Oui** : préfixes `5%`, `6%`, `7%` en JPQL | Indicateurs de gestion, pas des états réglementaires | **Hors périmètre** des états ; mettre les préfixes derrière le référentiel central quand il existe |
| `BalanceAgeeService` | Balance âgée des tiers | lignes non lettrées des comptes lettrables (`lettrable`, pas de préfixe) | Non | Correct, limite connue (lettrage partiel) | **À conserver** |
| `TableauBordService` (`soldeTresorerie`) | Solde de trésorerie du tableau de bord | `numero like '5%'` | **Oui** | Indicateur | idem |
| `AiFinanceQueryService` | Requêtes de l'IA financière | services de lecture ci-dessus | indirect | — | **Aucun calcul réglementaire par l'IA** (règle de la mission) |
| `RapportController`, écran frontend `etats-financiers` | exposition HTTP / écran | DTO ci-dessus | — | — | **À refondre** autour de `FinancialStatementResult` (F12) |
| `ControleActifsFiscalCloture`, détecteurs stock / immobilisations | contrôles de clôture | repositories métier (rôles, registres), pas de préfixe | Non | Fiable | **À conserver** ; fourniront les contrôles inter-états (F10) |

## 2. Recherche exhaustive des hardcodes de numéros de compte

Recherche : `startsWith`, `substring`, `charAt(0)`, `like '<chiffre>%'`, `getClasse()`, `Character.getNumericValue`, littéraux de comptes, dans `main` (Java, JPQL) et dans le frontend.

| Emplacement | Usage | Classification |
|---|---|---|
| `EtatsFinanciersService` l.159 | `startsWith("66") \|\| startsWith("77")` = « financier » | **RÈGLE REPORTING À CENTRALISER** — et **erronée** (§3) |
| `EtatsFinanciersService` l.206-209 | `41` → ACTIF, `40` → PASSIF (nature des sous-comptes de tiers) | **HEURISTIQUE** : remplaçable par l'héritage via `CompteComptable.parent` (les sous-comptes 401001 / 411001 ont **déjà** `parent` = 401 / 411) |
| `EtatsFinanciersService` l.217-219, `ClotureExerciceService` l.200-202 | classe = `reference.getClasse()` sinon 1er chiffre | **RÉFÉRENTIEL LÉGITIME** (la classe est le 1er chiffre du plan OHADA) mais **dupliquée** : une seule fonction d'accès |
| `AccountingAccountMappingService` l.126, `AchatRule` l.301 | classe attendue d'un rôle comptable / nature d'achat | **RÉFÉRENTIEL LÉGITIME** (garde-fou « bon rôle, bonne classe », jamais une rubrique d'état) |
| `AccountPurpose.classesAttendues()` | classe attendue par rôle | **RÉFÉRENTIEL LÉGITIME** |
| `LigneEcritureRepository` (`like '5%'`, `:prefixeClasse`) | trésorerie, classes 6/7 pour indicateurs et prévision | **RÈGLE REPORTING À CENTRALISER** (indicateurs) |
| `CashPositionService` l.62, l.84 | `5…` = trésorerie, `57…` = caisse | **RÈGLE REPORTING À CENTRALISER** |
| `DetecteurPaiements` l.121, `TiersInvestigator` / `PieceInvestigator` (Phase E) | `41…` = client, `40…` = fournisseur, pour ranger un constat dans un domaine | **HEURISTIQUE** (typage d'un tiers) : à remplacer par `Tiers.type` / compte parent ; sans effet sur les états |
| Frontend `banque`, `facturation`, `immobilisations`, `stock`, `rapprochement` | filtres de listes de comptes (`startsWith('5')`, `'7'`, `'3'`, `'60'`…) | **HEURISTIQUE** d'ergonomie (choix d'un compte dans une liste) : à alimenter par les rôles comptables ; pas d'effet sur les états |
| Seeds `V2`, `V33` | libellés, classes, natures de 64 comptes de démonstration | **DONNÉES DE DÉMO** (voir §3) — ne doivent jamais devenir une source réglementaire |

Aucun hardcode de numéro de compte dans `ExportFecService`, `BalanceAgeeService`, les règles comptables (`*Rule`), `AccountingAccountResolver` : les rôles (`AccountPurpose`, `RoleCompteSysteme`) gouvernent déjà tout ce qui n'est pas du reporting.

## 3. Défauts constatés dans le reporting existant

Constats reproduits sur PostgreSQL (test d'audit jetable, sortie ci-dessous) ou lus dans le code.

1. **Le compte de résultat d'un exercice clôturé est vide.** Dossier : une vente de 1 000 000. Avant clôture : résultat net 1 000 000. **Après clôture : résultat net 0** (la somme des mouvements de l'exercice inclut l'écriture de détermination du résultat qui solde les comptes 6/7). Un état « final » issu de ce service serait faux. → l'état officiel devra exclure explicitement l'écriture de clôture ou lire les comptes de gestion avant solde ; décision à prendre et à documenter en F4/F6.
2. **Le Bilan n'est pas un bilan SYSCOHADA.** 6 sections libres, sans rubriques ni codes officiels, sans N-1, sans notes, sans compensation interdite vérifiée. Le « résultat » est injecté comme un faux poste `120/129` pour équilibrer quand l'exercice est ouvert.
3. **Pas de N-1** (l'Acte uniforme impose l'indication du chiffre de l'exercice précédent pour chaque poste, art. 34).
4. **Classes 8 (HAO) ignorées** : le compte de résultat ne produit que « exploitation » et « financier ». L'Acte uniforme (art. 31) distingue activités ordinaires (exploitation + financier) et **hors activités ordinaires**.
5. **Classification financière fausse avec le vrai plan** : `66`/`77` = « financier ». Dans le plan officiel (vérifié, voir §4) **66 = charges de personnel**, **67 = frais financiers**, 77 = revenus financiers. Avec un plan réel, les salaires seraient présentés comme des charges financières et les frais financiers (67) comme des charges d'exploitation.
6. **Le référentiel de démonstration diverge du plan officiel** : `120` / `129` = « résultat net » (officiel : **12 = report à nouveau, 13 = résultat net de l'exercice**), `64` = charges de personnel (officiel : 64 = impôts et taxes), `66` = charges financières (officiel : 67), `68` = « dotations aux amortissements et provisions » (officiel : 68 amortissements, 69 provisions), `6031` = variation de stocks de marchandises (officiel : 603 variations des stocks de biens achetés). Seuls 64 comptes sont présents (classes 1 à 8), contre un plan général de plusieurs centaines de comptes. **V33 le dit lui-même : « référentiel de DEMONSTRATION, pas la nomenclature SYSCOHADA officielle ».**
7. **Une requête par compte** (`soldeNetAvant` dans une boucle sur tous les comptes de l'entreprise) pour le bilan, trois requêtes par compte et toutes les lignes en mémoire pour la balance : non viable à 50 000 écritures (à mesurer en F4, benchmark séparé).
8. **Duplication** de la classification classe/nature entre `EtatsFinanciersService` et `ClotureExerciceService`.
9. **Annulation de facture** (F0.1) : l'annulation change le statut et réintègre le stock **mais ne contre-passe pas l'écriture** (`annulerFacture`) : une facture « ANNULEE » reste dans le grand livre. À traiter explicitement (avoir / contrepassation) avant de produire des états définitifs ; consigné ici, hors périmètre de F0.1 (qui bloque l'annulation avec affectations actives).

Sortie de l'audit jetable :

```
AUDIT-F1 avant cloture : resultatNet=1000000.00 ; bilan actif=1000000.00 passif=1000000.00
AUDIT-F1 apres cloture : resultatNet=0          ; bilan actif=1000000.00 passif=1000000.00
AUDIT-F1 balance : lignes=3 mouvements D=2000000.00 C=2000000.00
```

## 4. Source réglementaire

* **Trouvée et vérifiée** : *Acte uniforme relatif au droit comptable et à l'information financière (AUDCIF) et système comptable OHADA (SYSCOHADA)*, adopté le 26 janvier 2017 à Brazzaville, Journal officiel de l'OHADA, numéro spécial du 15 février 2017 (applicable au 1er janvier 2018 pour les comptes individuels, 1er janvier 2019 pour les comptes consolidés). Texte de l'Acte (54 pages) ; articles 25 à 34 (états financiers) lus et cités ; autres chapitres non exploités : https://www.oneccabf.org/wp-content/uploads/0.611.01.17-Acte-uniforme-du-26-janvier-2017_Droit-comptable.pdf (même fichier sur https://biblio.ohada.org/doc_num.php?explnum_id=2061).
  * **Cadre juridique vérifié** (voir `docs/syscohada-financial-statements-reference.md`) : art. 25-34 (états financiers annuels, Système normal = Bilan + Compte de résultat + Tableau des flux de trésorerie + Notes annexes ; Système minimal de trésorerie ; structure du Bilan, du Compte de résultat, du TFT ; Notes annexes à références croisées ; bilan d'ouverture = bilan de clôture N-1, compensation interdite, comparatif N-1 pour chaque poste).
* **Absente du projet et non encore vérifiée** : la partie « *Système comptable OHADA* » de l'édition spéciale (plan comptable général détaillé, **modèles/tracés** du Bilan, du Compte de résultat, du TFT, liste et modèles des **Notes annexes**, règles de fonctionnement des comptes). Le fichier téléchargé ne contient **que l'Acte** : l'article 25 renvoie les « tracés » au Système comptable OHADA, qui n'y figure pas. Volume officiel : *SYSCOHADA révisé — Plan comptable général OHADA* (catalogue : https://biblio.ohada.org/index.php?lvl=notice_display&id=6415).
* **Source secondaire utilisée seulement pour contrôler les défauts du §3** : extrait « SYSCOHADA : plan de comptes » (49 p., site africa-web.org, relayé par dgd.gov.gn), qui confirme 12 report à nouveau, 13 résultat net, 64 impôts et taxes, 66 charges de personnel, 67 frais financiers, 68 dotations aux amortissements, 69 dotations aux provisions, 77 revenus financiers, 78 transferts de charges, 79 reprises, 52 banques, 56 crédits de trésorerie, 57 caisse. **Une source secondaire ne suffit pas à coder une rubrique.**

## 5. Décisions pour la suite

1. **Remplacer** `EtatsFinanciersService` (ne pas le patcher). Garder la balance et le grand livre comme contrats ; refaire la balance en agrégat SQL.
2. **Pré-requis bloquant avant F2/F3** : obtenir le texte officiel du *Système comptable OHADA* (plan comptable général + modèles des états + notes). Tant qu'il n'est pas dans `docs/` ou vérifié en ligne ligne par ligne, **aucune rubrique, aucun code de ligne, aucune formule, aucune note** n'est codée. Le modèle de données (définitions versionnées, résolveur de mapping, statuts RESOLVED / AMBIGUOUS / UNMAPPED / INVALID) peut être construit sans contenu réglementaire.
3. **Séparer trois choses aujourd'hui mélangées** : *référentiel officiel* (PCG + modèles, versionné, immuable une fois utilisé), *données de démonstration* (V2/V33 : à marquer comme telles, jamais lues comme source réglementaire), *PCE de l'entreprise* (comptes réels, héritant du référentiel par `reference` puis par `parent`).
4. **Mapping** : résolution par `reference` du compte, sinon par la chaîne `parent` (les sous-comptes de tiers l'ont déjà) ; jamais par préfixe de numéro dans un service de reporting ; compte non classable → `UNMAPPED` / `AMBIGUOUS` + Finding.
5. **Compte de résultat d'un exercice clôturé** : décider (F4/F6) comment exclure l'écriture de détermination du résultat ; test dédié.
6. **Migrations** : le projet n'a aucun utilisateur de production ; un nettoyage / rebase des migrations de seed reste possible ; à décider après stabilisation du modèle (pas avant).
