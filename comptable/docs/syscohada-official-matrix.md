# Matrice réglementaire officielle SYSCOHADA — gate de la phase F

**Gate F2 : LEVÉ** (voir §J ; closeout complet dans `docs/syscohada-regulatory-closeout.md`). Source primaire : AUDCIF + Système comptable OHADA, JO OHADA, numéro spécial du 15 février 2017 (PDF signé archivé, SHA-256 dans `sources-reglementaires/README.md`). Cette matrice consolide : les transcriptions du plan de comptes (`sources-reglementaires/syscohada-plan-comptes-officiel.part1..5.txt` et `part6-9.txt`), le paquet `syscohada-regulatory-unblock-pack.md` (relecture visuelle du PDF officiel) et son état machine `syscohada-matrix-status-updated.json`. **Aucune donnée n'a été relue dans le PDF pendant la rédaction de ce document.**

**Statut de vérification** : `VERIFIED` (lecture suffisamment certaine dans le PDF officiel signé), `PARTIAL` (source primaire consultée mais incomplète, ambiguë ou non transcrite précisément), `UNVERIFIED` (non établi depuis la source primaire). **Provenance** (jamais confondue avec le statut) : `DIRECT_PRIMARY` (publié tel quel par la source primaire), `DERIVED_FROM_PRIMARY` (déduit de la source primaire par un raisonnement explicite et documenté), `CORROBORATED_ONLY` (recoupement secondaire, sans lecture primaire). Une donnée `DERIVED_FROM_PRIMARY` n'est jamais promue en `DIRECT_PRIMARY` ; une corroboration ne rend jamais une donnée `VERIFIED`. `codingAllowed` = `true` si et seulement si le statut est `VERIFIED`.

## A. Référentiel

| Élément | Valeur | Source | Provenance | Statut | codingAllowed |
|---|---|---|---|---|---|
| Titre | AUDCIF + Système comptable OHADA (SYSCOHADA révisé), version complète signée | PDF archivé, README | DIRECT_PRIMARY | VERIFIED | true |
| Adoption | 26 janvier 2017, Conseil des ministres, Brazzaville | README / Acte | DIRECT_PRIMARY | VERIFIED | true |
| Publication | Journal officiel de l'OHADA, numéro spécial du 15 février 2017 | README | DIRECT_PRIMARY | VERIFIED | true |
| Application comptes individuels | 1er janvier 2018 | README / Acte | DIRECT_PRIMARY | VERIFIED | true |
| Application comptes consolidés / combinés | 1er janvier 2019 | README / Acte | DIRECT_PRIMARY | VERIFIED | true |
| Empreinte du PDF | SHA-256 `d5ecb380984b792c4d586b0efa77931249a4c0c25daf39d3c50b27648dd05300` | `sources-reglementaires/README.md` | DIRECT_PRIMARY | VERIFIED | true |

Identifiant applicatif du référentiel : **`SYSCOHADA_REVISE_AUDCIF_2017`** — identifiant **interne** de l'application, pas un identifiant officiel OHADA.

## B. Plan de comptes

Transcription primaire, **1419 lignes** (code|intitulé|page imprimée), classes 1 à 9 : pages imprimées 216 à 269. Fichiers `part1..5` (classes 1 à 5 et compte 60, p. 216-251) et `part6-9` (classes 61 à 99, p. 252-269). Statut : **VERIFIED**, provenance `DIRECT_PRIMARY` ; les anomalies de la source sont conservées telles qu'imprimées (colonne 4 des fichiers de transcription : `ANOMALIE_SOURCE`).

| Classe | 2 chiffres | 3 chiffres | 4 chiffres |
|---|---|---|---|
| 1 | 10 | 62 | 76 |
| 2 | 17 | 62 | 226 |
| 3 | 9 | 40 | 37 |
| 4 | 17 | 68 | 183 |
| 5 | 10 | 51 | 35 |
| 6 | 19 | 57 | 200 |
| 7 | 8 | 41 | 83 |
| 8 | 9 | 42 | 5 |
| 9 | 10 | 8 | 34 |

### Anomalies de la source primaire (conservées, jamais corrigées)

