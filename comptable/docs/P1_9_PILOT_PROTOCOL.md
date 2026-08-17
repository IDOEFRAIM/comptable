# Protocole de validation terrain — Mission P1.9 (sections 27-31)

Ce document décrit **comment** mesurer si le produit résout réellement les problèmes d'un
cabinet comptable ouest-africain — il ne contient **aucun résultat de pilote réel** : aucun
cabinet externe n'a encore utilisé le produit au moment de la rédaction (2026-08-13).
Conformément à la section 29 de la mission ("ne jamais inventer un gain"), ce chantier livre le
**protocole et l'instrumentation**, pas des chiffres de gain fabriqués. Le
[`P1_9_RAPPORT_FINAL.md`](P1_9_RAPPORT_FINAL.md) documente ce qui est mesurable *aujourd'hui* en
environnement de développement (échantillon synthétique, N petit) séparément de ce que ce
protocole permettra de mesurer *une fois* un vrai cabinet onboardé.

## 1. Principe : jamais de gain sans baseline

Un cabinet qui commence à utiliser le produit n'a, par définition, aucune donnée "avant" dans le
produit lui-même — la baseline doit être capturée **manuellement, avant l'onboarding**, sur le
processus papier/Excel/WhatsApp existant du cabinet. Sans cette étape, toute comparaison
"avant/après" serait une fiction. C'est pourquoi ce protocole a 3 phases obligatoires et
séquentielles : **Avant** (hors produit) → **Pendant** (dans le produit, instrumenté) →
**Après** (comparaison, jamais avant un cycle comptable complet).

## 2. Dataset pilote : 8 profils d'entreprise (mission section 30)

Chaque profil cible une capacité IA différente, pour vérifier qu'aucune fonctionnalité n'est
validée uniquement sur le cas favorable. Colonne "Signal produit attendu" = ce que le profil
doit faire ressortir dans les KPI de [`ProductKpiService`](../src/main/java/com/example/comptable/telemetry/ProductKpiService.java)
et de [`CabinetAnalyticsService`](../src/main/java/com/example/comptable/cabinet/CabinetAnalyticsService.java) —
pas une promesse de résultat.

| Profil | Caractéristique | Stress-test produit | Signal attendu à observer (jamais présupposé) |
|---|---|---|---|
| **A** | Très digitalisée (factures PDF propres, banque en ligne) | Cas favorable — sert de témoin haut de gamme | Human Touch Rate le plus bas du portefeuille, Matching Quality le plus haut |
| **B** | Documents majoritairement reçus par WhatsApp (photos, qualité variable) | Extraction VLM sur images de qualité inégale, boucle `DemandePiece` | Taux d'échec/incertitude d'extraction plus élevé ; volume de relances WhatsApp |
| **C** | Fort volume Mobile Money (Orange Money, Wave, MTN MoMo) | Rapprochement sur libellés non-bancaires standards | Taux `AMBIGUOUS_MATCH`/`NO_MATCH` sur ce sous-ensemble de transactions |
| **D** | Fort volume bancaire (nombreuses lignes de relevé) | Charge du Universal Matching Engine, pré-filtrage déterministe | Proportion de matchs tranchés sans appel LLM (`_detecter_match_evident`) vs avec appel |
| **E** | Nombreuses pièces manquantes | Missing Evidence Engine + boucle `DemandePiece`/relance | Volume de `Finding` type preuve manquante, temps de résolution des `DemandePiece` |
| **F** | Fort volume fournisseurs | Résolution tiers/compte de `AccountingProposalBuilder`, ambiguïté d'historique | Niveau d'auto-application (0-3) atteint en pratique, taux de correction humaine |
| **G** | Clients payeurs en retard | Collection Risk / Recouvrement | Nombre de relances envoyées vs paiements reçus (jamais assimilé à un "succès" — voir section 4) |
| **H** | Entreprise nouvellement créée, aucun historique | Dossier neuf, tous les KPI en état vide | Vérifie que l'état vide s'affiche honnêtement (N=0, jamais 0% trompeur) — déjà couvert par `ProductKpiServiceTest.portefeuille_vide_ne_produit_aucun_pourcentage_invente` |

**Écart assumé avec la lettre de la mission** : ces 8 profils décrivent des **caractéristiques
réelles de cabinet**, à construire sur un cabinet pilote réel avec ses vraies données (factures,
relevés, historique). Ce chantier ne fabrique pas 8 nouvelles entreprises synthétiques
supplémentaires en base de développement — le dataset synthétique existant
([`P0_DATASET_SYNTHETIQUE.md`](P0_DATASET_SYNTHETIQUE.md), [`P1_DATASET_SYNTHETIQUE.md`](P1_DATASET_SYNTHETIQUE.md),
[`P1_5_DATASET.md`](P1_5_DATASET.md)) couvre déjà les mécanismes déterministes (Missing
Evidence, Control Engine, Matching, sécurité tenant) ; ce que ces mécanismes n'ont **jamais**
rencontré, c'est du volume et de la variété réels — ce que seul un pilote réel peut fournir.
Construire 8 fixtures synthétiques de plus n'aurait rien démontré de nouveau sur ce point
précis.

## 3. Phase "Avant" — baseline hors produit

À réaliser **avant** tout accès du cabinet au produit, par entretien avec le comptable/
collaborateur qui traite aujourd'hui ce client, un profil à la fois :

