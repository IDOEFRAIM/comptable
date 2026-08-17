# Dataset synthétique — Mission P0 « Core Intelligent » (section 31)

Ce document assemble et labellise le dataset synthétique exigé par la mission P0 : pour
chaque domaine (Banque, Mobile Money, Documents, Clients/Fournisseurs, Justificatifs,
Fiscalité, Clôture), un **cas propre** et un **cas à problème**, avec les données exactes
utilisées et la référence du test qui les exerce réellement contre du code réel (Mockito
uniquement sur le seul point d'entrée IA, `AiServiceClient` — jamais sur la logique métier
Java elle-même).

Il ne s'agit pas d'un nouveau jeu de fixtures : conformément à la consigne « ne pas
refactorer/dupliquer sans raison », ce document **référence** les tests unitaires et
d'intégration déjà écrits au fil des phases 1 à 8, plutôt que de recréer une base de
données de démonstration séparée qui ferait double emploi et divergerait avec le temps.
Tous les chemins sont relatifs à `comptable/src/test/java`.

## 1. Banque (rapprochement)

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Ligne de relevé dont le montant correspond exactement à une écriture existante | `agent/matching/MatchDecisionEngineTest.cas1_transaction_vers_ecriture_exacte_declenche_match_auto` → `MATCH_AUTO` |
| **Propre** | Import CSV avec lignes qui se rapprochent automatiquement | `comptabilite/service/RapprochementBancaireServiceTest.importe_un_csv_et_rapproche_automatiquement_les_lignes_correspondantes` |
| **Problème** | Aucun candidat en base pour la ligne importée | `agent/matching/MatchDecisionEngineTest.cas4_aucun_candidat_donne_no_match` → `NO_MATCH` |
| **Problème** | Deux candidats plausibles, aucun ne se détache | `agent/matching/MatchDecisionEngineTest.cas5_deux_candidats_ambigus_necessitent_une_revue` → `MATCH_REVIEW` |
| **Problème** | Groupe `ONE_TO_MANY` dont la somme des candidats ne correspond pas au montant source (garde-fou anti-hallucination trouvé et corrigé en Phase 2) | `agent/matching/MatchDecisionEngineTest.casD_somme_dun_groupe_one_to_many_incoherente_est_signalee` |
| **Problème** | IA indisponible au moment du rapprochement | `comptabilite/service/RapprochementBancaireServiceMatchingIaTest.laisse_la_ligne_non_rapprochee_quand_lia_est_indisponible` |

## 2. Mobile Money

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Transaction Mobile Money vers une écriture exacte | `agent/matching/MatchDecisionEngineTest.cas12_transaction_mobile_money_vers_ecriture_exacte` → `MATCH_AUTO` |
| **Problème** | *(gap honnête)* — aucun cas Mobile-Money-spécifique à problème n'existe séparément : Mobile Money entre dans le Universal Matching Engine par le même `SourceType` que la banque, donc tous les cas « Problème » de la section 1 (aucun candidat, ambiguïté, groupe incohérent) s'appliquent identiquement. Ne pas dupliquer ces tests pour Mobile Money serait une redondance, pas une lacune de couverture. |

## 3. Documents (extraction / fraude)

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Document extrait sans incohérence détectée, reste `TRAITE` | `document/service/DocumentServiceTest.reste_traite_quand_lanalyse_de_fraude_ne_releve_rien` |
| **Propre** | Aucune alerte quand montants/TVA sont cohérents | `agent/fraude/AnalyseFraudeServiceTest.aucune_alerte_quand_tout_est_coherent` |
| **Problème** | Score de fraude critique → bascule en validation humaine | `document/service/DocumentServiceTest.bascule_en_attente_de_validation_humaine_quand_le_score_de_fraude_est_critique` |
| **Problème** | Écart de TVA grossier, montant rond suspect, doublon strict | `agent/fraude/AnalyseFraudeServiceTest.signale_un_ecart_de_tva_grossier` / `.signale_un_montant_rond_suspect_sur_une_facture_dachat` / `.signale_un_doublon_strict_avec_une_gravite_critique` |

## 4. Clients / Fournisseurs

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Aucune facture en retard, aucune pièce fournisseur en attente → score 100 | `dossier/DossierComptableServiceTest.dossier_propre_sans_probleme_obtient_un_score_parfait` |
| **Problème** | 3 factures clients en retard d'échéance → score Clients dégradé à 76 | `dossier/DossierComptableServiceTest.les_factures_clients_en_retard_degradent_le_score_clients` |

