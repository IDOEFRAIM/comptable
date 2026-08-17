# Matrice de décision P2 (mission P1.9, sections 45-47)

**Provisoire, à revoir après le pilote terrain.** Conformément à la mission ("le P2 ne doit pas
être pré-décidé — se baser sur l'usage/la douleur/le KPI/la demande utilisateur/le coût/l'effort/
la valeur, jamais choisir automatiquement Paie/Stock/etc."), ce document classe les candidats
avec les informations disponibles **aujourd'hui** (audit de code, pas encore d'usage réel
mesuré — voir [`P1_9_PILOT_PROTOCOL.md`](P1_9_PILOT_PROTOCOL.md)). La colonne **Usage attendu**
est une hypothèse à vérifier, pas une donnée : elle doit être remplacée par la fréquence réelle
observée pendant le pilote avant qu'une décision finale soit prise.

## Légende des scores

Faible / Moyen / Élevé pour Pain, Fréquence, Impact, Usage attendu, IA (dépendance à une
capacité IA pour apporter de la valeur) ; Effort en jours-personne estimés (ordre de grandeur,
pas un chiffrage projet).

## Matrice

| Candidat | Pain (aujourd'hui) | Fréquence | Impact métier | Usage attendu | Effort | Dépendance IA | Priorité provisoire |
|---|---|---|---|---|---|---|---|
| **Unifier la double représentation "facture fournisseur"** (`Document.FACTURE_ACHAT` vs `facture_fournisseur`) | Élevé — confusion documentée depuis P0, jamais actionnée | Quotidien (chaque pièce fournisseur) | Élevé — bloque une vision fournisseur unique, fausse potentiellement le rapprochement | Élevé (tous les cabinets à fort volume fournisseurs, profil F du pilote) | Moyen (10-15j — refonte ciblée, modèle de données déjà connu) | Aucune (refonte de modèle, pas une fonctionnalité IA) | **1 — le plus mûr, reporté 3 fois sans être ni fait ni refusé** |
| **Tester le Universal Matching Engine + l'Agent Autonome avec une vraie clé Anthropic** | Élevé — zéro donnée réelle sur la qualité du jugement du modèle en production | Continu une fois activé | Élevé — conditionne la décision d'activer ces flags en production | Élevé (tout cabinet avec matching activé) | Faible (0j de code — juste `AI_PROVIDER=anthropic` + budget) | Totale (c'est la vérification elle-même) | **1bis — bloqué uniquement par le budget, pas par le produit** |
| **Time to Closing instrumenté** (horodatage de clôture persisté) | Moyen — гap connu depuis l'audit P1.9 | Mensuel (un cycle de clôture) | Moyen — complète le tableau de bord Qualité | Moyen | Faible (2-3j — une colonne + un point d'écriture) | Aucune | **2 — petit effort, débloque un KPI déjà attendu** |
| **Multi-pays OHADA — règles fiscales réelles par pays** (`TaxRule` peuplée) | Moyen — le sélecteur pays existe déjà (8 pays UEMOA), mais `tax_rule` est vide | Mensuel/déclaratif | Élevé si le cabinet pilote est hors Côte d'Ivoire | Dépend du profil du cabinet pilote (à vérifier, pas supposé) | Moyen (5-10j par pays — nécessite une source réglementaire vérifiée, pas juste du code) | Faible (l'IA ne doit jamais inventer une règle fiscale) | **3 — conditionnel au pays réel du pilote** |
| **Intégrations bancaires réelles** (API banques UEMOA, au lieu d'import de relevé) | Moyen — l'import manuel fonctionne mais demande une action du client | Quotidien/hebdomadaire | Élevé si disponible, mais dépend d'API tierces hors du contrôle du produit | Élevé en théorie, incertain en pratique (couverture API bancaire UEMOA limitée) | Élevé (20j+ — intégration par banque, pas un standard unique en zone UEMOA) | Aucune (intégration technique, pas IA) | **4 — forte valeur mais hors du contrôle du produit (dépend des banques)** |
| **Paie** | Inconnu — aucune donnée d'usage, aucun module existant | Mensuel | Élevé si demandé, mais **aucun signal produit actuel** (zéro ligne de code, zéro mention dans les Findings/Copilot) | **À vérifier au pilote, pas supposé** | Élevé (30j+ — domaine réglementaire distinct, CNPS/IPRES selon pays) | Faible | **5 — candidat le moins mûr, ne pas présumer la demande** |
| **Achats / Gestion commerciale avancée** | Faible-Moyen — `stock/`, `CommandeAchatRepository`, `FactureFournisseurRepository` **existent déjà** (base posée hors des missions P0/P1/P1.5/P1.9) | Variable selon cabinet | Moyen | Inconnu | Moyen (extension d'un module existant, pas une création) | Faible | **6 — dépend de la maturité réelle du module existant, à auditer séparément avant de trancher** |
| **Stock (au-delà de l'existant)** | Faible — module déjà présent (`stock/domain`, `service`, `web`) | Variable | Moyen, cabinet-dépendant | Inconnu | Faible-Moyen (extension) | Faible | **6bis — même remarque que ci-dessus** |
| **Forecasting avancé** (au-delà de KNOWN/EXPECTED) | Moyen — le palier PREDICTED a été explicitement refusé en P1 faute de données suffisantes | Continu | Moyen | Inconnu | Moyen-Élevé (nécessite un historique de paiements réels suffisant — même contrainte que Cash Forecast Quality, voir `P1_9_AUDIT.md` F.4) | Élevé | **7 — bloqué par la même contrainte de données que le KPI associé, pas la peine avant d'avoir un historique réel** |
| **Benchmarking inter-cabinets/clients** | Faible aujourd'hui — aucune demande observée | Faible | Moyen à terme | Inconnu | Moyen | Moyen | **8 — aucun signal produit actuel, à ne considérer qu'après un usage établi du Cabinet Analytics existant** |
| **Agents spécialisés supplémentaires** (au-delà de l'agent autonome expérimental) | Faible — l'agent autonome existant (module 1️⃣6️⃣) n'est déjà câblé sur aucun flux réel | Faible | Incertain | Inconnu | Élevé | Totale | **9 — contredit l'esprit de la mission ("ne pas sur-agentiser") tant que l'agent existant n'a pas prouvé sa valeur en production** |
| **PI-SPI** (interconnexion inter-bancaire/paiement instantané UEMOA) | Inconnu — pas d'audit spécifique fait dans ce chantier | Inconnu | Potentiellement élevé (paiements XOF) | Inconnu | Élevé (nécessite une expertise réglementaire/technique spécifique non couverte par l'audit actuel) | Aucune | **10 — nécessite un audit dédié avant même de pouvoir noter cette ligne sérieusement, pas assez d'information pour trancher** |

## Pourquoi ce classement, pas un autre

Les deux candidats en tête (**1** et **1bis**) ne sont pas de nouvelles fonctionnalités mais des
**dettes reportées** : chacune a été identifiée dans un audit précédent (P0 pour la double
représentation facture fournisseur, chaque mission depuis pour le test Anthropic réel), jamais
explicitement refusée ni planifiée. Les remonter en tête n'introduit aucun nouveau candidat non
demandé — c'est la lecture directe de la mission section 47 ("ne pas ignorer les items reportés
sans décision").

**Paie** n'est délibérément **pas** placé en tête malgré sa fréquence usuelle dans ce type de
produit ailleurs : aucun signal réel (code, Finding, demande Copilot, mention dans un rapport)
n'existe dans ce projet pour la justifier aujourd'hui. La placer en priorité 1 aurait été
exactement l'erreur que la mission interdit explicitement ("ne jamais choisir automatiquement
Paie/Stock/... sans preuve d'usage").

## Ce que le pilote doit apporter avant la décision finale

- Remplacer la colonne "Usage attendu" (hypothèse) par une fréquence réellement observée par
  profil de cabinet (voir les 8 profils A-H de `P1_9_PILOT_PROTOCOL.md`).
- Vérifier si des questions Copilot hors du périmètre actuel reviennent souvent (signal direct
  de demande non couverte — le Copilot journalise déjà les questions qui tombent en réponse de
  repli, voir `docs/P1_9_AUDIT.md` section G).
- Confirmer le pays réel du/des cabinet(s) pilote(s) avant de prioriser le multi-pays OHADA.
- Auditer séparément la maturité réelle de `stock/`/`CommandeAchatRepository`/
  `FactureFournisseurRepository` (jamais couverts par les missions P0-P1.9, état réel inconnu de
  ce chantier) avant de les considérer comme un candidat P2 sérieux — ce document se limite à
  constater leur existence, pas à juger leur complétude.
