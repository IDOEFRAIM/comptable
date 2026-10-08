# SYSCOHADA — paquet de déblocage réglementaire Phase F

Source primaire : **AUDCIF + Système comptable OHADA (SYSCOHADA)**, Journal Officiel OHADA, numéro spécial du 15 février 2017.

Ce document synthétise uniquement les éléments relus visuellement dans le PDF officiel signé. Il distingue les données désormais vérifiées de l'unique zone encore non explicitement sourcée.

## 1. Gate révisé

- **F2 — moteur de définitions versionné : READY**
- **F3 — mapping : READY pour le Compte de résultat ; READY/PARTIAL pour l'Actif ; PASSIF direct comptes→postes toujours à traiter explicitement**
- **F4 — balance canonique : READY**
- **F5 — Bilan complet : PARTIAL tant que la stratégie de mapping Passif n'est pas validée**
- **F6 — Compte de résultat : READY**
- **F7 — TFT : READY au niveau structure + formules/méthode**
- **F8 — catalogue des Notes : READY ; automatisation détaillée note par note reste progressive**

Le gate F2 global n'a plus de raison de rester bloqué à cause du plan de comptes, du CR, du TFT ou de la liste des Notes.

## 2. Plan de comptes

Les pages imprimées **252 à 269** ont été relues visuellement.

Résultats :
- classes **61 à 99** transcrites ;
- **66 = CHARGES DE PERSONNEL** ;
- **67 = FRAIS FINANCIERS ET CHARGES ASSIMILÉES** ;
- classes 7 et 8 confirmées ;
- classe 9 confirmée.

Anomalies de la source primaire :
- sous le compte 412, la ligne « Organismes internationaux, Effets à recevoir » est imprimée avec le code **412** : ne pas inventer `4125` ;
- les comptes `4282` à `4285` ne sont pas présents entre les pages adjacentes 239 et 240 ; la source passe de `4281` à `4286` ;
- `4793` et `4794` portent tous deux littéralement « Diminution des dettes d'exploitation » ;
- dans la classe 9, la source imprime de nouveau **907** devant « AUTRES ENGAGEMENTS ACCORDÉS », alors que les sous-comptes sont `9081`, `9082`, `9083`, `9088`. Conserver l'anomalie documentaire ; ne pas réécrire silencieusement le texte officiel.

## 3. Bilan — structure officielle vérifiée

### Actif

Structure vérifiée sur le modèle officiel :

- AD Immobilisations incorporelles
- AE Frais de développement et de prospection
- AF Brevets, licences, logiciels, et droits similaires
- AG Fonds commercial et droit au bail
- AH Autres immobilisations incorporelles
- AI Immobilisations corporelles
- AJ Terrains
- AK Bâtiments
- AL Aménagements, agencements et installations
- AM Matériel, mobilier et actifs biologiques
- AN Matériel de transport
- AP Avances et acomptes versés sur immobilisations
- AQ Immobilisations financières
- AR Titres de participation
- AS Autres immobilisations financières
- AZ Total actif immobilisé
- BA Actif circulant HAO
- BB Stocks et encours
- BG Créances et emplois assimilés
- BH Fournisseurs, avances versées
- BI Clients
- BJ Autres créances
- BK Total actif circulant
- BQ Titres de placement
- BR Valeurs à encaisser
- BS Banques, chèques postaux, caisse et assimilés
- BT Total trésorerie-actif
- BU Écart de conversion-actif
- BZ Total général

Colonnes : **N brut / amortissements-dépréciations / N net / N-1 net**.

### Passif

Structure vérifiée :

- CA Capital
- CB Apporteurs, capital non appelé
- CD Primes liées au capital social
- CE Écarts de réévaluation
- CF Réserves indisponibles
- CG Réserves libres
- CH Report à nouveau (+ ou -)
- CJ Résultat net de l'exercice
- CL Subventions d'investissement
- CM Provisions réglementées
- CP Total capitaux propres et ressources assimilées
- DA Emprunts et dettes financières diverses
- DB Dettes de location acquisition
- DC Provisions pour risques et charges
- DD Total dettes financières et ressources assimilées
- DF Total ressources stables
- DH Dettes circulantes HAO
- DI Clients, avances reçues
- DJ Fournisseurs d'exploitation
- DK Dettes fiscales et sociales
- DM Autres dettes
- DN Provisions pour risques à court terme
- DP Total passif circulant
- DQ Banques, crédits d'escompte
- DR Banques, établissements financiers et crédits de trésorerie
- DT Total trésorerie-passif
- DV Écart de conversion-passif
- DZ Total général