| Id | Constat (état « tel qu'imprimé ») | Page | Statut | Règle |
|---|---|---|---|---|
| 412 (code dupliqué) | La ligne « Organismes internationaux, Effets à recevoir » est imprimée avec le code **412**, déjà celui du compte parent « Clients, effets à recevoir en portefeuille ». | 238 | VERIFIED_AS_PRINTED | Ne pas inventer `4125`. |
| 4282–4285 | La source passe de `4281` (p. 239) à `4286` (p. 240) : ces comptes sont absents des pages adjacentes. | 239-240 | VERIFIED_ABSENCE | Ne pas inventer 4282–4285. |
| 4793 / 4794 | `4793` et `4794` portent littéralement le même intitulé « Diminution des dettes d'exploitation ». | 243 | VERIFIED_AS_PRINTED | Conserver les deux tels quels. |
| 907 (heading) | Dans la classe 9, un second heading « 907 AUTRES ENGAGEMENTS ACCORDÉS » est imprimé, alors que ses sous-comptes sont `9081`, `9082`, `9083`, `9088`. | 268 | SOURCE_ANOMALY | Conserver l'anomalie documentaire. |

Classe 6 : `66` = CHARGES DE PERSONNEL ; `67` = FRAIS FINANCIERS ET CHARGES ASSIMILÉES (confirmés, p. 252-258). Classes 7, 8, 9 confirmées (p. 259-269). Les doublons de code de transcription (`22`..`29`, `43`..`49` : table des matières de classe puis heading) sont des répétitions de présentation, pas des anomalies.

## C. Bilan — Actif (pages imprimées : modèle 999 ; correspondance Postes/Comptes 1068 ; schéma des masses 995)

Lecture directe des pages ci-dessus (images de 578 × 709 px : les chiffres des lignes AK et AL sont à la limite de la lisibilité). Colonnes du modèle : N brut / amortissements et dépréciations / N net / N-1 net. Chaque ligne est documentée en deux temps : **structure** (code, libellé, note — `VERIFIED`, `DIRECT_PRIMARY`) puis **correspondance** (comptes bruts, amortissements/dépréciations).

### C.1 Structure et correspondance

| Code | Libellé officiel | Note | Comptes bruts | Statut brut | Prov. brut | Amortissements / dépréciations | Statut amort. | Prov. amort. | Commentaire |
|---|---|---|---|---|---|---|---|---|---|
| AD | Immobilisations incorporelles | 3 | — | — | — | — | — | — | Ligne d'en-tête ombrée (note 3) ; total des sous-lignes AE à AH (voir formules). |
| AE | Frais de développement et de prospection | 3 | 211, 2181, 2191 | VERIFIED | DIRECT_PRIMARY | 2811, 2818p, 2911, 2918p, 2919p | PARTIAL | DIRECT_PRIMARY | Brut lisible et cohérent avec le plan. Le suffixe « p » (partie) n'est pas défini par la source : amortissements/dépréciations non codables. |
| AF | Brevets, licences, logiciels et droits similaires | 3 | 212, 213, 214, 2193 | VERIFIED | DIRECT_PRIMARY | 2812, 2813, 2814, 2912, 2913, 2914, 2919p | PARTIAL | DIRECT_PRIMARY | Idem : « 2919p ». |
| AG | Fonds commercial et droit au bail | 3 | 215, 216 | VERIFIED | DIRECT_PRIMARY | 2815, 2816, 2915, 2916 | VERIFIED | DIRECT_PRIMARY | Aucune ambiguïté. |
| AH | Autres immobilisations incorporelles | 3 | 217, 218 (sauf 2181), 2198 | VERIFIED | DIRECT_PRIMARY | 2817, 2818p, 2917, 2918p, 2919p | PARTIAL | DIRECT_PRIMARY | « p » non défini. |
| AI | Immobilisations corporelles | 3 | — | — | — | — | — | — | Ligne d'en-tête ombrée (note 3) ; total AJ à AN. |
| AJ | Terrains (dont placement en net) | 3 | 22 | VERIFIED | DIRECT_PRIMARY | 282, 292 | VERIFIED | DIRECT_PRIMARY | Déjà intégré (corrigé par la relecture de la p. 1068). |
| AK | Bâtiments (dont placement en net) | 3 | 231, 232, 233, 237, 2391 (derniers chiffres à confirmer) | PARTIAL | DIRECT_PRIMARY | 2831, 2832, 2833, 2837, 2931, 2932, 2933, 2937, 2938p/2939p (à confirmer) | PARTIAL | DIRECT_PRIMARY | Chiffres serrés (578 px de large) : rattachement de 2391, 2393 et dernier compte de dépréciation incertains. Non codable. |
| AL | Aménagements, agencements et installations | 3 | 234, 235, 238, 2392, 2393 (à confirmer) | PARTIAL | DIRECT_PRIMARY | 2834, 2835, 2838, 2934, 2935, 2938, 2939p (à confirmer) | PARTIAL | DIRECT_PRIMARY | Idem AK : les comptes d'immobilisations en cours (239x) sont à confirmer. |
| AM | Matériel, mobilier et actifs biologiques | 3 | 24 (sauf 245 et 2495) | VERIFIED | DIRECT_PRIMARY | 284 (sauf 2845), 294 (sauf 2945, 2949 ?), 2949p | PARTIAL | DIRECT_PRIMARY | Brut net. Amortissements : parenthèse d'exclusion de 294 et « p » ambigus. |
| AN | Matériel de transport | 3 | 245, 2495 | VERIFIED | DIRECT_PRIMARY | 2845, 2945, 2949p | PARTIAL | DIRECT_PRIMARY | « p » non défini. |
| AP | Avances et acomptes versés sur immobilisations | 3 | 251, 252 | VERIFIED | DIRECT_PRIMARY | 2951, 2952 | VERIFIED | DIRECT_PRIMARY |  |
| AQ | Immobilisations financières | 4 | — | — | — | — | — | — | Ligne d'en-tête ombrée (note 4) ; total AR + AS. |
| AR | Titres de participation | 4 | 26 | VERIFIED | DIRECT_PRIMARY | 296 | VERIFIED | DIRECT_PRIMARY |  |
| AS | Autres immobilisations financières | 4 | 27 | VERIFIED | DIRECT_PRIMARY | 297 | VERIFIED | DIRECT_PRIMARY |  |
| AZ | TOTAL ACTIF IMMOBILISÉ | — | — | — | — | — | — | — |  |
| BA | Actif circulant HAO | 5 | 485, 488 | VERIFIED | DIRECT_PRIMARY | 498 | VERIFIED | DIRECT_PRIMARY |  |
| BB | Stocks et encours | 6 | 31, 32, 33, 34, 35, 36, 37, 38 | VERIFIED | DIRECT_PRIMARY | 39 | VERIFIED | DIRECT_PRIMARY |  |
| BG | Créances et emplois assimilés | — | — | — | — | — | — | — | Ligne de regroupement sans note ; total BH + BI + BJ. |
| BH | Fournisseurs, avances versées | 17 | 409 | VERIFIED | DIRECT_PRIMARY | 490 | VERIFIED | DIRECT_PRIMARY | **ERREUR DANS LA DÉFINITION F2 ACTUELLE** : la dépréciation imprimée est **490** (cohérent avec le plan : 490 = dépréciations des comptes fournisseurs), pas 499 comme dans le paquet précédent ; `bilan-actif.json` doit être corrigé (voir §I). |
| BI | Clients | 7 | 41 (sauf 419) | VERIFIED | DIRECT_PRIMARY | 491 | VERIFIED | DIRECT_PRIMARY |  |
| BJ | Autres créances | 8 | Soldes débiteurs : 185, 42, 43, 44, 45, 46, 47 (sauf 478) | VERIFIED | DIRECT_PRIMARY | 492, 493, 494, 495, 496, 497 | VERIFIED | DIRECT_PRIMARY | Dépréciations **explicitement imprimées** : plus de « selon les familles » ; la définition F2 actuelle laisse BJ en PARTIAL et peut passer à VERIFIED. |
| BK | TOTAL ACTIF CIRCULANT | — | — | — | — | — | — | — |  |
| BQ | Titres de placement | 9 | 50 | VERIFIED | DIRECT_PRIMARY | 590 | VERIFIED | DIRECT_PRIMARY |  |
| BR | Valeurs à encaisser | 10 | 51 | VERIFIED | DIRECT_PRIMARY | 591 | VERIFIED | DIRECT_PRIMARY |  |
| BS | Banques, chèques postaux, caisse et assimilés | 11 | Soldes débiteurs : 52, 53, 54, 55, 57, 581, 582 | VERIFIED | DIRECT_PRIMARY | 592, 593, 594 | VERIFIED | DIRECT_PRIMARY | Dépréciations **explicitement imprimées** (592, 593, 594). Les soldes **créditeurs** de ces comptes ne sont pas dans BS : ils vont en trésorerie-passif (voir DR). Les comptes 56x relèvent du Passif (DQ, DR). |
| BT | TOTAL TRÉSORERIE-ACTIF | — | — | — | — | — | — | — |  |
| BU | Écart de conversion-Actif | 12 | 478 | VERIFIED | DIRECT_PRIMARY | — | — | — | 478 mappé ici uniquement (exclu de BJ). |
| BZ | TOTAL GÉNÉRAL | — | — | — | — | — | — | — |  |

Structure : 29 lignes, toutes `VERIFIED` / `DIRECT_PRIMARY` (p. 999).

### C.2 Formules de totaux

Le modèle n'imprime **aucune formule** de total pour le Bilan (contrairement au TFT, qui imprime « somme FA à FE »). Les formules ci-dessous sont la **hiérarchie du modèle** (lignes d'en-tête ombrées et lignes TOTAL, art. 30 de l'Acte : actif immobilisé, actif circulant, trésorerie-actif, écart de conversion-actif ; schéma p. 995) : déduction arithmétique déterministe, donc `DERIVED_FROM_PRIMARY`, **jamais** `DIRECT_PRIMARY`.

| Total | Formule | Statut | Provenance | codingAllowed |
|---|---|---|---|---|
| AD | AE + AF + AG + AH | VERIFIED | DERIVED_FROM_PRIMARY | true |
| AI | AJ + AK + AL + AM + AN | VERIFIED | DERIVED_FROM_PRIMARY | true |
| AQ | AR + AS | VERIFIED | DERIVED_FROM_PRIMARY | true |
| AZ | AD + AI + AP + AQ | VERIFIED | DERIVED_FROM_PRIMARY | true |
| BG | BH + BI + BJ | VERIFIED | DERIVED_FROM_PRIMARY | true |
| BK | BA + BB + BG | VERIFIED | DERIVED_FROM_PRIMARY | true |
| BT | BQ + BR + BS | VERIFIED | DERIVED_FROM_PRIMARY | true |
| BZ | AZ + BK + BT + BU | VERIFIED | DERIVED_FROM_PRIMARY | true |

Les formules sont appliquées colonne par colonne (brut, amortissements, net N, net N-1). `AD`, `AI` et `AQ` sont des sous-totaux : leurs colonnes sont ombrées sur le modèle imprimé (aucun montant propre).

## D. Bilan — Passif (page imprimée : modèle 1000 ; schéma 995)

**Résultat de la recherche d'une source primaire** : la page de correspondance (1068) est **rognée à droite** dans cette édition : la colonne Passif y est coupée après les libellés (« CA Capital … DZ TOTAL GÉNÉRAL »), sans colonne de comptes ; la page 1070 est blanche. **Aucune correspondance Passif → comptes n'est donc publiée** (`Cas A` : aucun poste). Les Notes (1019-1066) contiennent en revanche, pour chaque poste, le détail de ses composantes avec les libellés officiels du plan : c'est une source primaire de **dérivation déterministe** (`Cas B`) : libellé officiel du poste + Note + plan de comptes officiel (pages 216-269). Le recoupement externe fourni précédemment n'a été utilisé que pour détecter des écarts ; chaque ligne ci-dessous est justifiée par le texte officiel cité, **sans** reprendre la corroboration comme preuve. Un point où la dérivation n'est pas déterministe reste `PARTIAL` (non codable).

| Code | Libellé officiel | Note | Comptes (dérivés) | Statut | Provenance | codingAllowed | Dérivation / réserve |
|---|---|---|---|---|---|---|---|
| CA | Capital | 13 | 101, 102 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : libellé « Capital » + Note 13 (nom, nombre, montant des parts) + plan (101 capital social, 102 capital par dotation). Les comptes 103 (capital personnel) et 104 (compte de l'exploitant) ne sont pas rattachés par la source : **PARTIAL**, non codés. |
| CB | Apporteurs, capital non appelé (−) | 13 | 109 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : libellé identique à 109 « APPORTEURS, CAPITAL SOUSCRIT, NON APPELÉ » ; signe « (−) » imprimé. |
| CD | Primes liées au capital social | 14 | 105 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 14 « Primes » = prime d'apport, d'émission, de fusion, de conversion, autres = 1051 à 1058. |
| CE | Écarts de réévaluation | 3e (note imprimée « 3e ») | 106 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : libellé identique à 106 « ÉCARTS DE RÉÉVALUATION » (notes 3E/15A). |
| CF | Réserves indisponibles | 14 | 111, 112, 113 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 14 « Total réserves indisponibles » = légales, statutaires, plus-values nettes à long terme, attribution gratuite d'actions, autres réserves réglementées = 111, 112 et 113 (1131, 1132, 1138). |
| CG | Réserves libres | 14 | 118 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 14 « Réserves libres » = 118 (1181 facultatives, 1188 diverses). |
| CH | Report à nouveau (+ ou −) | 14 | 12 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : libellé identique à 12 « REPORT À NOUVEAU » (121 créditeur, 129 débiteur). |
| CJ | Résultat net de l'exercice (bénéfice + ou perte −) | — | −(solde net des comptes de classe 13 + solde net des comptes de classes 6, 7 et 8), vue LEGAL_LEDGER | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : identité comptable (avant clôture, le résultat est dans les classes 6-8 ; après clôture, dans 13 ; la clôture ne solde que 6 et 7, la classe 8 reste ouverte). Égal à XI du compte de résultat avant clôture. À faire valider par l'utilisateur (aucun libellé de compte ne l'impose). |
| CL | Subventions d'investissement | 15 | 14 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : libellé identique à 14 « SUBVENTIONS D'INVESTISSEMENT » ; Note 15A (141 équipement, 148 autres). |
| CM | Provisions réglementées | 15 | 15 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : libellé identique à 15 « PROVISIONS RÉGLEMENTÉES ET FONDS ASSIMILÉS » ; Note 15A (151 à 158). |
| DA | Emprunts et dettes financières diverses | 16 | 16, 181, 182, 183, 184 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 16A (emprunts obligataires, établissements de crédit, avances de l'État, avances et comptes courants bloqués, dépôts, intérêts courus, avances conditionnées, autres emprunts, dettes liées à des participations, comptes permanents bloqués) = 16, 181 à 184. Les comptes de liaison 185 à 188 sont rattachés à DM/BJ (Notes 8 et 19). |
| DB | Dettes de location acquisition | 16 | 17 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 16A (crédit-bail immobilier, mobilier, location-vente, intérêts courus, autres) = 172 à 178 ; libellé de 17. |
| DC | Provisions pour risques et charges | 16 | 19 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 16A « Provisions pour risques et charges » (litiges, garanties, marchés, change, impôts, pensions, restructuration, amendes, propre assureur, démantèlement, droits, autres) = 191 à 198. |
| DH | Dettes circulantes HAO | 5 | 481, 482, 484 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 5 « Dettes circulantes HAO » = fournisseurs d'investissements, effets à payer, versements restant à effectuer sur titres, autres dettes HAO = 481, 482, 484. |
| DI | Clients, avances reçues | 7 | 419 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 7 « Clients créditeurs » (avances reçues hors groupe, groupe, autres clients créditeurs) = 419. |
| DJ | Fournisseurs d'exploitation | 17 | 401, 402, 408 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 17 (dettes en compte, effets à payer, groupe, factures non parvenues) = 401, 402, 408. Le compte 404 (fournisseurs d'acquisitions courantes d'immobilisations) n'est pas listé par la Note 17 : **PARTIAL**, non codé (compte non mappé → Finding). |
| DK | Dettes fiscales et sociales | 18 | Soldes créditeurs : 42, 43, 44 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 18 (dettes sociales : personnel, organismes sociaux ; dettes fiscales : État) ; règle de côté symétrique de BJ (« soldes débiteurs » de 42 à 47 à l'Actif). |
| DM | Autres dettes | 19 | Soldes créditeurs : 185, 45, 46, 47 (sauf 479) | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 19 (organismes internationaux, associés, créditeurs divers, comptes de liaison) ; règle de côté symétrique de BJ. Les comptes de liaison 186, 187, 188 (cités par les Notes 8 et 19) : **PARTIAL**, non codés. |
| DN | Provisions pour risques à court terme | 19 | 499 (sauf 4998) | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 19 « Provisions pour risques à court terme (voir note 28) » = 499. Le compte **4998** (provisions HAO) : la Note 5 ne liste aucune provision parmi les dettes HAO, ce qui le placerait ici ; le recoupement externe le plaçait en DH : **PARTIAL**, non codé. Le compte 599 : **PARTIAL**, non codé. |
| DQ | Banques, crédits d'escompte | 20 | 564, 565 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 20 (escomptes de crédit de campagne, escomptes de crédit ordinaires) = 564, 565. |
| DR | Banques, établissements financiers et crédits de trésorerie | 20 | Soldes créditeurs : 52, 53 ; 561 ; 566 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 20 (banques locales, autres États région, autres banques, intérêts courus, crédit de trésorerie) et NB p. 1048 « Banques et intérêts courus figure dans cette rubrique si le compte principal attaché est créditeur » ; règle de côté symétrique de BS. |
| DV | Écart de conversion-Passif | 12 | 479 | VERIFIED | DERIVED_FROM_PRIMARY | true | Dérivé : Note 12 + libellé identique à 479 « ÉCARTS DE CONVERSION - PASSIF ». |

Structure (code, libellé, note) : 28 lignes `VERIFIED` / `DIRECT_PRIMARY` (p. 1000). Mapping : 22 lignes `VERIFIED` / `DERIVED_FROM_PRIMARY` (aucune en `DIRECT_PRIMARY`).

**Points `PARTIAL` (non codables ; les comptes concernés ressortent en `UNMAPPED_ACCOUNT`, jamais rangés par défaut)** : 103 et 104 (CA) ; 404 (DJ) ; 186, 187, 188 (DM/BJ) ; 4998 et 599 (DN/DH) ; les soldes créditeurs de 54, 55, 57, 581, 582 et les soldes débiteurs de 561-566 (hors Actif/Passif explicitement listés).

### D.2 Formules de totaux et contrôle

| Total | Formule | Statut | Provenance | codingAllowed |
|---|---|---|---|---|
| CP | CA − |CB| + CD + CE + CF + CG + CH + CJ + CL + CM | VERIFIED | DERIVED_FROM_PRIMARY | true |
| DD | DA + DB + DC | VERIFIED | DERIVED_FROM_PRIMARY | true |
| DF | CP + DD | VERIFIED | DERIVED_FROM_PRIMARY | true |
| DP | DH + DI + DJ + DK + DM + DN | VERIFIED | DERIVED_FROM_PRIMARY | true |
| DT | DQ + DR | VERIFIED | DERIVED_FROM_PRIMARY | true |
| DZ | DF + DP + DT + DV | VERIFIED | DERIVED_FROM_PRIMARY | true |

Contrôle de cohérence du Bilan : **`BZ = DZ`** — `VERIFIED` / `DERIVED_FROM_PRIMARY` (les deux lignes « TOTAL GÉNÉRAL » du même modèle ; identité de la partie double ; la tolérance est nulle, tous les montants étant des `BigDecimal`). Colonnes du Passif : N net / N-1 net.

## E. Compte de résultat — mapping et formules VERIFIED

Source primaire : chapitre 4 (modèle, p. 1001-1004) ; correspondance Postes/Comptes imprimée **p. 1069**. Structure, mapping et formules : `VERIFIED`, `DIRECT_PRIMARY` (la précédente incertitude sur **TG** est levée : **TG = +71**). Convention : `+` = produit, `-` = charge ; le montant d'une ligne est la contribution algébrique au résultat (crédit − débit des comptes concernés).

| Code | Comptes (signe) | Page | Provenance | Statut | codingAllowed |
|---|---|---|---|---|---|
| TA | +701 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RA | -601 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RB | -/+6031 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TB | +702, 703, 704 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TC | +705, 706 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TD | +707 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TE | -/+73 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TF | +72 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TG | +71 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TH | +75 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TI | +781 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RC | -602 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RD | -/+6032 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RE | -604, -605, -608 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RF | -/+6033 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RG | -61 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RH | -62, -63 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RI | -64 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RJ | -65 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RK | -66 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TJ | +791, +798, +799 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RL | -681, -691 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TK | +77 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TL | +797 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TM | +787 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RM | -67 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RN | -697 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TN | +82 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| TO | +84, +86, +88 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RO | -81 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RP | -83, -85 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RQ | -87 | 1069 | DIRECT_PRIMARY | VERIFIED | true |
| RS | -89 | 1069 | DIRECT_PRIMARY | VERIFIED | true |

### Formules (soldes intermédiaires en cascade, art. 31)

| Code | Libellé | Formule (paquet) | Provenance | Statut | codingAllowed |
|---|---|---|---|---|---|
| XA | Marge commerciale | TA + RA + RB  *(p. 1069 : « MARGE COMMERCIALE (Somme TA à RB) »)* | DIRECT_PRIMARY | VERIFIED | true |
| XB | Chiffre d'affaires | TA + TB + TC + TD | DIRECT_PRIMARY | VERIFIED | true |
| XC | Valeur ajoutée | (XB + RA + RB) + somme des lignes TE à RJ, avec leur signe | DIRECT_PRIMARY | VERIFIED | true |
| XD | Excédent brut d'exploitation | XC + RK | DIRECT_PRIMARY | VERIFIED | true |
| XE | Résultat d'exploitation | XD + TJ + RL | DIRECT_PRIMARY | VERIFIED | true |
| XF | Résultat financier | somme TK à RN | DIRECT_PRIMARY | VERIFIED | true |
| XG | Résultat des activités ordinaires | XE + XF | DIRECT_PRIMARY | VERIFIED | true |
| XH | Résultat hors activités ordinaires | somme TN à RP | DIRECT_PRIMARY | VERIFIED | true |
| XI | Résultat net | XG + XH + RQ + RS | DIRECT_PRIMARY | VERIFIED | true |

## F. Tableau des flux de trésorerie (pages imprimées : règles 1006-1014 ; modèle 1015)

Lecture directe de la méthode officielle. **Il n'existe aucune table comptes → lignes** du TFT dans le document : le calcul est une **reconstitution par la méthode officielle** à partir des soldes de bilan (N et N-1), du compte de résultat et d'événements de capitaux/financement. Les **formules et les signes sont publiés** ; le **rattachement de certains termes à des comptes** ne l'est pas : il est détaillé en §F.2 et reste `PARTIAL` là où il n'est pas déterministe.

### F.1 Lignes, formules et conventions

| Code | Libellé | Formule / méthode | Statut | Provenance | codingAllowed (formule) | Page / commentaire |
|---|---|---|---|---|---|---|
| ZA | Trésorerie nette au 1er janvier (A) | Trésorerie-actif N-1 − Trésorerie-passif N-1 ; trésorerie-actif = BT (BQ + BR + BS, nets) ; trésorerie-passif = DT (DQ + DR) | VERIFIED | DIRECT_PRIMARY | true | p. 1015 (libellé imprimé), p. 995 (composition) ; opérandes du Passif : `DERIVED`. |
| FA | Capacité d'autofinancement globale (CAFG) | CAFG = EBE + 654 − 754 (= CAF d'exploitation) + revenus financiers + gains de change + transferts de charges financières + produits HAO + transferts de charges HAO − frais financiers − pertes de change − participation − impôt sur les résultats | VERIFIED | DIRECT_PRIMARY | true | p. 1007 et p. 1063 : **la liste des termes et leurs signes sont explicites** ; l'EBE est XD du compte de résultat. Le rattachement de chaque terme à des comptes est détaillé ci-dessous. |
| FB | Actif circulant HAO | −Variation de l'actif circulant HAO (N − N-1), hors dettes et créances sur cessions/acquisitions d'immobilisations, financement, écarts de conversion d'exploitation, apporteurs, titres de placement non libérés | VERIFIED | DIRECT_PRIMARY | true | p. 1009-1010 (formule et exclusions (1)). Convention (a) : si les créances/stocks augmentent la variation est négative ; (b) : si les dettes augmentent elle est positive. |
| FC | Variation des stocks | −Variation des stocks et en-cours (N − N-1) = −(BB net N − BB net N-1) | VERIFIED | DIRECT_PRIMARY | true | p. 1009 ; stocks nets de dépréciations. |
| FD | Variation des créances | −Variation des créances et emplois assimilés et des intérêts courus des immobilisations financières (N − N-1), hors créances liées aux investissements/financements/écarts de conversion | VERIFIED | DIRECT_PRIMARY | true | p. 1009-1010 ; opérande BG net. |
| FE | Variation du passif circulant | +Variation du passif circulant et des intérêts des emprunts et dettes financières courus (N − N-1), mêmes exclusions | VERIFIED | DIRECT_PRIMARY | true | p. 1009-1010 ; opérande DP (sans trésorerie-passif). |
| ZB | Flux de trésorerie provenant des activités opérationnelles (B) | somme FA à FE | VERIFIED | DIRECT_PRIMARY | true | p. 1015 : « somme FA à FE ». |
| FF | Décaissements liés aux acquisitions d'immobilisations incorporelles | Acquisitions de l'exercice − variation des dettes sur acquisitions d'immobilisations ; acquisitions = reconstituées (modèle p. 1012) | VERIFIED | DIRECT_PRIMARY | true | p. 1011 (note (1)) et p. 1012 (relation « par exemple » donnée pour les immobilisations corporelles) ; la relation pour les incorporelles et financières est l'analogue : `DERIVED`. |
| FG | Décaissements liés aux acquisitions d'immobilisations corporelles | Acquisitions = variation des immobilisations corporelles nettes (N − N-1) + dotations aux amortissements N + dotations aux dépréciations N + valeur comptable nette des immobilisations corporelles cédées N + avances et acomptes payés non livrés − écart et provision spéciale de réévaluation N − coûts de démantèlement imputés aux immobilisations N − avances et acomptes sur immobilisations acquises payés lors d'exercices précédents ; puis − variation des dettes sur acquisitions | VERIFIED | DIRECT_PRIMARY | true | p. 1012 : relation imprimée terme à terme. |
| FH | Décaissements liés aux acquisitions d'immobilisations financières | Acquisitions de titres, dépôts et cautionnements, prêts et avances à long terme (p. 1011) − variation des dettes sur acquisitions | VERIFIED | DERIVED_FROM_PRIMARY | true | Nature des flux : p. 1011 (liste explicite) ; reconstitution analogue à FG : `DERIVED`. |
| FI | Encaissements liés aux cessions d'immobilisations incorporelles et corporelles | Prix de cession des immobilisations − variation des créances sur cessions d'immobilisations (N − N-1) | VERIFIED | DIRECT_PRIMARY | true | p. 1012 note (2). |
| FJ | Encaissements liés aux cessions d'immobilisations financières | Cessions et remboursements d'immobilisations financières (p. 1011) − variation des créances sur cessions | VERIFIED | DERIVED_FROM_PRIMARY | true | Analogue à FI : `DERIVED`. |
| ZC | Flux de trésorerie provenant des activités d'investissement (C) | somme FF à FJ | VERIFIED | DIRECT_PRIMARY | true | p. 1015. |
| FK | Augmentations de capital par apports nouveaux | Entrées de trésorerie provenant des augmentations de capital **en numéraire** (p. 1013) ; **hors** conversion de dettes en capital et incorporation de réserves (p. 1014) | VERIFIED | DIRECT_PRIMARY | true | Événement source : augmentation de capital en numéraire. |
| FL | Subventions d'investissement reçues | Entrées de trésorerie provenant des subventions d'investissement (p. 1013) ; la variation de créance sur subvention est exclue des variations opérationnelles | VERIFIED | DIRECT_PRIMARY | true |  |
| FM | Prélèvements sur le capital | Sorties de trésorerie (signe −) ; événement : réduction/remboursement de capital | VERIFIED | DIRECT_PRIMARY | true | p. 1014 (tableau de synthèse). |
| FN | Dividendes versés | Sorties de trésorerie liées aux distributions de dividendes (signe −) | VERIFIED | DIRECT_PRIMARY | true | p. 1013. |
| ZD | Flux de trésorerie provenant des capitaux propres (D) | somme FK à FN | VERIFIED | DIRECT_PRIMARY | true | p. 1015. |
| FO | Emprunts | Entrées de trésorerie provenant de nouveaux emprunts | VERIFIED | DIRECT_PRIMARY | true | p. 1013. |
| FP | Autres dettes financières | Entrées de trésorerie provenant des autres dettes financières | VERIFIED | DIRECT_PRIMARY | true | p. 1014. |
| FQ | Remboursements des emprunts et autres dettes financières | Sorties de trésorerie liées aux remboursements d'emprunts **et au remboursement de la dette de location-acquisition (compte 17)** ; seule la quote-part d'intérêts (672) reste opérationnelle | VERIFIED | DIRECT_PRIMARY | true | p. 1013 (remarque). |
| ZE | Flux de trésorerie provenant des capitaux étrangers (E) | somme FO à FQ | VERIFIED | DIRECT_PRIMARY | true | p. 1015. |
| ZF | Flux de trésorerie provenant des activités de financement (F) | D + E = ZD + ZE | VERIFIED | DIRECT_PRIMARY | true | p. 1015. |
| ZG | Variation de la trésorerie nette de la période (G) | B + C + F = ZB + ZC + ZF | VERIFIED | DIRECT_PRIMARY | true | p. 1015 et p. 1006 (« F = B + C + D »). |
| ZH | Trésorerie nette au 31 décembre (H) | G + A = ZG + ZA ; contrôle : trésorerie-actif N − trésorerie-passif N | VERIFIED | DIRECT_PRIMARY | true | p. 1015 « (G+A) » et « Contrôle ». |

### F.2 Rattachement des termes de la CAFG aux comptes (p. 1007, 1063 ; plan p. 216-269)

| Terme | Comptes | Statut | Provenance | codingAllowed | Raison |
|---|---|---|---|---|---|
| EBE | XD du compte de résultat | VERIFIED | DIRECT_PRIMARY | true | Soldes intermédiaires déjà vérifiés. |
| + Valeurs comptables des cessions courantes d'immobilisations | 654 | VERIFIED | DIRECT_PRIMARY | true | Compte imprimé dans le terme (p. 1007). |
| − Produits des cessions courantes d'immobilisations | 754 | VERIFIED | DIRECT_PRIMARY | true | Compte imprimé dans le terme. |
| + Transferts de charges financières | 787 | VERIFIED | DERIVED_FROM_PRIMARY | true | Libellé identique à 787 (« TRANSFERTS DE CHARGES FINANCIÈRES ») et à la ligne TM du compte de résultat. |
| + Transferts de charges HAO | 848 | VERIFIED | DERIVED_FROM_PRIMARY | true | Libellé identique à 848 (« TRANSFERTS DE CHARGES H.A.O »). |
| − Participation | 87 | VERIFIED | DERIVED_FROM_PRIMARY | true | Libellé « participation des travailleurs » = ligne RQ (87). |
| − Impôt sur les résultats | 89 | VERIFIED | DERIVED_FROM_PRIMARY | true | Ligne RS (89). |
| + Gains de change | 776 (gains de change financiers) — les gains de change commerciaux (756) sont déjà dans l’EBE | PARTIAL | DERIVED_FROM_PRIMARY | false | Plausible mais la source ne dit pas que 776 est le seul compte du terme. |
| − Pertes de change | 676 (pertes de change financières) — les pertes de change commerciales (656) sont déjà dans l’EBE | PARTIAL | DERIVED_FROM_PRIMARY | false | Idem. |
| + Revenus financiers | 77 hors 776 (déjà compté) et hors 779 (reprise non cash) | PARTIAL | DERIVED_FROM_PRIMARY | false | La Note 29 range 777, 778 et 779 sous « revenus financiers » : leur traitement (cash ou non) n'est pas explicite. |
| − Frais financiers | 67 hors 676 ; la dotation 697 est non cash donc exclue | PARTIAL | DERIVED_FROM_PRIMARY | false | Le compte 678x/679x et les charges non décaissables ne sont pas distingués par la source. |
| + Produits HAO | 84 hors 848 (transferts) et 849 (reprises) ; 88 (subventions d'équilibre) ? | PARTIAL | DERIVED_FROM_PRIMARY | false | Les comptes encaissables du HAO ne sont pas énumérés ; 82 (cessions) et 86 (reprises) relèvent d'autres lignes. |

**Exclusion des éléments non cash (explicite)** : dotations aux amortissements, dépréciations et provisions (681, 691, 697, 85 : hors CAFG puisque le point de départ est l'EBE, qui les exclut), reprises (791, 797, 798, 799, 86), conversion d'instruments sans trésorerie, conversion de dettes en capitaux propres, incorporation de réserves au capital, acquisitions en location-acquisition, acquisition d'entité par émission d'actions (p. 1011, 1014) : **aucun flux**. Les variations de créances/dettes rattachées aux investissements et au financement sont **exclues** de FB à FE et traitées en FF à FJ et FK à FQ (p. 1009-1010).

### F.3 Trésorerie, invariants

Composition (p. 995, 1015) : trésorerie-actif = **BT** = BQ + BR + BS ; trésorerie-passif = **DT** = DQ + DR ; trésorerie nette = BT − DT. Invariants : `ZB`, `ZC`, `ZD`, `ZE`, `ZF`, `ZG` par les sommes imprimées ; `ZA` = trésorerie nette de clôture de l'exercice précédent réel ; `ZH` = trésorerie-actif N − trésorerie-passif N ; **`ZA + ZG = ZH`** (« G + A », p. 1015) sans ajustement, sous réserve de la complétude des flux (le contrôle `CASH_FLOW_MISMATCH` le vérifie). Tous ces invariants dépendent du Passif (DT) : ils deviennent calculables avec le mapping dérivé de la §D.

## G. Notes annexes — catalogue R4 (p. 989) et matrice d'automatisation (pages 1019 à 1066)

**Fiche R4 (p. 989), archivée comme source primaire du catalogue** : 46 notes (1, 2, 3A à 3F, 4 à 15B, 16A, 16B, 16B bis, 16C, 17 à 30, 27A/27B, 31 à 36) ; chaque numéro et chaque intitulé ont été relus directement sur la page 989 **et** sur le sommaire p. 1019 (identiques) ; aucun numéro ne provient d'une source secondaire. Colonnes **A** (applicable) / **N/A** (non applicable) : « les notes non documentées ne doivent pas être jointes aux états financiers » (p. 989, p. 1018) ; exemple imprimé : entité sans stocks → case N/A de la ligne Note 6. **Note 35 : obligatoire seulement au-delà de 250 salariés** (p. 1064).

Colonnes ci-dessous : `Statut` = lisibilité du modèle imprimé (V = lu en entier ; P = colonnes rognées sur la page) ; `automationType` = **proposition issue de l'audit documentaire** (données requises par le modèle imprimé confrontées aux données structurées existant dans le produit) : elle n'est pas une donnée réglementaire et reste à valider avant codage. Règle appliquée : `AUTOMATIC` seulement si le tableau est entièrement calculable à partir des soldes/mouvements du grand livre et que les commentaires imprimés sont conditionnels (« commenter toute variation significative ») ; `MIXED` si une donnée structurée manque (échéances, groupe, filiales, contrats, devises…) ou si le modèle exige un texte précis ; `MANUAL_REQUIRED` si le produit ne détient aucune donnée.

| Note | Titre officiel | Page | Statut modèle | Applicable | Données requises par le modèle | Source dans Comptable | automationType (proposé) | Remarque |
|---|---|---|---|---|---|---|---|---|
| 1 | Dettes garanties par des sûretés réelles | 1020 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Sûretés réelles (hypothèques, nantissements, gages) par dette ; engagements financiers donnés/reçus | Aucune donnée structurée de sûretés ni d'engagements hors bilan | MANUAL_REQUIRED | Comptes 90 à 99 (classe 9, facultative) non exploités par le produit |
| 2 | Informations obligatoires | 1021 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Déclaration de conformité, règles et méthodes, dérogations, informations complémentaires (texte libre) | Aucune | MANUAL_REQUIRED | Texte libre : quatre blocs A à D |
| 3A | Immobilisation brute | 1022 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Montant brut à l'ouverture, acquisitions/apports/créations, virements, réévaluation, cessions/scissions/hors service… par rubrique | Module Immobilisations (registre, acquisitions, sorties) + soldes 21-27 | MIXED | Colonnes de droite rognées sur la page : structure complète des colonnes `PARTIAL` |
| 3B | Biens pris en location acquisition | 1023 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Nature du contrat (I, M, A), montants bruts et mouvements ; bailleur, durée | Comptes 2x6 et 17 ; contrats absents | MIXED | Colonnes rognées |
| 3C | Immobilisations : amortissements | 1024 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Amortissements cumulés à l'ouverture, dotations, diminutions ; modes et taux | Module Immobilisations (plan d'amortissement, dotations) + soldes 28 | MIXED | Colonnes rognées ; titre interne imprimé « NOTE 3B » (anomalie de la source) |
| 3D | Immobilisations : plus-values et moins-values de cession | 1025 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Brut, amortissements pratiqués, VNC par rubrique cédée ; justification, dates d'acquisition et de sortie | Sorties d'immobilisations (partiellement sans écriture : à auditer) + comptes 81, 82 | MIXED | — |
| 3E | Informations sur les réévaluations effectuées par l'entité | 1026 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Nature et date, éléments réévalués, coûts historiques, amortissements supplémentaires, méthode, traitement fiscal | Aucune | MANUAL_REQUIRED | — |
| 3F | Tableau d'étalement des charges immobilisées | 1032 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Montant à étaler au 1er janvier 2018, durée, comptes et montants par exercice 2018 à 2022 | Aucune | MANUAL_REQUIRED | Anomalie de la source : la page 1032 imprime « NOTE 8A » alors que R4 (p. 989) et le sommaire (p. 1019) la nomment « 3F » ; code retenu : 3F (R4) |
| 4 | Immobilisations financières | 1027 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Titres de participation, prêts et créances, prêts au personnel, créances sur l'État, titres immobilisés, dépôts, intérêts courus ; N, N-1, échéances ; liste des filiales | Soldes 26-27 ; échéances et filiales absentes | MIXED | — |
| 5 | Actif circulant HAO (et dettes circulantes HAO) | 1028 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Créances sur cessions, autres créances HAO, dépréciations ; fournisseurs d'investissements, effets, versements restant, autres dettes HAO | Soldes 481, 482, 484, 485, 488, 498 | AUTOMATIC | Commentaires conditionnels (variation significative, date de cession) |
| 6 | Stocks et en-cours | 1029 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Marchandises, matières premières, autres approvisionnements, produits en cours, services en cours, produits finis, intermédiaires, en route ; dépréciations ; date d'inventaire | Module Stock (CUMP, inventaire) + soldes 31-39 | MIXED | Date de prise d'inventaire et méthodes à indiquer |
| 7 | Clients, produits à recevoir (et clients créditeurs) | 1030 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Clients hors/avec réserve de propriété, effets, groupe, cessions, escomptés, litigieux, produits à recevoir, dépréciations ; avances reçues ; échéances | Tiers, factures, balance âgée, soldes 41 | MIXED | Créances du groupe (nom, % de titres) hors modèle de données |
| 8 | Autres créances | 1031 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Personnel, organismes sociaux, État, organismes internationaux, apporteurs, compte transitoire d'ajustement spécial, débiteurs divers, comptes de liaison ; échéances | Soldes 42-47, 185-188 | MIXED | — |
| 9 | Titres de placement | 1033 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Titres du trésor, actions, obligations, bons, titres hors région, intérêts courus, autres ; dépréciations | Soldes 50, 590 ; cours de bourse absents | MIXED | — |
| 10 | Valeurs à encaisser | 1034 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Effets, chèques, cartes de crédit, autres ; dépréciations | Soldes 51, 591 | AUTOMATIC | — |
| 11 | Disponibilités (banques, chèques postaux et chèques) | 1035 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Banques locales, autres États région, DAT, autres banques, intérêts courus, chèques postaux, établissements financiers, instruments, caisse, monnaie électronique, régies | Banque (comptes, rapprochement), caisse, soldes 52-58 ; dates de rapprochement et d'inventaire de caisse | MIXED | Titre imprimé « BANQUES, CHÈQUES POSTAUX ET CHÈQUES » vs « DISPONIBILITÉS » (anomalie de la source) |
| 12 | Écarts de conversion (et transferts de charges) | 1036 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Devises, montant, cours, variation ; transferts de charges d'exploitation et financières | Soldes 478, 479, 781, 787 ; devises et cours absents | MIXED | — |
| 13 | Capital : valeur nominale des actions ou parts | 1037 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Nom, nationalité, nature, nombre, montant, cessions par associé ; capital non appelé | Aucune donnée d'actionnariat ; soldes 10 | MIXED | — |
| 14 | Primes et réserves | 1038 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Primes, réserves légales, statutaires, plus-values, attribution gratuite, autres, libres, report à nouveau ; dates des AGE/AGO | Soldes 105, 11, 12 | MIXED | Dates d'assemblées à indiquer |
| 15A | Subventions et provisions réglementées | 1039 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Subventions par financeur, provisions réglementées par nature ; régime fiscal, échéances | Soldes 14, 15 | MIXED | — |
| 15B | Autres fonds propres | 1040 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Titres participatifs, avances conditionnées, TSDI, ORA, autres | Comptes 1671-1674, 168x | MIXED | — |
| 16A | Dettes financières et ressources assimilées | 1041 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Emprunts (obligataires, établissements de crédit, État…), location acquisition, provisions pour risques et charges ; échéances | Soldes 16-19 | MIXED | Conditions d'emprunt à documenter |
| 16B | Engagements de retraite et avantages assimilés (méthode actuarielle) | 1042 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Hypothèses actuarielles, variation de l'obligation, sensibilité | Aucune | MANUAL_REQUIRED | — |
| 16B bis | Engagements de retraite et avantages assimilés (régimes financés) | 1043 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Actif/passif net des régimes financés, valeur des actifs du régime | Aucune | MANUAL_REQUIRED | Titre imprimé identique à 16B (la page ne précise pas « méthode actuarielle ») |
| 16C | Actifs et passifs éventuels | 1044 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Actifs et passifs éventuels (litiges, autres) | Aucune | MANUAL_REQUIRED | — |
| 17 | Fournisseurs d'exploitation | 1045 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Fournisseurs en compte, effets, groupe, FNP ; avances et acomptes ; échéances | Tiers, factures, soldes 40 | MIXED | — |
| 18 | Dettes fiscales et sociales | 1046 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Dettes sociales (personnel, organismes), dettes fiscales (impôt sur bénéfices, impôts, TVA, retenues, autres) ; échéances | Soldes 42-44, déclarations TVA | MIXED | — |
| 19 | Autres dettes et provisions pour risques à court terme | 1047 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Organismes internationaux, associés, créditeurs divers, comptes de liaison ; provisions à court terme (note 28) | Soldes 45-47, 185-188, 499 | MIXED | — |
| 20 | Banques, crédit d'escompte et de trésorerie | 1048 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Escomptes de crédit, banques créditrices, crédits de trésorerie ; organisme, conditions, taux, durée | Soldes 52, 56 ; conditions de crédit absentes | MIXED | — |
| 21 | Chiffre d'affaires et autres produits | 1049 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Ventes de marchandises, de produits fabriqués, de travaux et services (région, hors région, groupe, internet) ; produits accessoires ; production immobilisée, subventions, autres produits | Soldes 70-75 (sous-comptes régionaux du plan) | AUTOMATIC | Exige l'usage des sous-comptes détaillés du plan |
| 22 | Achats | 1050 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Achats de marchandises, matières premières (région, hors région, groupe), autres achats (matières, énergie, fournitures, emballages, frais, remises) | Soldes 601-608 | AUTOMATIC | Idem |
| 23 | Transports | 1051 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Transports sur ventes, pour compte de tiers, du personnel, de plis, autres | Soldes 61 | AUTOMATIC | — |
| 24 | Services extérieurs | 1052 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Sous-traitance, locations, redevances, entretien, assurances, études, publicité, télécoms, frais bancaires, honoraires, formation, redevances, cotisations, autres | Soldes 62-63 | AUTOMATIC | — |
| 25 | Impôts et taxes | 1053 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Impôts directs, indirects, droits d'enregistrement, pénalités et amendes, autres ; cause des pénalités | Soldes 64 | MIXED | Cause des pénalités à indiquer |
| 26 | Autres charges | 1054 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Pertes sur créances, quote-part, VC des cessions, rémunérations d'administrateurs, dons, autres, provisions (note 28) | Soldes 65 | MIXED | Date de PV, organismes bénéficiaires |
| 27A | Charges de personnel | 1055 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Rémunérations directes, indemnités, charges sociales, exploitant, personnel extérieur, autres ; nature et durée des contrats | Soldes 66 (comptes 661 à 668) | MIXED | Nature et durée du contrat du personnel extérieur |
| 27B | Effectifs, masse salariale et personnel extérieur | 1056 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Effectifs par qualification, sexe, nationalité ; masse salariale ; personnel extérieur (codes YA à YO) | Aucune donnée RH/paie | MANUAL_REQUIRED | Colonnes de droite rognées |
| 28 | Provisions et dépréciations inscrites au bilan | 1057 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Provisions à l'ouverture, dotations (exploitation, financières, HAO), reprises, à la clôture, par nature | Soldes 15, 19, 29, 39, 49, 59 ; mouvements 68, 69, 79, 85, 86 | MIXED | Colonnes rognées ; circonstances des constitutions et reprises à indiquer |
| 29 | Charges et revenus financiers | 1058 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Intérêts, escomptes, pertes de change, pertes sur titres, charges provisionnées ; revenus de prêts, participations, placements, gains de change… | Soldes 67, 77 | AUTOMATIC | Intérêts non comptabilisés (paiement à terme) à indiquer si présents |
| 30 | Autres charges et produits HAO | 1059 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Charges et produits HAO constatés, pertes sur créances, dons, abandons, dotations, participation, subventions d'équilibre ; transferts, reprises | Soldes 83-88 | AUTOMATIC | — |
| 31 | Répartition du résultat et autres éléments caractéristiques des cinq derniers exercices | 1060 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Structure du capital, CA, RAO, participation, impôt, résultat net, dividendes, effectifs, masse salariale | Soldes + 5 exercices ; dividendes et effectifs absents | MIXED | Colonnes rognées (N-3, N-4 non visibles) |
| 32 | Production de l'exercice | 1061 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Produit, unité, quantités et valeurs vendues (pays, autres pays OHADA, hors OHADA), production immobilisée | Module Stock (quantités) partiellement | MANUAL_REQUIRED | — |
| 33 | Achats destinés à la production | 1062 | PARTIAL | TO_DETERMINE (R4 : A / N/A par entité) | Matières et produits, quantités et valeurs (produits de l'État, importés…) | Module Stock partiellement | MANUAL_REQUIRED | Colonnes rognées |
| 34 | Fiche de synthèse des principaux indicateurs financiers | 1063 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Soldes intermédiaires de gestion, CAF, autofinancement, rentabilité, structure, FR, BFE, BF HAO, trésorerie, variation de trésorerie, endettement net | États financiers (CR, Bilan, TFT) | AUTOMATIC | Dépend de F5 et F7 complets ; montants en milliers de francs |
| 35 | Liste des informations sociales, environnementales et sociétales à fournir | 1064 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Informations qualitatives sociales, environnementales, sociétales. **« Note obligatoire pour les entités ayant un effectif de plus de 250 salariés »** | Aucune | MANUAL_REQUIRED | Règle d'applicabilité explicite : non applicable en dessous de 250 salariés |
| 36 | Tables des codes | 1065-1066 | VERIFIED | TO_DETERMINE (R4 : A / N/A par entité) | Codes forme juridique, régime fiscal, pays du siège ; codes activités économiques | Fiche d'identité de l'entité | MIXED | Table de référence (non un tableau de comptes) |

Catalogue R4 : 46 entrées `VERIFIED` / `DIRECT_PRIMARY` (p. 989 et 1019). Matrice : 11 notes `MANUAL_REQUIRED`, 26 `MIXED`, 9 `AUTOMATIC` (proposées).

**Notes prioritaires (ordre demandé)** : 3A (MIXED), 3C (MIXED), 4 (MIXED), 6 (MIXED), 7 (MIXED), 11 (MIXED), 13 (MIXED), 16A (MIXED), 17 (MIXED), 18 (MIXED), 20 (MIXED), 21 (AUTOMATIC), 22 (AUTOMATIC), 27A (MIXED), 29 (AUTOMATIC), 30 (AUTOMATIC). **Anomalies de la source** : 3F imprimée « 8A » (p. 1032) ; titre interne de 3C imprimé « NOTE 3B » (p. 1024) ; 16B et 16B bis ont le même intitulé (R4 et page 1043) ; Note 11 : sommaire « DISPONIBILITÉS », page « BANQUES, CHÈQUES POSTAUX ET CHÈQUES » ; Note 28 : lignes de la colonne « Nature » numérotées 5, 7, 6… de façon incohérente (p. 1057).

## H. Formules et contrôles (synthèse)

| Objet | Statut | Provenance |
|---|---|---|
| Compte de résultat : XA à XI (TA → RS) | VERIFIED | DIRECT_PRIMARY |
| Bilan Actif : AD, AI, AQ, AZ, BG, BK, BT, BZ | VERIFIED | DERIVED_FROM_PRIMARY |
| Bilan Passif : CP, DD, DF, DP, DT, DZ | VERIFIED | DERIVED_FROM_PRIMARY |
| Contrôle BZ = DZ | VERIFIED | DERIVED_FROM_PRIMARY |
| TFT : ZB, ZC, ZD, ZE, ZF, ZG, ZH | VERIFIED | DIRECT_PRIMARY |
| TFT : ZA + ZG = ZH | VERIFIED | DIRECT_PRIMARY |
| TFT : CAFG (termes, signes) | VERIFIED | DIRECT_PRIMARY |
| TFT : variations FB à FE (formules, exclusions) | VERIFIED | DIRECT_PRIMARY |
| TFT : investissements FF, FG, FI | VERIFIED | DIRECT_PRIMARY |
| TFT : FH, FJ | VERIFIED | DERIVED_FROM_PRIMARY |
| TFT : rattachement des termes de la CAFG aux comptes (7 termes sur 12) | PARTIAL | DERIVED_FROM_PRIMARY |

## I. Éléments restants et bloquants

| Code | Donnée manquante | Page recherchée | Raison |
|---|---|---|---|
| AK, AL (brut et dépréciations) | Rattachement exact de 2391, 2393 et du dernier compte de dépréciation (2938p/2939p) | 1068 | Résolution native de l'image : 578 × 709 px ; les derniers chiffres de ces cellules sont indistincts ; la règle d'abstention s'applique (aucune reconstitution par le plan). |
| AE, AF, AH, AK, AL, AM, AN (dépréciations) | Signification du suffixe « p » (2918p, 2919p, 2939p, 2949p) et du retrait « 294 (sauf 2945, 2949) » | 1068 ; Titre VII (fonctionnement des comptes 29), p. 271-536 | La page du tableau ne définit pas « p » ; le chapitre de fonctionnement des comptes n'a pas été exploré (hors des zones autorisées) ; non codable tant que non défini. |
| BH (définition F2 actuelle) | Correction de la dépréciation : 490, pas 499 | 1068 | Écart entre `bilan-actif.json` v1 (499, repris du paquet précédent) et la page imprimée (490). **Aucune modification n'a été faite** (consigne : pas de code/définition) ; à corriger dans une mission de définitions. |
| CA : 103, 104 | Rattachement du capital personnel et du compte de l'exploitant | 1000, 1037 (Note 13), 216 | La Note 13 ne cite que des associés/actionnaires ; 103/104 concernent les entreprises individuelles. |
| DJ : 404 | Rattachement des fournisseurs d'acquisitions courantes d'immobilisations | 1045 (Note 17), 1028 (Note 5) | Ni la Note 17 ni la Note 5 ne le citent ; décision comptable requise. |
| DM, BJ : 186, 187, 188 | Rattachement des comptes de liaison | 1031 (Note 8), 1047 (Note 19) | Cités par les deux notes (actif et passif) ; le tableau 1068 ne cite que 185 côté BJ. |
| DN / DH : 4998 ; 599 | Rattachement des provisions HAO et financières à court terme | 1028, 1047, 1057 | La Note 5 ne liste aucune provision HAO ; la Note 28 ne donne pas de comptes. |
| Soldes créditeurs de 54, 55, 57, 581, 582 | Classement | 1035, 1048 | Seuls les soldes créditeurs de 52, 53, 561, 566 sont explicites (Note 20). |
| TFT : termes CAFG partiels | Comptes de « revenus financiers », « gains de change », « pertes de change », « frais financiers », « produits HAO » | 1007, 1058, 1059 | Le terme n'est pas énuméré par compte ; plusieurs lectures sont possibles. |
| TFT : identification comptable des flux FK à FQ | Événements source (augmentation de capital en numéraire, emprunts, remboursements, dividendes) à partir des comptes 101-109, 16, 17, 465, 461… | 1013-1014 | La source donne les natures de flux, pas les comptes ni la distinction numéraire/non numéraire à partir du grand livre. |
| TFT : exclusions FB à FE | Liste exhaustive des comptes de créances/dettes d'investissement et de financement à exclure | 1009-1010 | La source donne des exemples et une règle générale (« toutes les dettes et créances… rattachables à un compte de charge ou de produit non retenu »), pas une liste. |
| Notes : colonnes rognées | Colonnes de droite de 3A, 3B, 3C, 27B, 28, 31, 33 | 1022, 1023, 1024, 1056, 1057, 1060, 1062 | Page tronquée à droite dans l'édition scannée. |

## J. Gates (état au 2026-10-05, après F8.1 ; faits réglementaires inchangés)

| Gate | État |
|---|---|
| F2 — modèle de définition versionné | READY (déjà implémenté) |
| F3 — mapping Compte de résultat | READY (implémenté) |
| F3 — mapping Actif | PARTIAL_READY : codé pour AG, AJ, AP, AR, AS, BA, BB, BH (490), BI, BJ, BQ, BR, BS, BU ; brut seul (`PARTIAL`) pour AE, AF, AH, AM, AN ; **bloqués** : AK, AL et toutes les dépréciations marquées « p » |
| F3 — mapping Passif | PARTIAL_READY : 22 postes en `DERIVED_FROM_PRIMARY` ; 6 réserves `PARTIAL` (103/104, 404, 186-188, 4998, 599, soldes créditeurs 54-58) |
| F4 — balance canonique | READY |
| F5 — Bilan | PARTIAL_READY (**implémenté**, définitions v2) : READY sur un dossier dont aucun solde ne touche une ligne `PARTIAL` ni un compte réservé (BZ = DZ, CJ = XI, présenté avant l'écriture de clôture) ; INCOMPLETE sinon. Niveau suivant bloqué par : AK, AL, dépréciations « p », brut seul AE/AF/AH/AM/AN, 6 réserves du Passif |
| F6 — Compte de résultat | READY (implémenté) |
| F7 — TFT | PARTIAL_READY (**implémenté**) : ZA, ZH, FC (stocks bruts), FB/FD/FE par classifieur, FF–FJ et FK–FQ par ecritures et événements de trésorerie qualifiés (`CashFlowEvent`), invariant ZA + ZG = ZH. READY sur un dossier sans mouvement sur les 5 termes `PARTIAL` de la CAFG et sans écriture de trésorerie non qualifiée. Niveau suivant bloqué par : revenus financiers, frais financiers (intérêts), gains/pertes de change, produits HAO (réglementaire) ; qualification humaine des événements de financement/investissement ; colonne N-1 du TFT non établie par la matrice |
| F8 — catalogue Notes | READY (R4 archivé, 46 notes) |
| F8 — automatisation | PARTIAL_READY (espace de travail F8.1 livré) : Notes AUTOMATIC, MIXED, MANUAL_REQUIRED et NOT_APPLICABLE gérées de bout en bout jusqu'au snapshot `DOSSIER_COMPLET` (données générées et manuelles séparées, applicabilité, revue, audit, versionnage). Niveau suivant bloqué par : notes MIXED/AUTOMATIC dont le tableau généré n'est pas codé (catalogue seulement), 7 notes au schéma `PARTIAL` (3A, 3B, 3C, 27B, 28, 31, 33), indicateurs de la Note 34 et ventilations des Notes 21/22 sans formule vérifiée |

Une donnée `PARTIAL` ou `UNVERIFIED` n'entre jamais dans le code, les migrations ou les seeds. Une donnée `DERIVED_FROM_PRIMARY` ne devient jamais `DIRECT_PRIMARY`.
