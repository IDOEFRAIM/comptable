# Closeout réglementaire SYSCOHADA — Phase F (Bilan Actif/Passif, TFT, Notes)

Date : 2026-10-04. **Aucun code, aucune migration, aucun seed, aucune définition n'a été modifié.** Source : PDF officiel archivé (SHA-256 `d5ecb380…8e05300`), pages imprimées 989, 991-1000, 1005-1016, 1017-1066, 1067-1070, plan de comptes (216-269). Aucune recherche web, aucun OCR : lecture directe d'images 250 dpi / 170 dpi des pages concernées (images natives de 578 × 709 px). Le détail ligne à ligne est dans `docs/syscohada-official-matrix.md` ; l'état machine dans `sources-reglementaires/syscohada-matrix-status-updated.json`.

## 1. Ce qui est maintenant `VERIFIED` / `DIRECT_PRIMARY`
* Structure du Bilan Actif (29 lignes) et Passif (28 lignes) : codes, libellés, numéros de notes (p. 999, 1000).
* **Correspondance Actif** (p. 1068) : brut de AE, AF, AG, AH, AJ, AM, AN, AP, AR, AS, BA, BB, BH, BI, BJ, BQ, BR, BS, BU ; dépréciations de AG, AJ, AP, AR, AS, BA, BB, **BH = 490** (et non 499), BI, **BJ = 492 à 497** (explicite), BQ, BR, **BS = 592, 593, 594** (explicite).
* TFT : toutes les formules de somme, les signes, la liste des termes de la CAFG, les formules de variation FB à FE avec leurs exclusions, la relation d'acquisition des immobilisations corporelles, la formule des encaissements sur cessions, la composition de la trésorerie, les événements de financement.
* Notes : catalogue R4 (46 notes, p. 989 = sommaire p. 1019), règle « note non applicable → non jointe », règle de la Note 35 (> 250 salariés), structure de 39 notes sur 46 (7 à colonnes rognées).

## 2. Ce qui est `DERIVED_FROM_PRIMARY` (déterministe, documenté, codable)
* **Totaux du Bilan** (AD, AI, AQ, AZ, BG, BK, BT, BZ ; CP, DD, DF, DP, DT, DZ) : hiérarchie du modèle imprimé ; la source n'imprime aucune formule.
* **Contrôle `BZ = DZ`**.
* **Correspondance Passif** pour 22 postes (CA partiel, CB, CD, CE, CF, CG, CH, CJ, CL, CM, DA, DB, DC, DH, DI, DJ partiel, DK, DM partiel, DN partiel, DQ, DR, DV) : dérivée du **libellé officiel du poste + de la Note correspondante + du plan officiel** (les Notes détaillent chaque poste avec les libellés des comptes). Cas A (liste publiée) : aucun, la page 1068 est rognée.
* TFT : FH, FJ (analogues de FG, FI), FF pour la reconstitution, termes 787, 848, 87, 89 de la CAFG.

## 3. Ce qui reste `PARTIAL` (non codable)
* Actif : brut de AK et AL (chiffres indistincts) ; dépréciations de AE, AF, AH, AK, AL, AM, AN (suffixe « p » non défini).
* Passif : 103, 104 ; 404 ; 186-188 ; 4998 ; 599 ; soldes créditeurs de 54, 55, 57, 581, 582.
* TFT : comptes de 5 termes de la CAFG (revenus financiers, gains et pertes de change, frais financiers, produits HAO) ; liste des comptes à exclure de FB-FE ; identification comptable des flux FK-FQ (numéraire vs non numéraire).
* Notes : colonnes rognées de 3A, 3B, 3C, 27B, 28, 31, 33.

## 4. Ce qui reste `UNVERIFIED`
Rien d'inscrit comme `UNVERIFIED` : tout ce qui n'est pas établi est `PARTIAL` (source primaire consultée mais incomplète) et listé en §I de la matrice avec la page recherchée. Le recoupement externe du Passif n'est conservé nulle part comme preuve (`CORROBORATED_ONLY` : non codable).

## 5. Ce qui est codable
Compte de résultat (déjà fait) ; Bilan Actif : les postes listés au §1 (brut + dépréciations) et les totaux ; Bilan Passif : les 22 postes dérivés et les totaux ; contrôle `BZ = DZ` ; TFT : formules de somme, invariants, trésorerie, FC, ZA, ZH, FG/FI par les relations imprimées, FK à FQ dès que les événements sont fournis ; catalogue des Notes ; matrice des Notes (proposition).

## 6. Ce qui ne doit pas être codé
Les lignes `PARTIAL` du §3 ; le suffixe « p » ; la reprise de la corroboration externe du Passif ; la déduction de comptes à partir du plan pour AK/AL ; un TFT complet sur dossier réel tant que les comptes de la CAFG et les exclusions FB-FE ne sont pas tranchés (le produit pourrait produire un TFT avec Findings `UNMAPPED_ACCOUNT` mais pas un TFT final).

## 7. Gate F5 (Bilan)
**PARTIAL_READY.** Codable : Actif (14 postes complets dont BU sans dépréciation, 5 avec brut seul), Passif (22 postes dérivés), totaux, `BZ = DZ`. Bloqué : AK, AL, dépréciations « p », 6 réserves du Passif. Un Bilan complet exige : (1) le suffixe « p » défini par le Titre VII ou une décision comptable ; (2) une image plus lisible de la page 1068 (AK/AL) ; (3) la validation de CJ et des règles de côté dérivées. Tant que ces points sont ouverts, le Bilan reste `INCOMPLETE` avec les comptes non mappés exposés.

