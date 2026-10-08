package com.example.comptable.controle.revue;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.controle.ControlResultAdapters;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Formes de reponse de la revue comptable. */
public final class RevueDto {

    private RevueDto() {
    }

    public record TuileDto(String code, String libelle, long nombre) {
    }

    /** @param bloquantPourCloture au moins un constat actif est BLOCKING */
    public record Synthese(long totalActifs, boolean bloquantPourCloture, List<TuileDto> tuiles,
                           Map<String, Long> parSeverite, Map<String, Long> parCategorie,
                           OffsetDateTime derniereExecution) {
    }

    public record Execution(int detectes, int crees, int misAJour, int resolus) {
    }

    /**
     * Une ligne de revue = une decision humaine possible. Explicabilite : {@code message} (quoi),
     * {@code entityType}/{@code entityId} (quel objet), {@code explanation} (pourquoi),
     * {@code evidence} (quelles valeurs), {@code suggestedAction} (quelle action).
     */
    public record Constat(
            UUID id, String code, String category, String severity, String status, boolean blocking,
            String entityType, String entityId, String message, String explanation, String suggestedAction,
            Map<String, Object> evidence, List<String> relatedEntityIds,
            OffsetDateTime detectedAt, OffsetDateTime createdAt,
            String resolutionType, String resolutionComment, OffsetDateTime resolvedAt,
            UUID assignedToId, String assignedToNom, String domaine, String domaineLibelle) {

        public static Constat from(Finding f) {
            var resultat = ControlResultAdapters.depuisFinding(f);
            Object typeTiers = resultat.metadata().get("typeTiers");
            var domaine = f.getDomaine() != null ? com.example.comptable.controle.ControlDomain.valueOf(f.getDomaine())
                    : com.example.comptable.controle.ControlDomain.de(f.getControlCode(), typeTiers == null ? null : typeTiers.toString());
            return new Constat(f.getId(), f.getControlCode(), f.getCategory().name(), f.getControlSeverity().name(),
                    f.getStatus().name(), resultat.blocking(), f.getSourceType(), f.getSourceId(), f.getTitle(),
                    f.getDescription(), f.getRecommendedAction(), resultat.metadata(), resultat.relatedEntityIds(),
                    f.getLastDetectedAt(), f.getCreatedAt(),
                    f.getResolutionType() != null ? f.getResolutionType().name() : null, f.getResolutionComment(),
                    f.getResolvedAt(),
                    f.getAssignedTo() != null ? f.getAssignedTo().getId() : null,
                    f.getAssignedTo() != null ? f.getAssignedTo().getNom() + " " + f.getAssignedTo().getPrenom() : null,
                    domaine.name(), domaine.libelle());
        }
    }

    public record Page(List<Constat> contenu, int page, int taille, long total, int totalPages) {
    }

    /** Une case du tableau par domaine : ce qui bloque, ce qui avertit, ce qui est a examiner. */
    public record DomaineRevue(String code, String libelle, long bloquants, long avertissements, long aExaminer, long total) {
    }

    /**
     * Espace de revision : la cloture est-elle possible (meme verite que la pre-cloture), combien de bloquants / avertissements / a examiner, combien de
     * constats resolus aujourd'hui, et le detail par domaine. Des comptes, jamais un pourcentage.
     */
    public record Espace(boolean pret, String statutCloture, long bloquants, long avertissements, long aExaminer, long resolusAujourdhui,
                         List<DomaineRevue> domaines, java.time.OffsetDateTime derniereExecution) {
    }
}
