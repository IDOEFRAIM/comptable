package com.example.comptable.controle;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.fraude.AlerteFraude;
import com.example.comptable.agent.fraude.NiveauRisqueFraude;
import com.example.comptable.document.domain.Document;
import com.example.comptable.dossier.web.dto.ControlItem;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 1, lots 1.6/1.7 : contrat commun, adapters (lecture seule) et pont vers Finding. */
class ControlResultTest {

    private final UUID entrepriseId = UUID.randomUUID();

    private Entreprise entreprise() {
        Entreprise e = new Entreprise();
        e.setId(entrepriseId);
        return e;
    }

    private Finding finding(TypeFinding type, SeveriteFinding severite, StatutFinding statut, Map<String, Object> evidence) {
        Finding f = new Finding();
        f.setEntreprise(entreprise());
        f.setType(type);
        f.setSeverity(severite);
        f.setStatus(statut);
        f.setSourceType("DOCUMENT");
        f.setSourceId("doc-1");
        f.setTitle("Titre");
        f.setDescription("Description chiffree");
        f.setRecommendedAction("Verifier");
        f.setEvidenceJson(evidence);
        f.setControlCode(type.name());
        f.setCategory(switch (type) {
            case MISSING_EVIDENCE, DOCUMENT_REVIEW -> ControlCategory.DOCUMENT;
            case TAX_REVIEW -> ControlCategory.TAX;
            case BANK_DIFFERENCE, MATCH_AMBIGUOUS, LETTRAGE_AMBIGU -> ControlCategory.RECONCILIATION;
            case CLOSING_BLOCKER -> ControlCategory.CLOSING;
            default -> ControlCategory.ACCOUNTING;
        });
        f.setControlSeverity(switch (severite) {
            case CRITICAL -> ControlSeverity.BLOCKING;
            case IMPORTANT -> ControlSeverity.WARNING;
            case REVIEW -> ControlSeverity.INFO;
        });
        f.setLastDetectedAt(java.time.OffsetDateTime.now());
        return f;
    }

    @Test
    void blocking_est_derive_de_la_gravite() {
        ControlResult r = ControlResult.ouvert("X", ControlCategory.ACCOUNTING, ControlSeverity.BLOCKING, entrepriseId,
                "EXERCICE", "e1", "m", "e", null, null, null);
        ControlResult w = ControlResult.ouvert("X", ControlCategory.ACCOUNTING, ControlSeverity.WARNING, entrepriseId,
                "EXERCICE", "e1", "m", "e", null, null, null);

        assertThat(r.blocking()).isTrue();
        assertThat(w.blocking()).isFalse();
        assertThat(r.status()).isEqualTo(ControlStatus.OPEN);
        assertThat(r.relatedEntityIds()).isEmpty();
        assertThat(r.metadata()).isEmpty();
        assertThat(r.detectedAt()).isNotNull();
    }

    @Test
    void finding_est_adapte_avec_la_convention_CRITICAL_BLOCKING_IMPORTANT_WARNING_REVIEW_INFO() {
        assertThat(ControlResultAdapters.depuisFinding(finding(TypeFinding.ANOMALY, SeveriteFinding.CRITICAL, StatutFinding.OPEN, Map.of())).severity())
                .isEqualTo(ControlSeverity.BLOCKING);
        assertThat(ControlResultAdapters.depuisFinding(finding(TypeFinding.ANOMALY, SeveriteFinding.IMPORTANT, StatutFinding.OPEN, Map.of())).severity())
                .isEqualTo(ControlSeverity.WARNING);
        assertThat(ControlResultAdapters.depuisFinding(finding(TypeFinding.ANOMALY, SeveriteFinding.REVIEW, StatutFinding.OPEN, Map.of())).severity())
                .isEqualTo(ControlSeverity.INFO);
    }

