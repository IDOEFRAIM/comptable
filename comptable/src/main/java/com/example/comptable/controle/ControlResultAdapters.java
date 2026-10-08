package com.example.comptable.controle;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.fraude.AlerteFraude;
import com.example.comptable.agent.fraude.NiveauRisqueFraude;
import com.example.comptable.document.domain.Document;
import com.example.comptable.dossier.web.dto.ControlItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapters LECTURE SEULE vers {@link ControlResult} (Phase 1, lot 1.7) : les moteurs existants ne
 * sont pas reecrits, ils sont simplement lisibles dans un format commun.
 * <ul>
 *   <li>{@link Finding} (ControlEngine, MissingEvidenceAnalyzer, matching...) ;</li>
 *   <li>{@link ControlItem} (checklist de ClotureReadinessService) ;</li>
 *   <li>{@link AlerteFraude} / rapport de fraude d'un {@link Document} (AnalyseFraudeService).</li>
 * </ul>
 * Mapping de gravite Finding -> ControlResult : CRITICAL = BLOCKING, IMPORTANT = WARNING, REVIEW = INFO
 * (meme convention que ClotureReadinessService). Un Finding publie par {@link ControlResultPublisher}
 * conserve sa gravite d'origine dans {@code evidence.controlSeverity}, relue ici pour un aller-retour fidele.
 */
public final class ControlResultAdapters {

    public static final String META_RELATED = "relatedEntityIds";

    private ControlResultAdapters() {
    }

    public static ControlResult depuisFinding(Finding finding) {
        Map<String, Object> evidence = finding.getEvidenceJson() != null ? new HashMap<>(finding.getEvidenceJson()) : new HashMap<>();
        List<String> lies = evidence.get(META_RELATED) instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        evidence.remove(META_RELATED);
        ControlSeverity severite = finding.getControlSeverity();

        return new ControlResult(finding.getControlCode(), finding.getCategory(), severite, statutDe(finding.getStatus()),
                finding.getEntreprise().getId(), finding.getSourceType(), finding.getSourceId(), finding.getTitle(),
                finding.getDescription(), severite == ControlSeverity.BLOCKING, lies, evidence,
                finding.getRecommendedAction(), finding.getLastDetectedAt());
    }

    /** Un point de checklist OK ou NOT_APPLICABLE n'est pas un constat : {@code null}. */
    public static ControlResult depuisPointChecklist(UUID entrepriseId, ControlItem item) {
        ControlSeverity severite = switch (item.statut()) {
            case "BLOCKING" -> ControlSeverity.BLOCKING;
            case "WARNING" -> ControlSeverity.WARNING;
            default -> null;
        };
        if (severite == null) {
            return null;
        }
        Map<String, Object> metadata = new HashMap<>(item.metadata());
        if (item.evidence() != null) {
            metadata.putIfAbsent("evidence", item.evidence());
        }
        return ControlResult.ouvert(item.code(), ControlCategory.CLOSING, severite, entrepriseId,
                item.entityType() != null ? item.entityType() : "CLOTURE",
                item.entityId() != null ? item.entityId() : item.code(),
                item.message(), item.explanation() != null ? item.explanation() : item.message(),
                item.relatedEntityIds(), metadata, null);
    }

    public static List<ControlResult> depuisChecklist(UUID entrepriseId, List<ControlItem> checklist) {
        List<ControlResult> resultats = new ArrayList<>();
        for (ControlItem item : checklist) {
            ControlResult resultat = depuisPointChecklist(entrepriseId, item);
            if (resultat != null) {
                resultats.add(resultat);
            }
        }
        return resultats;
    }

    public static ControlResult depuisAlerteFraude(UUID entrepriseId, UUID documentId, AlerteFraude alerte) {
        return ControlResult.ouvert(alerte.code(), ControlCategory.ANOMALY, depuisNiveauFraude(alerte.gravite()),
                entrepriseId, "DOCUMENT", documentId.toString(), alerte.motif(), alerte.motif(), List.of(), Map.of(),
                alerte.actionRecommandee());
    }

    /** Alertes du rapport JSON persiste sur le document ({@code Document.rapportFraude}), lu defensivement. */
    public static List<ControlResult> depuisRapportFraude(Document document) {
        Map<String, Object> rapport = document.getRapportFraude();
        if (rapport == null || !(rapport.get("alertes") instanceof List<?> alertes)) {
            return List.of();
        }
        UUID entrepriseId = document.getEntreprise().getId();
        List<ControlResult> resultats = new ArrayList<>();
        for (Object brute : alertes) {
            if (!(brute instanceof Map<?, ?> alerte)) {
                continue;
            }
            resultats.add(depuisAlerteFraude(entrepriseId, document.getId(),
                    new AlerteFraude(String.valueOf(alerte.get("code")), String.valueOf(alerte.get("motif")),
                            parseNiveau(alerte.get("gravite")),
                            alerte.get("actionRecommandee") != null ? String.valueOf(alerte.get("actionRecommandee")) : null)));
        }
        return resultats;
    }

    // ------------------------------------------------------------------ mappings (visibles pour le publisher)

    static ControlSeverity depuisSeveriteFinding(SeveriteFinding severite) {
        return switch (severite) {
            case CRITICAL -> ControlSeverity.BLOCKING;
            case IMPORTANT -> ControlSeverity.WARNING;
            case REVIEW -> ControlSeverity.INFO;
        };
    }

    public static SeveriteFinding versSeveriteFinding(ControlSeverity severite) {
        return switch (severite) {
            case BLOCKING -> SeveriteFinding.CRITICAL;
            case ERROR, WARNING -> SeveriteFinding.IMPORTANT;
            case INFO -> SeveriteFinding.REVIEW;
        };
    }

    static ControlSeverity depuisNiveauFraude(NiveauRisqueFraude niveau) {
        // Une alerte de fraude, meme critique, n'empeche rien d'elle-meme : elle route vers la validation humaine.
        return switch (niveau) {
            case CRITIQUE -> ControlSeverity.ERROR;
            case MOYEN -> ControlSeverity.WARNING;
            case FAIBLE -> ControlSeverity.INFO;
        };
    }

    static ControlStatus statutDe(StatutFinding statut) {
        return switch (statut) {
            case OPEN, IN_PROGRESS -> ControlStatus.OPEN;
            case RESOLVED -> ControlStatus.RESOLVED;
            case DISMISSED -> ControlStatus.DISMISSED;
        };
    }

    private static NiveauRisqueFraude parseNiveau(Object brut) {
        try {
            return NiveauRisqueFraude.valueOf(String.valueOf(brut));
        } catch (IllegalArgumentException e) {
            return NiveauRisqueFraude.MOYEN;
        }
    }
}