Colonnes : **N net / N-1 net**.

### Correspondance Actif

La table officielle imprimée p.1068 contient bien les correspondances Actif. Les grandes règles suivantes sont nettes et vérifiées :

- AJ → 22 ; amortissements et dépréciations 282, 292 (corrigé par relecture directe de la p. 1068 ; l'ancienne transcription « 292, 297 » était erronée)
- AP → 251, 252 ; dépréciations 2951, 2952
- AR → 26 ; dépréciations 296
- AS → 27 ; dépréciations 297
- BA → 485, 488 ; dépréciations 498
- BB → 31 à 38 ; dépréciations 39
- BH → 409 ; dépréciations 499
- BI → 41 sauf 419 ; dépréciations 491
- BJ → soldes débiteurs 185, 42, 43, 44, 45, 46, 47 **sauf 478** (corrigé par relecture directe de la p. 1068 ; 478 correspond à BU uniquement) ; dépréciations 492 à 497 selon les familles
- BQ → 50 ; dépréciations 590
- BR → 51 ; dépréciations 591
- BS → soldes débiteurs de trésorerie (52, 53, 54, 55, 57, 581, 582) avec dépréciations correspondantes
- BU → 478

Les lignes détaillées d'immobilisations AE–AN existent dans la même table, mais certains sous-numéros du scan sont trop serrés pour être considérés comme définitivement transcrits sans zoom spécifique ligne par ligne. Les règles générales de structure sont vérifiées ; les sous-listes qui entreraient dans un seed doivent garder un contrôle de provenance précis.

### Correspondance Passif : point restant

La page officielle de correspondance ne fournit pas, dans cette édition scannée, une colonne lisible et explicite comptes→postes pour le Passif comparable à celle de l'Actif.

**Ne pas promouvoir en VERIFIED** les mappings passif obtenus par recoupement secondaire.

Deux options propres :
1. laisser les postes passif non mappés tant qu'une source primaire explicite complémentaire n'est pas identifiée ;
2. construire plus tard un mapping dérivé et documenté par croisement du plan officiel + libellés officiels, avec statut différent d'un mapping « directement publié ».

## 4. Compte de résultat — mapping et formules vérifiés

La table officielle imprimée p.1069 permet de considérer les mappings suivants comme vérifiés :

- TA +701
- RA -601
- RB -/+6031
- XA = TA + RA + RB (p. 1069 : « MARGE COMMERCIALE (Somme TA à RB) » ; corrigé par relecture directe)
- TB +702,703,704
- TC +705,706
- TD +707
- XB = TA + TB + TC + TD
- TE -/+73
- TF +72
- TG +71
- TH +75
- TI +781
- RC -602
- RD -/+6032
- RE -604,-605,-608
- RF -/+6033
- RG -61
- RH -62,-63
- RI -64
- RJ -65
- XC = (XB + RA + RB) + somme des lignes TE à RJ selon leur signe
- RK -66
- XD = XC + RK
- TJ +791,+798,+799
- RL -681,-691
- XE = XD + TJ + RL
- TK +77
- TL +797
- TM +787
- RM -67
- RN -697
- XF = somme TK à RN
- XG = XE + XF
- TN +82
- TO +84,+86,+88
- RO -81
- RP -83,-85
- XH = somme TN à RP
- RQ -87
- RS -89
- XI = XG + XH + RQ + RS

La précédente incertitude sur **TG** est levée : **TG = +71**.

## 5. TFT — structure, formules et méthode vérifiées

Structure officielle :
- ZA Trésorerie nette au 1er janvier
- FA CAFG
- FB Actif circulant HAO
- FC Variation des stocks
- FD Variation des créances
- FE Variation du passif circulant
- ZB Flux des activités opérationnelles
- FF à FJ Flux d'investissement
- ZC Flux d'investissement
- FK à FN Flux liés aux capitaux propres
- ZD Flux capitaux propres
- FO à FQ Flux capitaux étrangers
- ZE Flux capitaux étrangers
- ZF Flux de financement
- ZG Variation de trésorerie nette
- ZH Trésorerie nette à la clôture

Formules principales :
- ZA = Trésorerie-actif N-1 − Trésorerie-passif N-1
- ZB = somme FA à FE, avec les signes du modèle
- ZC = somme FF à FJ
- ZD = somme FK à FN
- ZE = somme FO à FQ
- ZF = ZD + ZE
- ZG = ZB + ZC + ZF
- ZH = Trésorerie-actif N − Trésorerie-passif N

Méthodes officielles relues :
- CAFG = EBE − charges décaissables restantes + produits encaissables restants, selon la décomposition officielle ;
- Encaissements N = Produits N + Créances N-1 − Créances N ;
- Décaissements N = Achats N + Dettes N-1 − Dettes N ;
- Achats de l'exercice = Achats consommés − variation de stocks ;
- flux opérationnel = CAFG − variation du besoin de financement lié aux activités opérationnelles ;
- investissements : acquisitions en décaissements, cessions en encaissements ;
- financement : apports/subventions/emprunts en encaissements, retraits/dividendes/remboursements en décaissements ;
- exclure des variations opérationnelles les créances/dettes rattachées aux investissements et au financement.

Il n'existe pas, dans les pages relues, de table directe de mapping comptes→lignes TFT comparable à celle du CR. Le moteur TFT doit donc être basé sur la méthode officielle et les données source, pas sur un mapping inventé de préfixes.

## 6. Notes annexes — catalogue officiel vérifié

La Fiche R4 p.989 donne la liste officielle :

1. Dettes garanties par des sûretés réelles
2. Informations obligatoires
3A. Immobilisation brute
3B. Biens pris en location acquisition
3C. Immobilisations : amortissements
3D. Immobilisations : plus-values et moins value de cession
3E. Informations sur les réévaluations effectuées par l'entité
3F. Tableau d'étalement des charges immobilisées
4. Immobilisations financières
5. Actif circulant HAO
6. Stocks et en-cours
7. Clients produits à recevoir
8. Autres créances
9. Titres de placement
10. Valeurs à encaisser
11. Disponibilités
12. Écarts de conversion
13. Capital : valeur nominale des actions ou parts
14. Primes et réserves
15A. Subventions et provisions réglementées
15B. Autres fonds propres
16A. Dettes financières et ressources assimilées
16B. Engagements de retraite et avantages assimilés (méthode actuarielle)
16B bis. Engagements de retraite et avantages assimilés (source à détailler lors de l'implémentation de la note)
16C. Actifs et passifs éventuels
17. Fournisseurs d'exploitation
18. Dettes fiscales et sociales
19. Autres dettes et provisions pour risques à court terme
20. Banques, crédit d'escompte et de trésorerie
21. Chiffre d'affaires et autres produits
22. Achats
23. Transports
24. Services extérieurs
25. Impôts et taxes
26. Autres charges
27A. Charges de personnel
27B. Effectifs, masse salariale et personnel extérieur
28. Provisions et dépréciations inscrites au bilan
29. Charges et revenus financiers
30. Autres charges et produits HAO
31. Répartition du résultat et autres éléments caractéristiques des cinq derniers exercices
32. Production de l'exercice
33. Achats destinés à la production
34. Fiche de synthèse des principaux indicateurs financiers
35. Liste des informations sociales, environnementales et sociétales à fournir
36. Tables des codes

La Fiche R4 prévoit qu'une note non applicable n'a pas à être jointe.

## 7. Décision recommandée

Le précédent statut global `F2 = BLOCKED` doit être remplacé par un gate fin :

- **F2 READY** : modèle de définition/versioning, uniquement avec structures vérifiées ;
- **F3 READY pour CR**, Actif progressif, Passif non promu sans source explicite ;
- **F4 READY** ;
- **F5 PARTIAL** jusqu'au mapping Passif ;
- **F6 READY** ;
- **F7 READY** ;
- **F8 READY pour catalogue**, automatisation détaillée progressive.

Aucune donnée secondaire ne doit être maquillée en donnée primaire.