| Mesure | Question posée | Unité |
|---|---|---|
| Volume mensuel de pièces | "Combien de factures/reçus/relevés traitez-vous pour ce client par mois ?" | nombre de pièces |
| Volume mensuel de transactions | "Combien de lignes bancaires/Mobile Money par mois ?" | nombre de lignes |
| Temps de rapprochement | "Combien de temps pour rapprocher un mois de banque ?" | heures |
| Temps de saisie/comptabilisation | "Combien de temps pour comptabiliser un mois de pièces ?" | heures |
| Temps de relance pièces manquantes | "Combien de relances envoyées par mois, combien de temps chacune ?" | nombre + minutes |
| Délai de clôture | "Combien de jours entre la fin du mois et la clôture effective ?" | jours |
| Nombre de relances de recouvrement | "Combien de relances de paiement envoyées par mois, quel délai moyen de paiement ?" | nombre + jours |

Ces chiffres sont déclaratifs (entretien), pas mesurés automatiquement — c'est une limite
assumée : aucun système existant chez le cabinet pilote n'est instrumenté, donc la seule source
possible est le témoignage du comptable. Consigné avant l'onboarding, jamais reconstruit a
posteriori.

## 4. Phase "Pendant" — mesure instrumentée dans le produit

Une fois le cabinet onboardé, les mêmes grandeurs sont mesurées automatiquement via les KPI déjà
instrumentés (P1.9 Phase 1-2, voir [`P1_9_AUDIT.md`](P1_9_AUDIT.md) section G) :

| Mesure "Avant" | KPI produit équivalent | Source |
|---|---|---|
| Temps de rapprochement | Matching Quality (taux d'acceptation, `AiMatchProposal` auto-appliqué vs revu) | `ProductKpiService.matchingBancaire` |
| Temps de saisie/comptabilisation | Human Touch Rate côté comptabilisation (`AccountingProposal` niveau 0-3) | `ProductKpiService.humanTouchRate` |
| Temps de relance pièces manquantes | Evidence Resolution (résolution `DemandePiece`, durée moyenne) | `ProductKpiService.evidenceResolution` |
| Délai de clôture | **Non instrumenté dans ce chantier** — gap assumé (voir `P1_9_AUDIT.md` section F.3, `ExerciceComptable` n'a pas d'horodatage de clôture persistée) | — |
| Relances de recouvrement | Collection Risk (montants en retard, relances envoyées) | `CollectionRiskService` (P1) |
| Findings/anomalies détectés | Finding Resolution Time, Work Queue | `ProductKpiService.findingResolutionTime`, `.workQueue` |
| Usage du Copilot | Copilot Usage/Quality (questions, feedback, taux de réponse avec preuve) | `ProductKpiService.copilotUsage` |

**Règle absolue (section 9 de la mission)** : un rapprochement auto-appliqué n'est jamais compté
comme "correct" tant qu'il n'a pas traversé une fenêtre d'observation raisonnable sans
correction manuelle signalée — voir la limite documentée dans `P1_9_AUDIT.md` (le False
Auto-Match Rate n'est pas mesurable avec le modèle de données actuel, car une proposition déjà
`ACCEPTED` automatiquement ne peut plus être rejetée via aucun endpoint existant :
`AiMatchProposalService.charger` exige `status == PROPOSED`). Cette limite doit être communiquée
au cabinet pilote explicitement, pas cachée.

De même, une relance de recouvrement envoyée n'est jamais qualifiée de "réussie" simplement parce
qu'elle a été envoyée — seul un paiement effectivement reçu après la relance compte comme signal
positif (voir mission section 11, KPI Collection).

## 5. Phase "Après" — comparaison

La comparaison Avant/Pendant ne doit être faite **qu'après au moins un cycle comptable complet**
(un mois calendaire, idéalement deux pour lisser les effets de démarrage/apprentissage) — jamais
après quelques jours d'usage, qui ne mesureraient que la courbe d'apprentissage de l'outil, pas
sa valeur en régime établi.

Format de restitution par profil (A-H) et agrégé :

```
Profil : <A-H>
Cycle mesuré : <mois/année>
Temps de rapprochement   : avant Xh  → pendant Yh   (delta, jamais un % seul sans les deux valeurs)
Temps de comptabilisation: avant Xh  → pendant Yh
Relances pièces manquantes: avant N/mois → pendant N/mois
Délai de clôture         : avant J jours → pendant J jours (si mesurable, sinon "non mesuré - gap connu")
N (taille d'échantillon)  : <nombre de pièces/transactions traitées ce cycle>
```

Le N doit toujours accompagner chaque comparaison — un delta calculé sur 3 pièces n'a pas la même
valeur probante qu'un delta sur 300, et le rapport ne doit jamais les présenter de façon
indifférenciée (même discipline que le tableau de bord Qualité, voir `P1_9_AUDIT.md` section H).

## 6. Ce que ce protocole ne couvre pas (limites assumées)

- **Aucun cabinet réel n'a encore été onboardé** au moment de la rédaction de ce document — ce
  protocole est prêt à exécuter, pas encore exécuté.
- **Le Time to Closing n'est pas instrumenté** (gap connu, voir section 4) — la phase "Après" ne
  pourra pas comparer ce point tant qu'un horodatage de clôture n'est pas ajouté (candidat P2 ou
  correctif mineur ultérieur, pas fait dans ce chantier faute de justification suffisante par
  l'audit pour ouvrir une nouvelle colonne sur `ExerciceComptable` sans cas d'usage pilote
  concret encore observé).
- **Cash Forecast Quality n'est pas mesurable** avant un historique de plusieurs mois de
  prévisions vs réalisations sur un cabinet réel (voir `P1_9_AUDIT.md` section F.4).
- Les mesures "Avant" restent déclaratives (entretien), jamais vérifiées par un système tiers —
  limite inhérente au fait que la plupart des cabinets cibles n'ont aujourd'hui aucun outil
  digital pour ce processus (c'est précisément le problème que le produit adresse).