> Limite assumée (documentée dans le plan d'implémentation) : Clients/Fournisseurs ne sont
> **pas** basés sur `Finding` comme les 5 autres domaines, mais sur des compteurs directs
> (factures en retard, pièces fournisseur non liées à une écriture) — un choix P0 pour
> éviter de créer des `Finding` de faible valeur informative sur des compteurs déjà simples
> à lire directement.

## 5. Justificatifs manquants

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Candidats déjà examinés mais ambigus → pas de nouvelle demande dupliquée | `agent/finding/WhatsAppResolutionServiceTest.des_candidats_examines_mais_ambigus_ne_declenche_aucune_demande` |
| **Problème** | Aucun candidat examiné → demande ciblée déclenchée automatiquement | `agent/finding/WhatsAppResolutionServiceTest.aucun_candidat_examine_declenche_une_demande_ciblee` |
| **Chaîne complète** | Ligne bancaire réellement sans justificatif → `Finding MISSING_EVIDENCE` + `DemandePiece` réels en base, puis résolution après preuve fournie | `dossier/P0CoreIntelligentE2ETest.chaine_complete_document_banque_finding_demande_puis_resolution` (voir section 8) |

> `MissingEvidenceAnalyzer` lui-même n'a pas de fichier de test unitaire dédié — il est
> volontairement fin (un aiguillage sur la décision déjà produite et validée par
> `MatchDecisionEngine`) et sa couverture réelle vient de `RapprochementBancaireServiceTest`
> / `RapprochementBancaireServiceMatchingIaTest` (qui l'exercent en contexte) et surtout du
> test de bout en bout, qui est la preuve la plus forte : Postgres réel, pas de mock sur la
> logique métier.

## 6. Fiscalité

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Recalcul TVA depuis des écritures validées | `fiscalite/service/DeclarationFiscaleServiceTest.recalcule_le_montant_tva_depuis_les_ecritures_validees` |
| **Problème** | Refus de recalcul sur une déclaration déjà payée / sur un autre impôt que la TVA | `fiscalite/service/DeclarationFiscaleServiceTest.refuse_le_recalcul_dune_declaration_deja_payee` / `.refuse_le_recalcul_pour_un_type_dimpot_different_de_tva` |

> `ControlEngine.analyserDeclarationsFiscales` (le vrai détecteur « période TVA manquante »,
> Phase 2, corrigé en Phase 8 pour ignorer les périodes antérieures à l'entrée du dossier
> dans le cabinet — voir le bug trouvé par le test de bout en bout ci-dessous) n'a pas de
> fichier de test unitaire dédié ; il est exercé indirectement (mocké) dans
> `DossierComptableServiceTest` et réellement, contre une entreprise fraîchement créée, par
> le test de bout en bout.

## 7. Clôture

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Aucune écriture brouillon, aucun `Finding` ouvert → `READY` | `dossier/ClotureReadinessServiceTest.dossier_propre_est_pret` |
| **Problème (bloquant)** | Écritures `BROUILLON` sur l'exercice ouvert, ou `Finding` `CRITICAL` ouvert, ou aucun exercice ne couvre la date du jour | `dossier/ClotureReadinessServiceTest.ecritures_brouillon_bloquent_la_cloture` / `.finding_critique_bloque_la_cloture` / `.aucun_exercice_ne_couvre_la_date_du_jour_bloque_la_cloture` |
| **Problème (avertissement)** | Seulement des `Finding` `IMPORTANT` ouverts | `dossier/ClotureReadinessServiceTest.finding_important_seul_donne_ready_with_review` → `READY_WITH_REVIEW` |

## 8. Comptabilisation automatique (transversal)

| Cas | Donnée | Test |
|---|---|---|
| **Propre** | Tiers identifié et historique de compte cohérent (≥ 3 occurrences) → éligible niveau 3 (comptabilisation réelle) | `agent/comptabilisation/AccountingProposalBuilderTest.tiers_et_compte_avec_historique_coherent_est_eligible_niveau3` |
| **Problème** | Aucun tiers identifiable → confiance nulle, aucune proposition exploitable | `agent/comptabilisation/AccountingProposalBuilderTest.aucun_tiers_identifiable_donne_une_confiance_nulle` |
| **Intermédiaire** | Tiers identifié mais historique de compte insuffisant → proposition non éligible au niveau 3, reste une suggestion à valider | `agent/comptabilisation/AccountingProposalBuilderTest.tiers_identifie_mais_historique_de_compte_insuffisant_nest_pas_eligible_niveau3` |

## 9. Scénario de bout en bout (section 32)

`dossier/P0CoreIntelligentE2ETest.chaine_complete_document_banque_finding_demande_puis_resolution`
enchaîne réellement, contre Postgres réel (seul `AiServiceClient` est mocké — `AI_PROVIDER`
n'a même pas besoin d'être positionné, aucune clé Anthropic requise), la chaîne complète :

1. Import d'un relevé bancaire avec une ligne totalement inexpliquée (aucun candidat réel en base).
2. `Finding MISSING_EVIDENCE` réel créé, `DemandePiece` ciblée réelle créée et liée (`finding_id`).
3. Dossier dégradé (score Banque < 100), Closing Readiness `READY_WITH_REVIEW`/`NOT_READY`.
4. La preuve arrive (écriture bancaire réelle créée, simulant la réponse du client).
5. Rematching réel : le candidat existe désormais, `MATCH_AUTO`.
6. Le `Finding` se résout automatiquement (`resoudreParSource`).
7. Dossier régénéré : score Banque revenu exactement à 100.

C'est la preuve la plus forte du chantier P0 : chaque étage (import, matching, findings,
demandes, dossier, clôture) fonctionne ensemble, avec les vraies classes Spring, la vraie
base de données, et pas seulement en isolation via des mocks.

## Bug réel trouvé par ce dataset

En construisant le scénario ci-dessus contre une entreprise **fraîchement créée le jour du
test**, `ControlEngine.analyserDeclarationsFiscales` remontait des `Finding TAX_REVIEW` pour
des périodes TVA passées **avant même la création du dossier** — un faux-positif de
bienvenue qui aurait dégradé la confiance du comptable dans le système dès le premier
onboarding. Corrigé en excluant les périodes dont l'échéance précède `entreprise.createdAt`
(`agent/finding/ControlEngine.java`). C'est exactement le type de défaut qu'un test unitaire
isolé (avec une entreprise mockée sans date de création réaliste) n'aurait pas révélé.