    @Test
    void finding_statut_et_categorie_sont_traduits() {
        ControlResult r = ControlResultAdapters.depuisFinding(
                finding(TypeFinding.MISSING_EVIDENCE, SeveriteFinding.IMPORTANT, StatutFinding.IN_PROGRESS, Map.of("montant", 100)));

        assertThat(r.status()).isEqualTo(ControlStatus.OPEN);
        assertThat(r.category()).isEqualTo(ControlCategory.DOCUMENT);
        assertThat(r.code()).isEqualTo("MISSING_EVIDENCE");
        assertThat(r.entrepriseId()).isEqualTo(entrepriseId);
        assertThat(r.entityType()).isEqualTo("DOCUMENT");
        assertThat(r.entityId()).isEqualTo("doc-1");
        assertThat(r.explanation()).isEqualTo("Description chiffree");
        assertThat(r.suggestedAction()).isEqualTo("Verifier");
        assertThat(r.metadata()).containsEntry("montant", 100);
        assertThat(ControlResultAdapters.depuisFinding(
                finding(TypeFinding.TAX_REVIEW, SeveriteFinding.REVIEW, StatutFinding.DISMISSED, Map.of())).status())
                .isEqualTo(ControlStatus.DISMISSED);
        assertThat(ControlResultAdapters.depuisFinding(
                finding(TypeFinding.BANK_DIFFERENCE, SeveriteFinding.REVIEW, StatutFinding.RESOLVED, Map.of())).category())
                .isEqualTo(ControlCategory.RECONCILIATION);
    }

    @Test
    void publier_delegue_la_declaration_au_FindingService_avec_le_code_et_la_gravite_d_origine() {
        FindingService findingService = mock(FindingService.class);
        ControlResultPublisher publisher = new ControlResultPublisher(findingService);
        ControlResult resultat = ControlResult.ouvert("BANK_RECONCILIATION_INCOMPLETE", ControlCategory.RECONCILIATION, ControlSeverity.ERROR,
                entrepriseId, "RELEVE", "releve-1", "1 ligne non pointee", "124 500 FCFA non rapproches",
                List.of("ligne-9"), Map.of("montantNonPointe", 124500), "Pointer la ligne");
        Finding attendu = finding(TypeFinding.BANK_DIFFERENCE, SeveriteFinding.IMPORTANT, StatutFinding.OPEN, Map.of());
        org.mockito.Mockito.when(findingService.declarer(any(Entreprise.class), eq(resultat), eq(TypeFinding.BANK_DIFFERENCE)))
                .thenReturn(new FindingService.Declaration(attendu, FindingService.Effet.CREE));

        assertThat(publisher.publier(entreprise(), resultat, TypeFinding.BANK_DIFFERENCE)).isSameAs(attendu);
    }

    @Test
    void finding_relu_conserve_code_gravite_et_elements_lies() {
        Map<String, Object> evidence = new java.util.HashMap<>(Map.of("montantNonPointe", 124500));
        evidence.put(ControlResultAdapters.META_RELATED, List.of("ligne-9"));
        Finding finding = finding(TypeFinding.BANK_DIFFERENCE, SeveriteFinding.IMPORTANT, StatutFinding.OPEN, evidence);
        finding.setControlCode("BANK_RECONCILIATION_INCOMPLETE");
        finding.setControlSeverity(ControlSeverity.ERROR);
        ControlResult relu = ControlResultAdapters.depuisFinding(finding);

        assertThat(relu.severity()).isEqualTo(ControlSeverity.ERROR);
        assertThat(relu.code()).isEqualTo("BANK_RECONCILIATION_INCOMPLETE");
        assertThat(relu.relatedEntityIds()).containsExactly("ligne-9");
        assertThat(relu.metadata()).containsEntry("montantNonPointe", 124500).doesNotContainKey(ControlResultAdapters.META_RELATED);
    }

    @Test
    void publier_ignore_un_constat_non_ouvert() {
        FindingService findingService = mock(FindingService.class);
        ControlResult resolu = new ControlResult("X", ControlCategory.ACCOUNTING, ControlSeverity.WARNING, ControlStatus.RESOLVED,
                entrepriseId, "DOCUMENT", "d", "m", "e", false, null, null, null, null);

        assertThat(new ControlResultPublisher(findingService).publier(entreprise(), resolu, TypeFinding.ANOMALY)).isNull();
        org.mockito.Mockito.verifyNoInteractions(findingService);
    }