## 8. Gate F7 (TFT)
**PARTIAL.** `ZA`, `ZB` à `ZH` et les invariants sont codables (le Passif étant maintenant dérivable). La CAFG est calculable pour 7 termes sur 12 ; les 5 autres, les exclusions FB-FE et les flux FK-FQ exigent une décision (reconstitution par bilan et événements). Aucun TFT final tant que ces points ne sont pas levés.

## 9. Gate F8 (Notes)
**Catalogue : READY.** **Automatisation : PARTIAL.** Proposition : 9 notes `AUTOMATIC` (5, 10, 21, 22, 23, 24, 29, 30, 34), 26 `MIXED`, 11 `MANUAL_REQUIRED` (dont 35 conditionnelle à 250 salariés). Les 16 notes prioritaires de la mission : 3A, 3C, 4, 6, 7, 11, 13, 16A, 17, 18, 20, 21, 22, 27A, 29, 30 — leurs modèles imprimés sont lus ; le codage de chaque table (mapping lignes de note → comptes du plan) est un travail de définitions distinct, à faire note par note.

## 10. Erreur découverte dans la définition F2 (à corriger lors d'une mission de définitions)
`etats-financiers/SYSCOHADA_REVISE_AUDCIF_2017/1/bilan-actif.json`, ligne **BH** : dépréciation `499` ; la page imprimée 1068 indique **`490`** (cohérent avec le plan : 490 = dépréciations des comptes fournisseurs, 499 = provisions pour risques à court terme). Conséquence actuelle : un solde 490 serait « non mappé » et 499 serait rangé à tort en BH. Corrections permises par cette lecture (non appliquées ici, conformément à la consigne) : BH 490 ; BJ dépréciations 492-497 → ligne `VERIFIED` ; BS dépréciations 592-594 → ligne `VERIFIED` ; AG ; et les bruts de AE, AF, AH, AM, AN.

## 11. Décompte (lignes de la matrice)
| Bloc | VERIFIED | PARTIAL | UNVERIFIED |
|---|---|---|---|
| Bilan Actif | 69 | 9 | 0 |
| Bilan Passif | 51 | 6 | 0 |
| TFT | 34 | 5 | 0 |
| Notes | 85 | 7 | 0 |

## 10. État d'implémentation (2026-10-05) — les faits réglementaires ci-dessus sont inchangés

* **Gate F5 : PARTIAL_READY.** Bilan implémenté (définitions v2, `BalanceSheetService`) : READY si aucun solde ne touche AE/AF/AH/AM/AN/AK/AL, une dépréciation « p » ou un compte réservé (103, 104, 404, 186-188, 4998, 599) ; contrôles BZ = DZ et CJ = XI ; Bilan présenté avant l'écriture technique de clôture.
* **Gate F7 : PARTIAL_READY.** TFT implémenté : FC sur stocks bruts (une dépréciation n'est jamais un flux), FB/FD/FE par classifieur d'exclusions, FF–FJ et FK–FQ par les écritures de trésorerie (preuve cash dans l'écriture) et les événements déclarés à la saisie (`CashFlowEvent`, colonne `ecriture_comptable.cash_flow_event`, V63). Les écritures sans ligne de trésorerie n'ont aucun flux (incorporation de réserves, conversion de dette, location-acquisition, cession non encaissée, mise au rebut). Bloqué : les 5 termes `PARTIAL` de la CAFG (finding `UNRESOLVED_CAFG_COMPONENT`, avec le compte déclencheur), événements non qualifiés (finding `UNQUALIFIED_CASH_EVENT`).
* **Gate F8 : PARTIAL.** Catalogue READY ; notes générées : 5, 10, 23, 24, 29, 30 ; 21 et 22 (totaux par famille issus des lignes vérifiées du CR, ventilation par zone manuelle car le plan de comptes n'a pas de sous-comptes régionaux) ; 34 (lecture de EBE, résultat net, CAFG, total du bilan, trésorerie ; dépend de CR, Bilan et TFT READY ; indicateurs sans formule vérifiée manuels). Statuts de note : NOT_APPLICABLE, READY, INCOMPLETE, MANUAL_REQUIRED.
* **Chemin de succès `DOSSIER_COMPLET`** : possible (testé) pour un dossier dont CR, Bilan et TFT sont READY, exercice clôturé, et dont les applicabilités des 46 notes sont décidées par l'utilisateur ; toute note applicable avec section manuelle reste `MANUAL_REQUIRED` (aucune saisie manuelle de contenu n'est stockée aujourd'hui).
* Seuls blocages réglementaires restants : AK/AL, suffixe « p », brut seul AE/AF/AH/AM/AN, 6 réserves du Passif, 5 termes de la CAFG, indicateurs de la Note 34 sans formule vérifiée, ventilations des Notes 21/22.

## 11. F8.1 — Espace de travail des Notes (2026-10-05)

Contenu humain persistant des Notes (`financial_statement_note`, V64), séparé des données générées ; applicabilité décidée, revue, audit, snapshot `DOSSIER_COMPLET` avec notes (générées, manuelles, revue). **Gate F8 : PARTIAL_READY.** Un dossier réel gère de bout en bout une note AUTOMATIC (23), une MIXED (17), une MANUAL_REQUIRED (2) et une NOT_APPLICABLE validée. Reste bloquant (réglementaire ou de définition) : tableaux générés non codés pour la plupart des notes MIXED/AUTOMATIC, 7 notes au schéma `PARTIAL`, formules des indicateurs de la Note 34, ventilations des Notes 21/22. Détail : `docs/syscohada-notes-workspace.md`.
