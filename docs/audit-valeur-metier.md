# Audit de valeur métier — Warimara

Date : 2026-10-09. Périmètre : tout ce que voit et lit un utilisateur dans l'interface (≈ 45 écrans, ≈ 70 indicateurs et libellés chiffrés).
Question posée à chaque écran et chaque chiffre : **quelle décision ou quelle action cela permet-il, à quelle personne, et est-ce que le chiffre est juste et compréhensible pour elle ?**

## 0. Limites de cet audit (à lire d'abord)

- **Fait sur le code et les gabarits**, pas en parcourant l'application : Docker Desktop n'a pas pu être relancé pendant l'audit, donc pas de parcours en direct avec données. Les constats chiffrés ci-dessous sont établis par lecture du code et, quand c'est indiqué, par un test existant.
- **Aucun utilisateur n'a été interrogé.** Les jugements « utile / inutile » reposent sur ce qu'un cabinet comptable fait au quotidien, pas sur des observations. Ce sont des hypothèses à confronter à 3–5 comptables (protocole en §7).
- Il n'existe **aucune mesure d'usage** (pages vues, clics) : on ne peut pas dire aujourd'hui quels écrans sont réellement utilisés.

## 1. Les personnes et leurs besoins réels

| Personne | Ce qu'elle doit pouvoir faire en moins de 10 s en ouvrant l'application |
|---|---|
| **Expert-comptable** (patron du cabinet) | Savoir quels dossiers sont en danger, qui est surchargé, quelles échéances approchent, où on perd du temps |
| **Collaborateur** (production) | Savoir quoi traiter maintenant sur ses dossiers, et le faire sans changer d'écran dix fois : pièces → écritures → banque → TVA → clôture |
| **Dirigeant / client** | Envoyer ses pièces, savoir ce qu'on lui demande, voir où il en est |
| **Opérateur de la plateforme** | Savoir si le système marche (disponibilité, erreurs, qualité de l'IA) |

Le point central de l'audit : **les écrans et indicateurs sont mélangés entre ces quatre publics.** Une partie de ce qui est montré au cabinet est destinée à l'éditeur du logiciel.

## 2. Verdict d'ensemble

| | |
|---|---|
| Cœur utile et solide | Pièces → écritures → rapprochement bancaire → TVA → dossier financier ; la Révision (« pourquoi ce dossier n'est pas prêt ») ; la file « À traiter » du Cabinet ; le dépôt de pièces par lien |
| Fausses assurances | Le **score** (100/100 « prêt » possible sur un dossier vide), le **score fiscal** (100 sans cadre fiscal vérifié), le **solde prévisionnel** de trésorerie (oublie les paiements fournisseurs), le **« chiffre d'affaires »** de la trésorerie |
| Bruit | Latence, P90, fallback, Automation Rate / Human Touch Rate dans la navigation du cabinet ; cinq vues différentes de « ce qu'il reste à faire » |
| Impasses | « Créer un compte » (fermé en production), « Générer échéancier TVA » (échoue toujours au Burkina), boutons « Simuler confirmation / échec », étape WhatsApp du premier démarrage |
| Hors cœur de métier | Facturation / devis / stocks / achats : un ERP de PME dans un outil de cabinet |

## 3. Votre exemple : la carte « Usage et qualité du Copilot »

Vous avez raison de ne pas lui faire confiance, pour **quatre raisons distinctes** :

1. **Pour qui ?** C'est un indicateur de **l'éditeur** (« la qualité de mon IA »), pas du comptable. Il est dans le menu du cabinet sous le nom « Indicateurs ». Aucun comptable ne prend une décision à partir de « latence moyenne 854 ms » : c'est une mesure d'exploitation (si elle dérive, l'**opérateur** doit être alerté, pas le cabinet). Idem « P90 », « fallback », « Automation Rate », « Human Touch Rate » (page `quality-dashboard.component.html`).
2. **Les deux pourcentages ne se complètent pas.** « Sans preuve (fallback) » = réponse dont la confiance vaut 0 (`CopilotService.java:320` : `confidence == 0.0`) ; « Avec preuve » = au moins une preuve citée. Une réponse qui a de la confiance **mais cite zéro preuve** n'est dans aucune des deux : d'où **0 % et 0 %** pour 1 question. Ce n'est pas un bug de calcul (`ProductKpiService.java:211-228` est arithmétiquement juste), c'est une **définition incohérente** : deux catégories qui ne couvrent pas tous les cas.
3. **La note de la page ment.** Elle affirme « jamais un pourcentage sur un échantillon vide ou trop petit pour être fiable ». Le code ne cache le pourcentage que si N = 0 ; **à N = 1, il affiche 0 %**. Aucun seuil minimal n'existe.
4. **Le Copilot lui-même apporte peu** : 8 intentions fixes reconnues par mots-clés (`IntentCopilot` : dossier pas prêt, bloquants de clôture, écart banque, pièces manquantes, anomalies fournisseurs, retards clients, évolution TVA, à traiter maintenant). Chacune est déjà affichée par un écran (Révision, Banque, Pièces, Recouvrement, TVA). Le modèle de langage ne fait que **reformuler** les faits (`CopilotExplainer`), et si l'IA est coupée il répond « Explication automatique indisponible ». Le vrai indicateur de valeur serait « combien de fois la réponse a évité d'ouvrir un autre écran », qui n'est pas mesuré.

## 4. Inventaire des indicateurs chiffrés : vrai, utile, trompeur ?

Légende : **GARDER** (utile et juste) · **REPENSER** (utile mais mal défini/mal présenté) · **DÉPLACER** (réservé à l'opérateur) · **SUPPRIMER** (sans valeur ou trompeur).

### 4.1 Cabinet

| Indicateur | Écran | Verdict | Pourquoi |
|---|---|---|---|
| Dossiers actifs / à traiter / en attente client / prêts pour revue / échéances proches | Cabinet | **GARDER** | Répond à « où en est mon portefeuille ». Les 5 compteurs sont actionnables |
| File « À traiter » (entreprise, problème, domaine, impact, ancienneté, responsable) | Cabinet | **GARDER** | Le meilleur écran d'entrée : priorisé, attribué, daté |
| Statut par dossier : étapes, pièces, banque, contrôles, TVA | Dossiers | **GARDER** | Vision multi-axes utile ; 11 colonnes, à réduire sur petit écran |
| Dossiers affectés / dernière activité par membre | Équipe | **GARDER** | Charge de travail et risque d'abandon |
| Taux d'automatisation, Human Touch Rate | Indicateurs | **DÉPLACER** | Mesure de produit. Vocabulaire anglais, sens obscur pour un comptable |
| Qualité du matching bancaire, de la comptabilisation (taux d'acceptation / rejet) | Indicateurs | **DÉPLACER** | Qualité de l'IA : utile à l'éditeur ; au cabinet, seul compte « combien me reste-t-il à valider » |
| Taux et délai moyen de résolution des pièces manquantes | Indicateurs | **GARDER** | Vrai indicateur de gestion : « combien de temps mes clients mettent à répondre » |
| Temps de résolution des constats : moyenne, médiane, **P90** | Indicateurs | **REPENSER** | Moyenne ou médiane suffit ; P90 est du jargon statistique sans décision associée |
| Usage et qualité du Copilot : fallback, avec preuve, **latence**, feedback | Indicateurs | **DÉPLACER** | Voir §3 |
| File de travail : constats ouverts / en retard, pièces en attente / en retard | Indicateurs | **GARDER** | Doublon de la file « À traiter » mais lisible ; à fusionner (§5) |
| Clients actifs / normaux / à surveiller / bloqués, « Analytics » | Cockpit | **SUPPRIMER** | Page **orpheline** (aucun lien ne mène à `/cabinet/cockpit`) et doublon du Cabinet |

### 4.2 Entreprise

| Indicateur | Écran | Verdict | Pourquoi |
|---|---|---|---|
| **Score global /100** et 7 scores par domaine | Tableau de bord | **REPENSER** | Voir §5.1 : pondérations arbitraires, absence de données = 100 |
| Statut « Clôture — … » | Tableau de bord | **GARDER** | Concret : bloquants et avertissements de clôture |
| Documents à comptabiliser ; en erreur d'extraction ; écritures en brouillon à valider | Tableau de bord | **GARDER** | Comptes d'objets à traiter, actionnables (renommer « extraction » en « lecture automatique ») |
| Solde de trésorerie « (classe 5) » | Tableau de bord | **GARDER** | Utile ; « classe 5 » est du jargon de plan comptable, écrire « Banque et caisse » |
| **Score fiscal /100** | Conformité | **SUPPRIMER** | Voir §5.2 : fausse assurance |
| Solde actuel, encaissements connus / attendus | Trésorerie | **GARDER** | Fonde une décision de trésorerie |
| **Décaissements connus**, **Solde prévisionnel** | Trésorerie | **REPENSER** | Voir §5.3 : n'incluent que les impôts, jamais les fournisseurs |
| **Chiffre d'affaires**, Charges, **Résultat**, Impôts/taxes (« Snapshot financier ») | Trésorerie | **REPENSER / SUPPRIMER** | Voir §5.3 : deuxième version des mêmes chiffres, calculée autrement que le compte de résultat |
| Solde dû, risque, action recommandée (par client) | Recouvrement | **REPENSER** | Utile au dirigeant ; le niveau de risque est un barème d'ancienneté, présenté comme une évaluation |
| TVA collectée / déductible / solde net / crédit / payé / reste à payer | TVA | **GARDER** | Cœur de métier ; **Burkina bloqué** tant que le cadre n'est pas vérifié (bandeau déjà affiché) |
| Balance, grand livre, journal, balance âgée, FEC, lettrage | Rapports | **GARDER** | Indispensables, norme du métier |
| Compte de résultat, bilan, TFT, notes, finalisation | États financiers | **GARDER** | Valeur maximale du produit |

## 5. Les constats les plus graves

### 5.1 Le score donne une fausse impression de santé
`DossierComptableService.java:46-60, 89-126` : chaque domaine part de 100 et perd des points fixes par constat ouvert (critique −25, important −10, à vérifier −3, facture client en retard −8, pièce fournisseur en attente −5). Le score global est la **moyenne des 7 domaines**. Le statut est « PRÊT » à partir de 95, « BLOQUÉ » sous 70.

- **Rien ne signifie 100.** Un dossier sans aucune écriture, sans relevé bancaire, sans pièce n'a aucun constat, donc **100/100 et « PRÊT »**. Un test existant le consacre : `DossierComptableServiceTest.java:109-123` (« dossier propre sans problème obtient un score parfait », avec zéro facture, zéro document).
- **Moyenne = dilution.** Quatre domaines à 100 masquent un domaine à 0 sur le score global (seule une constatation « critique » force « BLOQUÉ »).
- **Barème sans fondement.** 25 / 10 / 3 / 8 / 5 et les seuils 70 / 95 ne sont validés par aucune pratique de cabinet.

**À faire** : supprimer le score unique. Afficher des **états de complétude** (« Banque : relevés reçus pour 7 mois sur 12 », « Pièces : 41 écritures sur 60 justifiées », « Aucune donnée » quand c'est le cas) et la liste des bloquants, qui existe déjà dans la Révision.

### 5.2 « Score fiscal » : fausse assurance, surtout au Burkina
`ComplianceCenterService.java:59-64` : le score fiscal est le score du domaine « Fiscalité » (100 moins les pénalités des constats TAX_REVIEW), **par défaut 100**. Au Burkina, le cadre fiscal n'est pas vérifié : le produit **ne peut pas** évaluer la conformité fiscale, et affiche pourtant « 100 / 100 ». C'est exactement l'inverse de la règle posée pour le pilote (« TVA Burkina : cadre réglementaire non vérifié »).

**À faire** : afficher « Non évalué — cadre fiscal non vérifié » tant qu'aucun cadre vérifié ne s'applique ; ne jamais montrer de score.

### 5.3 Trésorerie : deux « vérités » et une prévision incomplète
- **Prévision** (`CashForecastService.java`, en-tête) : les « décaissements connus » ne contiennent que les **déclarations fiscales** ; les paiements fournisseurs sont **volontairement exclus** (la facture fournisseur n'a pas de date d'échéance). Le « Solde prévisionnel » est donc systématiquement **trop optimiste**, sans avertissement dans l'écran.
- **« Snapshot financier »** (`FinancialSnapshotService.java:64-77`) : « Chiffre d'affaires » = toutes les écritures de classe 7 (produits financiers, subventions, reprises compris), « Résultat » = classe 7 − classe 6 (sans les comptes HAO ni l'impôt sur le résultat). Ce n'est **ni le chiffre d'affaires, ni le résultat SYSCOHADA** que produit le Dossier financier : deux chiffres différents pour la même notion dans deux écrans.

**À faire** : renommer et avertir (« hors paiements fournisseurs »), ou retirer le bloc « Snapshot » au profit d'un lien vers le compte de résultat officiel.

### 5.4 Fiscalité : deux écrans, des impasses, des outils de test visibles
- Le menu propose **Fiscalité** (ancien module « Déclarations fiscales ») **et** l'**Espace TVA** (nouveau) **et** **Conformité** : trois entrées pour un seul sujet.
- Le bouton **« Générer échéancier TVA »** de l'ancien écran **échoue toujours au Burkina** (`DeclarationFiscaleService.java:80-90` : sans cadre vérifié, erreur 422 « Aucun échéancier n'est généré »). Une impasse sur un bouton principal.
- Les boutons **« Simuler confirmation » / « Simuler échec »** et « Payer en ligne » (mode simulation) sont **de l'outillage de test visible dans l'interface de production** : un utilisateur peut y « payer » un impôt fictif.

### 5.5 Le premier contact est cassé pour un cabinet réel
- La page de connexion propose **« Créer un compte »** ; en production la création est **réservée à l'opérateur** : le visiteur reçoit une erreur 403.
- L'assistant « Bienvenue » (régime fiscal, devise, pays) n'est atteint **qu'après** cette inscription ; il est donc **inaccessible** dans le parcours réel. Le formulaire « + Nouvelle entreprise » que l'expert utilise ne demande que **raison sociale et pays** : ni devise ni régime fiscal, alors que l'assistant dit que le régime « détermine le taux de TVA attendu » pour les contrôles.
- L'étape 2 de cet assistant explique **Meta Business Manager et un webhook WhatsApp** : tâche d'un administrateur technique, pas d'un comptable qui veut ouvrir son premier dossier.

**À faire** : retirer « Créer un compte » en production ; remplacer l'assistant par une **liste de démarrage dans l'application** (exercice, devise, régime fiscal, plan comptable à vérifier, équipe, premier relevé) ; reléguer WhatsApp dans « Paramètres du cabinet ».

### 5.6 Cinq vues de « ce qu'il reste à faire »
Le même objet (le constat / la tâche) est présenté par : **Cabinet — À traiter**, **Tableau de bord — Actions prioritaires**, **Révision**, **Cockpit — Top risques** (orphelin), **Indicateurs — File de travail**. Chaque vue a ses colonnes, ses mots et ses compteurs. L'utilisateur ne sait pas laquelle fait foi.
**À faire** : **une** file (celle du Cabinet, au niveau portefeuille) et **une** page de travail (la Révision, au niveau dossier). Supprimer le reste ou le transformer en simples liens.

### 5.7 Une navigation de 23 entrées, dont plusieurs hors du cœur de métier
Le menu d'une entreprise compte **23 liens**. Trois sont des **doublons** de fonction : Documents / Validation / Pièces demandées (+ Collecte) ; Rapprochement (deux écrans : « moderne » et « classique ») ; Rapports / États financiers / Dossier financier. **Facturation (devis, factures, avoirs), Stocks, Achats (bons de commande)** relèvent d'un **ERP de PME**, pas du travail d'un cabinet : le cabinet n'établit pas les factures de son client. Ils pèsent dans le menu, dans le code et dans la maintenance.
**À faire** : décision stratégique à prendre : soit ces modules sont un produit pour l'entreprise cliente (à présenter comme tel, activable par entreprise), soit ils sont hors périmètre. En attendant, **les masquer par défaut**.

### 5.8 Vocabulaire
Des termes anglais ou jargonneux dans l'interface d'un cabinet francophone : « Inbox de validation », « Copilot », « Automation Rate », « Human Touch Rate », « fallback », « Snapshot », « P90 », « classe 5 », « Workspace ». Un comptable ne dit pas « fallback ». Propositions : « Boîte de validation », « Assistant », « Part traitée automatiquement », « Part traitée sans intervention », « Instantané » (ou supprimer), etc.

## 6. Verdict écran par écran

| Écran | Public | Question métier traitée | Verdict |
|---|---|---|---|
| Cabinet (À traiter, compteurs) | Expert, collaborateur | Que dois-je traiter ? | **GARDER** (écran d'accueil de référence) |
| Dossiers (portefeuille) | Expert | Où en est chaque dossier, qui l'a ? | **GARDER** |
| Échéances | Expert | Qu'est-ce qui arrive ? | **GARDER** (saisie manuelle utile tant que le cadre fiscal est absent) |
| Équipe | Expert | Qui fait quoi, accès | **GARDER** |
| Indicateurs produit | Éditeur | Mon IA est-elle bonne ? | **DÉPLACER** vers un espace opérateur ; garder 2 cartes « gestion » dans le Cabinet |
| Cockpit (orphelin) | — | doublon | **SUPPRIMER** |
| Tableau de bord entreprise | Collaborateur | Où en est ce dossier ? | **REPENSER** (retirer le score, garder objets à traiter + clôture) |
| Révision | Collaborateur | Pourquoi pas prêt, que faire ? | **GARDER** (meilleur écran ; en faire la page de travail unique) |
| Documents (hub) | Collaborateur, client | Quelles pièces, lesquelles à valider ? | **GARDER** |
| Validation (boîte) | Collaborateur | Valider des pièces | **FUSIONNER** dans Documents (deux écrans « valider une pièce ») |
| Pièces demandées / Collecte | Collaborateur, client | Quelle pièce manque, relancer | **GARDER**, un seul vocabulaire |
| Dépôt public (lien) | Client | Envoyer une pièce sans compte | **GARDER** (grande valeur, simple) |
| Tiers | Collaborateur | Clients / fournisseurs | **GARDER** (import absent : à prévoir) |
| Plan comptable, Paramètres comptables | Expert | Configurer | **GARDER** (retirer « Tester la résolution » du premier plan) |
| Opérations, Écritures, Nouvelle écriture | Collaborateur | Saisir, valider | **GARDER** |
| Rapprochement bancaire (2 écrans), Banque | Collaborateur | Rapprocher | **FUSIONNER** les deux paradigmes |
| Rapports (balance, GL, journal, âgée, FEC, lettrage) | Collaborateur | Contrôler et exporter | **GARDER** |
| États financiers / Dossier financier | Expert | Produire les états | **GARDER** |
| Régularisations, Immobilisations | Collaborateur | Travaux de clôture | **GARDER** |
| TVA | Collaborateur, expert | Déclarer | **GARDER** (Burkina bloqué, bandeau clair) |
| Fiscalité (ancien) | — | doublon, impasses, simulations | **FUSIONNER** avec la TVA ; **MASQUER** les simulations |
| Conformité | Expert | « Score fiscal », obligations | **FUSIONNER** dans Fiscalité ; **SUPPRIMER** le score |
| Trésorerie | Dirigeant, expert | Combien aurai-je ? | **REPENSER** (§5.3) |
| Recouvrement | Dirigeant | Qui me doit ? | **REPENSER** (valeur réelle, présentation) |
| Facturation, Stocks, Achats | PME | Gérer ses ventes et stocks | **MASQUER** par défaut |
| Inviter | Expert | Donner accès à un client | **GARDER** (renommer « Accès client ») |
| Copilot (expert) / « Votre assistant » (dirigeant) | Tous | Poser une question | **REPENSER** : boutons « questions fréquentes » plutôt qu'une saisie libre à 8 intentions |
| Connexion / Créer un compte / Assistant de démarrage | Tous | Entrer | **REPENSER** (§5.5) |
| Notifications (cloche) | Tous | Être prévenu | **À ÉVALUER** (aucune mesure d'usage) |

## 7. Ce qui manque probablement (hypothèses à valider avec des comptables)

- **Piste d'audit visible** (qui a modifié quoi, quand) : elle existe côté serveur mais aucun écran ne la montre ; c'est une exigence naturelle du métier et de l'assurance qualité du cabinet.
- **Suivi du temps et des honoraires** du cabinet : aucun écran ; c'est souvent la première question du patron.
- **Reprise de l'existant** : import des tiers et des soldes d'ouverture absents (déjà noté en phase P0).
- **Vue client** : le dirigeant ne voit que ses pièces ; « où en est ma comptabilité ? » en trois lignes serait de la valeur directe.
- **Import bancaire** limité au CSV.

### Protocole de validation proposé (1 semaine)
1. Quatre entretiens de 30 min (2 experts, 2 collaborateurs) : leur faire ouvrir l'application et énoncer à voix haute « ce que je fais ici ». Noter ce qu'ils ignorent.
2. **Test des 5 secondes** sur l'écran Cabinet, le Tableau de bord et la Trésorerie : « que comprenez-vous ? ».
3. Poser un **compteur d'usage** par écran (nombre d'ouvertures par utilisateur et par semaine) pour décider des suppressions sur des faits.

## 8. Plan d'action priorisé

| # | Action | Effort | Gain |
|---|---|---|---|
| 1 | Retirer « Créer un compte » et masquer les boutons « Simuler… » / « Payer en ligne » en production | S | Supprime des impasses et un risque (paiement fictif) |
| 2 | Remplacer le **score fiscal** par « Non évalué — cadre fiscal non vérifié » (Burkina) ; retirer le score global ou le remplacer par des états de complétude | S–M | Fin de la fausse assurance |
| 3 | Sortir de la navigation du cabinet : latence, P90, fallback, Automation / Human Touch, matching (vers un espace opérateur) ; corriger la note qui promet un seuil d'échantillon ; imposer N minimal | S | Interface crédible, plus de « 0 % / 0 % » |
| 4 | Trésorerie : avertir « hors paiements fournisseurs » sur la prévision ; renommer ou supprimer le « Snapshot financier » | S | Fin de deux chiffres contradictoires |
| 5 | Supprimer la page Cockpit orpheline ; fusionner Fiscalité / Conformité / TVA ; fusionner Validation dans Documents ; fusionner les deux écrans de rapprochement | M | Navigation de 23 à ~14 entrées |
| 6 | Masquer Facturation / Stocks / Achats par défaut (activables par entreprise) | S | Recentrage sur le cœur de métier |
| 7 | Francisation du vocabulaire (§5.8) | S | Cohérence |
| 8 | Liste de démarrage dans l'application ; régime fiscal et devise demandés à la création de l'entreprise ; WhatsApp relégué aux paramètres | M | Premier dossier utilisable sans développeur |
| 9 | Copilot : « questions fréquentes » à un clic ; mesurer l'évitement d'écran plutôt que la latence | M | Valeur mesurable |
| 10 | Mesure d'usage par écran, puis tailler sur faits | M | Décisions non subjectives |

## 9. Preuves (références)

- Carte Copilot : `comptable/frontend/src/app/features/telemetry/quality-dashboard.component.html`, `comptable/src/main/java/com/example/comptable/telemetry/ProductKpiService.java:211-228`, `…/copilot/CopilotService.java:317-321`, `…/copilot/domain/IntentCopilot.java`, `…/copilot/CopilotExplainer.java`.
- Score : `…/dossier/DossierComptableService.java:46-60, 89-165` ; test `…/dossier/DossierComptableServiceTest.java:109-123`.
- Score fiscal : `…/fiscalite/conformite/ComplianceCenterService.java:59-64`.
- Trésorerie : `…/finance/CashForecastService.java` (en-tête), `…/finance/FinancialSnapshotService.java:64-77`.
- Fiscalité : `…/fiscalite/service/DeclarationFiscaleService.java:80-90`, `…/features/fiscalite/declaration-list.component.html`.
- Onboarding et connexion : `…/features/entreprises/onboarding-wizard.component.html`, `…/features/entreprises/entreprise-list.component.html`, `…/features/auth/login.component.html`.
- Navigation : `…/features/entreprises/entreprise-workspace.component.html`, `…/core/layout/shell.component.html`, `app.routes.ts` (route `cabinet/cockpit` sans lien entrant).

## 10. Suite donnée (2026-10-09) : actions 1 à 4 appliquées

| # | Action | Fait | Où |
|---|---|---|---|
| 1 | « Créer un compte » retiré en production ; paiement fictif et simulations masqués **et refusés par le serveur** | **Oui** | `GET /api/config` (public), `PaiementEnLigneGarde` (409 sur l'initiation tant qu'un fournisseur est simulé en prod ; 403 sur « simuler-confirmation » en prod), `login.component.html` (message « Accès sur invitation »), `declaration-list.component.html` |
| 2 | Score fiscal remplacé par « Non évalué » sans cadre vérifié ; score global retiré de l'écran | **Oui** | `TaxHealthDto(evalue, motif, anomaliesOuvertes)`, `ComplianceCenterService`, `DossierComptableDto.aucuneActivite` et `ScoreDomaineDto.elementsOuverts` (nombres réels à traiter par domaine, « Aucune activité enregistrée » sur un dossier vide ; le statut « Prêt » devient « Aucun point bloquant »). Le Copilot ne reçoit plus de score ni de statut « prêt » pour un dossier vide |
| 3 | Indicateurs de l'éditeur sortis de l'écran principal ; seuil minimal d'échantillon | **Oui (partiel)** | Page renommée « Suivi du cabinet » : travail en cours, réponse des clients, délai de traitement. Latence, P90, fallback, « Automation / Human Touch Rate » supprimés de l'affichage ; la qualité de l'assistant est repliée (« suivi technique ») et libellée en français. Pourcentage affiché seulement à partir de 10 cas (sinon « — » et les nombres réels). **Pas encore** d'espace réservé à un opérateur : l'application n'a pas de rôle opérateur ; la section repliée reste accessible aux membres du cabinet |
| 4 | Trésorerie : prévision incomplète signalée, « snapshot » corrigé | **Oui** | Avertissement « plafond optimiste », libellés « Impôts à payer (seuls décaissements connus) » et « Solde prévisionnel (hors fournisseurs et charges) » ; « Chiffre d'affaires / Résultat » renommés « Produits (classe 7) / Produits − charges (indicatif) » avec renvoi vers les États financiers |

Vérifié : tests backend ciblés (55), Karma (153), build de production. **Non vérifié dans un navigateur** (Docker Desktop arrêté pendant ce travail) : à contrôler visuellement au prochain démarrage de la pile.

Reste ouvert, hors des quatre actions : le bouton « Générer échéancier TVA » échoue toujours au Burkina (message d'erreur serveur) ; les actions 5 à 10 du §8.