    @Test
    void deux_controles_sur_le_meme_objet_ont_deux_cles_de_deduplication_distinctes() {
        ControlResult a = ControlResult.ouvert("A", ControlCategory.DOCUMENT, ControlSeverity.WARNING, entrepriseId, "DOCUMENT", "d1", "m", "e", null, null, null);
        ControlResult b = ControlResult.ouvert("B", ControlCategory.DOCUMENT, ControlSeverity.WARNING, entrepriseId, "DOCUMENT", "d1", "m", "e", null, null, null);

        assertThat(a.sourceKey()).isNotEqualTo(b.sourceKey());
    }

    @Test
    void alerte_de_fraude_critique_est_une_erreur_non_bloquante() {
        UUID documentId = UUID.randomUUID();
        ControlResult r = ControlResultAdapters.depuisAlerteFraude(entrepriseId, documentId,
                new AlerteFraude("DOUBLON_POTENTIEL", "Meme montant deja enregistre", NiveauRisqueFraude.CRITIQUE, "Comparer"));

        assertThat(r.severity()).isEqualTo(ControlSeverity.ERROR);
        assertThat(r.blocking()).isFalse();
        assertThat(r.code()).isEqualTo("DOUBLON_POTENTIEL");
        assertThat(r.entityType()).isEqualTo("DOCUMENT");
        assertThat(r.entityId()).isEqualTo(documentId.toString());
        assertThat(r.suggestedAction()).isEqualTo("Comparer");
    }

    @Test
    void rapport_de_fraude_json_du_document_est_lu_defensivement() {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setEntreprise(entreprise());
        document.setRapportFraude(Map.of("alertes", List.of(
                Map.of("code", "ECART_TVA", "motif", "TVA incoherente", "gravite", "MOYEN", "actionRecommandee", "Verifier"),
                Map.of("code", "AUTRE", "motif", "m", "gravite", "INCONNU"))));

        List<ControlResult> resultats = ControlResultAdapters.depuisRapportFraude(document);

        assertThat(resultats).hasSize(2);
        assertThat(resultats.get(0).severity()).isEqualTo(ControlSeverity.WARNING);
        assertThat(resultats.get(1).severity()).isEqualTo(ControlSeverity.WARNING); // gravite inconnue -> MOYEN
        document.setRapportFraude(null);
        assertThat(ControlResultAdapters.depuisRapportFraude(document)).isEmpty();
    }

    @Test
    void checklist_seuls_les_points_en_anomalie_deviennent_des_constats() {
        List<ControlItem> checklist = List.of(
                new ControlItem("brouillons", "Brouillons", "BLOCKING", "CRITICAL", "3 brouillons", "3"),
                new ControlItem("regularisations", "Regul", "WARNING", "IMPORTANT", "1 regul", "1"),
                new ControlItem("exercices", "Exercice", "OK", null, "ok", null),
                new ControlItem("caisse", "Caisse", "NOT_APPLICABLE", null, "n/a", null));

        List<ControlResult> resultats = ControlResultAdapters.depuisChecklist(entrepriseId, checklist);

        assertThat(resultats).extracting(ControlResult::code).containsExactly("brouillons", "regularisations");
        assertThat(resultats.get(0).blocking()).isTrue();
        assertThat(resultats.get(0).category()).isEqualTo(ControlCategory.CLOSING);
        assertThat(resultats.get(1).blocking()).isFalse();
        assertThat(resultats.get(0).metadata()).containsEntry("evidence", "3");
    }

    @Test
    void point_de_checklist_detaille_conserve_explication_et_objets_lies() {
        ControlItem item = ControlItem.detaille("banque", "Rapprochement bancaire", "WARNING", "IMPORTANT", "1 ligne non pointee", null,
                "RELEVE", "releve-1", "Compte 521 : ecart de 124 500 FCFA", List.of("ligne-1"), Map.of("ecart", 124500));

        ControlResult r = ControlResultAdapters.depuisPointChecklist(entrepriseId, item);

        assertThat(r.entityType()).isEqualTo("RELEVE");
        assertThat(r.entityId()).isEqualTo("releve-1");
        assertThat(r.explanation()).contains("124 500");
        assertThat(r.relatedEntityIds()).containsExactly("ligne-1");
        assertThat(r.metadata()).containsEntry("ecart", 124500);
    }
}
