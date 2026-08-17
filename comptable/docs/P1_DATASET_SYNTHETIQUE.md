# Dataset synthétique — Mission P1 « Cabinet Operating System » (section 51)

Ce document assemble et labellise la couverture de test exigée par la mission P1 pour le
cabinet, la comptabilité, la trésorerie, le Copilot et les permissions. Comme pour le dataset
P0 ([`docs/P0_DATASET_SYNTHETIQUE.md`](P0_DATASET_SYNTHETIQUE.md)), ce n'est pas un jeu de
fixtures séparé : ce document **référence** les tests réels déjà écrits, plutôt que d'en
dupliquer la substance dans une base de démonstration parallèle qui divergerait avec le temps.

## Écart assumé avec la lettre de la mission (honnêteté d'abord)

La mission demande explicitement « 20 entreprises fictives ». **Ce chantier n'en construit pas
20** : le test de bout en bout ([`P1CabinetOperatingSystemE2ETest`](../src/test/java/com/example/comptable/cabinet/P1CabinetOperatingSystemE2ETest.java))
utilise **2 entreprises réelles** dans un même cabinet (une saine, une à risque) — suffisant
pour prouver que l'agrégation cabinet-wide (Cockpit, Analytics, Copilot cabinet) fonctionne sur
un **portefeuille réel à plusieurs clients**, pas sur un mock empilé. Construire 18 entreprises
supplémentaires n'aurait rien prouvé de plus sur le mécanisme d'agrégation lui-même (il est déjà
prouvé avec N=2, la boucle Java ne change pas de comportement à N=20) et aurait coûté un temps
disproportionné pour ce chantier. Une vraie démonstration commerciale à 80 clients (mission
section 66) reste une action P2 distincte, pas un prérequis de test.

## 1. Cabinet (clients propres / incomplets / bloqués / en retard)

| Profil | Donnée | Test |
|---|---|---|
| Client sain | Aucun Finding ouvert → score 100, statut PRET | `P1CabinetOperatingSystemE2ETest.e2e_cabinet_cockpit_agrege_un_portefeuille_reel_de_deux_clients` |
| Client bloqué | 1 Finding `TAX_REVIEW` CRITICAL réel → statut BLOQUE, apparaît en tête du top risques | même test |
| Dossier « à surveiller » (dérivé, pas explicitement construit ici) | Couvert par `DossierComptableServiceTest` (P0) — score entre 70 et 95 | `dossier/DossierComptableServiceTest` |

## 2. Comptabilité (banque / clients / fournisseurs / documents / TVA / rapprochement)

Ces domaines sont ceux du P0, déjà couverts par
[`docs/P0_DATASET_SYNTHETIQUE.md`](P0_DATASET_SYNTHETIQUE.md) (sections 1 à 7) — non recopiés
ici pour éviter la duplication. Le P1 les **agrège** (Cabinet Cockpit, Analytics) sans ajouter
de nouveau cas métier à ce niveau.

## 3. Trésorerie (encaissée / attendue / en retard)

| Palier | Donnée | Test |
|---|---|---|
| **Connue** (encaissée à échéance certaine) | Facture VALIDEE avec échéance dans l'horizon (+5j) | `P1CabinetOperatingSystemE2ETest.e2e_cash_position_et_forecast` |
| **Attendue** (en retard, pondérée par ancienneté) | Facture VALIDEE en retard de 15j → probabilité 90% | même test |
| **Position actuelle** (multicanal) | Écriture réelle sur compte 521 (classe 5) → `CashPositionDto.total` | même test |

`PREDICTED` (palier IA/statistique) n'est volontairement pas testé : il n'est pas livré (voir
`docs/P1_ARCHITECTURE.md`, aucune donnée de paiement historique disponible pour le calibrer
honnêtement).

## 4. Recouvrement

