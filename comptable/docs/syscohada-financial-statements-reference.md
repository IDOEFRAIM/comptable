# Référence réglementaire interne — états financiers SYSCOHADA

Document vivant de la phase F. **Règle** : une rubrique, un code de ligne, une formule, un regroupement de comptes ou une note n'entre dans le code que s'il figure ici avec sa source vérifiée. Ce document distingue strictement ce qui est **vérifié** de ce qui **reste à vérifier**.

## 1. Version et statut

| Élément | Valeur |
|---|---|
| Texte | Acte uniforme relatif au droit comptable et à l'information financière (AUDCIF) et système comptable OHADA (SYSCOHADA) |
| Adoption | 26 janvier 2017, Conseil des ministres de l'OHADA, Brazzaville |
| Publication | Journal officiel de l'OHADA, numéro spécial, 15 février 2017 |
| Application | 1er janvier 2018 (comptes individuels) ; 1er janvier 2019 (comptes consolidés, comptes combinés, états en normes IFRS) |
| Identifiant interne proposé du référentiel | `SYSCOHADA_REVISE_AUDCIF_2017` — **provisoire** : à confirmer (l'appellation courante est « SYSCOHADA révisé » ; aucune convention officielle de version n'a été trouvée dans l'Acte) |
| Systèmes de présentation | Système **normal** (implémenté en phase F) ; Système **minimal de trésorerie** (SMT, art. 28) : **hors phase F**, l'architecture ne doit pas l'empêcher |

## 2. Cadre juridique VÉRIFIÉ (texte de l'Acte, p. 22-24 de l'édition, articles 25 à 34)

Source lue : `0.611.01.17-Acte-uniforme-du-26-janvier-2017_Droit-comptable.pdf` (54 pages), chapitre III « Jeu complet d'états financiers annuels ».

* **Art. 25** — À l'exception des Notes annexes, les états financiers annuels sont présentés selon des **modèles dont les éléments sont classés en rubriques successives, elles-mêmes subdivisées en postes** ; ces modèles dépendent du système comptable (normal / minimal) et sont présentés conformément à des **tracés figurant dans le Système comptable OHADA**.
* **Art. 26** — Le **Système normal** comporte **Bilan, Compte de résultat de l'exercice, Tableau des flux de trésorerie et Notes annexes**, dont les dispositions principales sont fixées dans le Système comptable OHADA.
* **Art. 28** — Le **Système minimal de trésorerie** repose sur Bilan, Compte de résultat et Notes annexes dressés à partir de la comptabilité de trésorerie (prise en compte, lorsque significatives, des variations de stocks, de créances et de dettes).
* **Art. 29** — Le Bilan décrit séparément les éléments d'actif et de passif et fait apparaître distinctement les capitaux propres ; le Compte de résultat récapitule en liste les produits et charges et fait apparaître **par cascade** les résultats intermédiaires et, in fine, le bénéfice net ou la perte nette ; le TFT retrace les mouvements entrée / sortie de liquidités de l'exercice.
* **Art. 30** — Le Bilan fait apparaître **à l'actif** : actif immobilisé, actif circulant, trésorerie-actif, écart de conversion-actif ; **au passif** : ressources stables, passif circulant, trésorerie-passif, écart de conversion-passif.
* **Art. 31** — Le Compte de résultat fait apparaître produits et charges **distingués** selon qu'ils concernent les opérations d'exploitation et les opérations financières attachées aux **activités ordinaires**, et les opérations **hors activités ordinaires** ; classement en liste permettant des soldes intermédiaires de gestion en cascade « dans les conditions définies par le Système comptable OHADA ».
* **Art. 32** — Le TFT fait apparaître la **trésorerie nette en début d'exercice**, les flux provenant des **activités opérationnelles**, des **opérations d'investissement**, des **capitaux propres**, des **capitaux étrangers**, et la **trésorerie nette en fin d'exercice**.
* **Art. 33** — Les états financiers sont accompagnés de **Notes annexes organisées par référence croisée** avec l'information liée ; elles apportent des informations complémentaires, descriptions narratives ou décompositions, et les informations sur les éléments non comptabilisés (notamment engagements donnés et reçus) ; toute modification de présentation ou de méthode d'évaluation y est signalée.
* **Art. 34** — Le **bilan d'ouverture** d'un exercice correspond au **bilan de clôture** du précédent ; toute **compensation** non juridiquement fondée entre postes d'actif et de passif (bilan) et entre charges et produits (résultat) est **interdite** ; la présentation est **identique d'un exercice à l'autre** ; **chaque poste comporte le chiffre du poste correspondant de l'exercice précédent** ; si un poste n'est pas comparable, c'est le précédent qui est adapté et l'absence de comparabilité / l'adaptation est signalée dans les Notes annexes.

Conséquences directes pour le moteur (déjà actées) : structure imposée **actif / passif** avec les grandes masses de l'art. 30 ; **N-1 sur chaque ligne** ; **pas de compensation** ; **HAO distinct** ; **TFT avec les cinq blocs de l'art. 32** ; **Notes à références croisées** ; **continuité bilan d'ouverture = bilan de clôture N-1** (contrôle à implémenter).

## 3. Classes de comptes — recoupement secondaire (NON suffisant pour coder)

Extrait « SYSCOHADA : plan de comptes » (africa-web.org, relayé par dgd.gov.gn) : classes 1-5 comptes de bilan (1 ressources durables, 2 actif immobilisé, 3 stocks, 4 tiers, 5 trésorerie), classes 6-7 comptes de gestion, classe 8 opérations hors activités ordinaires (HAO), classe 9 comptabilité analytique / engagements. Comptes cités : 12 report à nouveau, 13 résultat net de l'exercice, 52 banques, 56 banques crédits de trésorerie et d'escompte, 57 caisse, 603 variations des stocks de biens achetés, 64 impôts et taxes, 66 charges de personnel, 67 frais financiers et charges assimilées, 68 dotations aux amortissements, 69 dotations aux provisions, 77 revenus financiers et produits assimilés, 78 transferts de charges, 79 reprises de provisions. **Ces points servent uniquement à détecter les écarts du référentiel de démonstration** (`docs/financial-statements-audit-F1.md` §3).

## 4. Source primaire, statuts et gates (mis à jour après le paquet réglementaire)

Le PDF officiel complet (AUDCIF + Système comptable OHADA, version signée) est archivé : `sources-reglementaires/README.md` (provenance, SHA-256). Le paquet `sources-reglementaires/syscohada-regulatory-unblock-pack.md` (relecture visuelle du PDF) et la transcription des classes 61 à 99 (`syscohada-plan-comptes-officiel.part6-9.txt`) ont été intégrés dans `docs/syscohada-official-matrix.md`, qui est la référence unique : chaque donnée y porte un **statut** (`VERIFIED`/`PARTIAL`/`UNVERIFIED`) et une **provenance** (`DIRECT_PRIMARY`/`DERIVED_FROM_PRIMARY`/`CORROBORATED_ONLY`), jamais confondus.

| Gate | État |
|---|---|
| F2 — modèle de définition versionné | **READY** (implémenté : `etatsfinanciers/definition`, ressources `etats-financiers/SYSCOHADA_REVISE_AUDCIF_2017/1/`) |
| F3 — mapping Compte de résultat | **READY** (implémenté, résolveur unique) |
| F3 — mapping Actif | **PARTIAL_READY** : 13 postes seedés (AJ, AP, AR, AS, BA, BB, BH, BI, BJ brut, BQ, BR, BS brut, BU) ; AD, AE–AI, AK–AN et les totaux restent PARTIAL (voir matrice §C) |
| F3 — mapping Passif | **PARTIAL_READY** (closeout 2026-10-04) : aucune correspondance publiée (page 1068 rognée), mais 22 postes **dérivés** (`DERIVED_FROM_PRIMARY`) des Notes + plan officiel ; 6 réserves `PARTIAL` ; voir `docs/syscohada-regulatory-closeout.md` |
| F4 — balance canonique | **READY** (`AccountingBalanceEngine`, phase F1.5) |
| F5 — Bilan | **PARTIAL_READY** : totaux et contrôle `BZ = DZ` dérivés ; AK, AL et dépréciations « p » bloquent un Bilan complet ; reste `INCOMPLETE` |
| F6 — Compte de résultat | **READY** (structure, mapping et formules `VERIFIED`) |
| F7 — TFT | **PARTIAL** : formules, signes, CAFG (7 termes sur 12 rattachés), variations, trésorerie `VERIFIED` ; exclusions FB-FE et événements FK-FQ à trancher |
| F8 — catalogue Notes | **READY** (46 notes R4, automatisation non décidée) |

**Corrections issues de la relecture directe du PDF (pages imprimées 1068-1069)** : `XA = TA + RA + RB` (« MARGE COMMERCIALE (Somme TA à RB) », `VERIFIED` / `DIRECT_PRIMARY`) ; `BJ` = soldes débiteurs de 185, 42, 43, 44, 45, 46, 47 **sauf 478**, et `478` ne correspond qu'à `BU` ; `AJ` = `22` avec amortissements/dépréciations `282, 292`. Les anciennes transcriptions (`XA = TA + RB`, `sauf 479`, `292, 297`) étaient erronées et ont été retirées. Les anomalies du plan officiel restent conservées telles qu'imprimées (412 dupliqué, 4282–4285 absents, 4793/4794 même intitulé, heading 907 dupliqué en classe 9). Le mapping direct du Passif reste séparément non vérifié.

## 5. Ce qui n'est pas automatisable (provisoire)

Les Notes annexes comportant des informations qualitatives (méthodes, événements postérieurs, engagements hors bilan, parties liées…) seront `MANUAL_REQUIRED` ; les décompositions chiffrées issues du grand livre seront `AUTOMATIC` ou `MIXED`. La matrice définitive se fait après lecture de la liste officielle des notes.

## 6. Gouvernance des définitions (rappel)

* Une définition est **versionnée** (`framework`, `version`, `effectiveFrom`) et **immuable** dès qu'un état final l'a utilisée : nouvelle version plutôt que mise à jour.
* Chaque ligne porte sa **référence à la source officielle** (document, page, numéro de ligne).
* Les données de démonstration ne sont jamais lues comme source réglementaire.

**Closeout du 2026-10-04** : `docs/syscohada-regulatory-closeout.md` (lecture directe des pages 989, 991-1000, 1005-1066, 1068). Erreur relevée dans `bilan-actif.json` : dépréciation de BH = **490** (imprimée), pas 499.