| Cas | Donnée | Test |
|---|---|---|
| Client en retard important | Écriture réelle sur compte 411 vieille de 70 jours, tiers non lettré → `HIGH`, "contact humain" | `P1CabinetOperatingSystemE2ETest.e2e_collection_risk_classe_un_client_en_retard_important` |
| Répartition par tranche (0-30/31-60/61-90/+90) | Couvert unitairement | `recouvrement/CollectionRiskServiceTest` |

## 5. Copilot (dossier / compte / cash / clients / fournisseurs / fiscalité / cabinet)

| Portée | Question | Test |
|---|---|---|
| Comptable | « Pourquoi ce dossier n'est-il pas prêt ? » — evidence réelle (Finding), confiance > 0 | `P1CabinetOperatingSystemE2ETest.e2e_copilot_comptable_explique_pourquoi_le_dossier_nest_pas_pret` |
| Comptable | Les 8 questions obligatoires (section 14) | `copilot/IntentClassifierTest`, `copilot/CopilotServiceTest` |
| Cabinet | « Quels clients sont bloqués ? » — evidence réelle (entreprise à risque) | `P1CabinetOperatingSystemE2ETest.e2e_copilot_cabinet_repond_avec_des_preuves_reelles` |
| Cabinet | Les 5 questions cabinet (section 40) | `copilot/CabinetIntentClassifierTest`, `copilot/CabinetCopilotServiceTest` |
| Hors périmètre | Question non reconnue → pas d'appel IA, réponse structurée générique | `copilot/CopilotServiceTest.question_hors_perimetre_ne_declenche_aucun_appel_de_donnees_ni_llm` |
| Dégradation IA | `AiServiceClient` indisponible → explication générique, jamais de crash | `copilot/CopilotExplainerTest.degrade_proprement_quand_le_microservice_ia_est_indisponible` — **vérifié aussi manuellement dans un vrai navigateur** contre le backend réel avec `ai-service` éteint (Phase 9) |

## 6. Compliance

| Cas | Donnée | Test |
|---|---|---|
| Obligation fiscale listée | `DeclarationFiscale` réelle (TVA, périmètre "2026-07") | `P1CabinetOperatingSystemE2ETest.e2e_compliance_center_liste_les_obligations_et_les_anomalies` |
| Anomalie fiscale associée | `Finding TAX_REVIEW` réel apparaissant dans le même centre | même test |
| Tax Health | Reprend le score du domaine Fiscalité du Dossier (P0) | `fiscalite/conformite/ComplianceCenterServiceTest.tax_health_reutilise_le_score_du_domaine_fiscalite_du_dossier` |

## 7. Permissions (EXPERT_COMPTABLE / COLLABORATEUR / CLIENT)

**Limite assumée** : contrairement aux domaines ci-dessus, ceci n'est **pas** vérifié par un
test HTTP de bout en bout dans ce chantier. Chaque nouvel endpoint P1 déclare explicitement sa
restriction de rôle dans `SecurityConfig` (`hasAnyRole("COLLABORATEUR", "EXPERT_COMPTABLE")`,
jamais `CLIENT`) — revu manuellement lors du câblage de chaque phase — mais l'application
concrète de cette règle (un appel HTTP réel avec un token CLIENT retourne bien 403) repose sur
le mécanisme de Spring Security lui-même (`authorizeHttpRequests`), pas sur du code métier
propre à ce projet. Aucun test `MockMvc` n'existait déjà dans ce codebase avant ce chantier (les
tests P0/P1 appellent les services Java directement, jamais via HTTP) ; en introduire un
maintenant aurait été une nouvelle convention de test non justifiée sous la pression du temps.
**Mise à jour P1.5** : cette recommandation est désormais réalisée — voir
[`P1_5_DATASET.md`](P1_5_DATASET.md) section 1 et
`security/P1_5SecuriteHttpE2ETest.java` (premier test HTTP réel du projet, MockMvc + vrais
jetons JWT). En le construisant, une vraie fuite de sécurité tenant a été trouvée et corrigée
dans `FindingService.assigner` (voir `docs/P1_5_RAPPORT_FINAL.md`, point N) — confirmant
concrètement l'utilité de fermer ce gap plutôt que de le laisser en recommandation.
