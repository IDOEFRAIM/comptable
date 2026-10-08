package com.example.comptable.investigation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resultat d'une investigation comptable : un OBJET (tiers, facture, transaction, ecriture...) reconstitue de sa source jusqu'a son anomalie.
 * Rien n'est persiste : le resultat est recalcule a la demande, toujours a partir de liens enregistres (affectations, rapprochements, lineage)
 * avant toute correspondance exacte, et seulement en dernier recours d'une heuristique. L'investigation ne corrige JAMAIS : les
 * {@link SuggestedAction} designent une operation d'un service metier existant, que le comptable declenche lui-meme.
 */
public record InvestigationResult(
        Subject subject,
        String summary,
        Comparison comparison,
        List<Cause> causes,
        List<Evidence> evidence,
        List<TimelineEvent> timeline,
        List<RelatedEntity> relatedEntities,
        List<SuggestedAction> suggestedActions,
        java.util.Map<String, Object> details,
        Confidence confidence,
        OffsetDateTime generatedAt) {

    /** Fiabilite de la reconstitution, de la plus sure a la moins sure : un lien enregistre l'emporte sur toute deduction. */
    public enum Basis { PERSISTED, LINEAGE, EXACT, HEURISTIC }

    public enum Confidence { HIGH, MEDIUM, LOW }

    /** ANOMALY : a traiter. EXPLANATION : explique un ecart, rien a corriger. INFO : situation normale decrite pour la comprehension. */
    public enum Nature { ANOMALY, EXPLANATION, INFO }

    public record Subject(String type, String id, String label) {
    }

    /** Ce que le compte devrait montrer, ce qu'il montre, et l'ecart. */
    public record Comparison(String expectedLabel, BigDecimal expected, String actualLabel, BigDecimal actual, BigDecimal difference) {
    }

    /** Une cause STRUCTUREE : un code stable (exploitable par la machine et les statistiques) et une explication lisible, avec ses preuves. */
    public record Cause(String code, Nature nature, String explanation, Basis basis, BigDecimal amount, List<Evidence> evidence) {
    }

    /** Un fait verifiable : jamais un identifiant seul, toujours un libelle lisible et, le cas echeant, un montant et une date. */
    public record Evidence(String kind, String entityType, String entityId, String label, BigDecimal amount, LocalDate date) {
    }

    public record TimelineEvent(LocalDate date, String type, String label, BigDecimal amount, String entityType, String entityId) {
    }

    /** Objet navigable depuis l'ecran (par type + id, sans jamais afficher d'identifiant). */
    public record RelatedEntity(String type, String id, String label) {
    }

    /** {@code operation} nomme l'operation metier EXISTANTE a utiliser (ex. « Repartir un reglement ») ; l'investigation ne l'execute jamais. */
    public record SuggestedAction(String code, String label, String operation, String targetType, String targetId) {
    }

    /** Assemble un resultat sans repeter la mecanique de tri et de confiance. */
    public static final class Builder {
        static final int MAX_OBJETS_LIES = 100;
        private final Subject subject;
        private String summary = "";
        private Comparison comparison;
        private final List<Cause> causes = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();
        private final List<TimelineEvent> timeline = new ArrayList<>();
        private final List<RelatedEntity> related = new ArrayList<>();
        private final List<SuggestedAction> actions = new ArrayList<>();
        private final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();

        public Builder(String type, String id, String label) {
            this.subject = new Subject(type, id, label);
        }

        public Builder summary(String s) {
            this.summary = s;
            return this;
        }

        public Builder comparison(String expectedLabel, BigDecimal expected, String actualLabel, BigDecimal actual) {
            this.comparison = new Comparison(expectedLabel, expected, actualLabel, actual,
                    expected == null || actual == null ? null : actual.subtract(expected));
            return this;
        }

        public Builder cause(Cause c) {
            causes.add(c);
            return this;
        }

        public Builder evidence(Evidence e) {
            if (evidence.stream().noneMatch(x -> x.kind().equals(e.kind()) && java.util.Objects.equals(x.entityId(), e.entityId()) && x.label().equals(e.label()))) {
                evidence.add(e);
            }
            return this;
        }

        public Builder event(TimelineEvent e) {
            timeline.add(e);
            return this;
        }

        public Builder related(String type, String id, String label) {
            // Plafond : une investigation ne renvoie jamais des milliers d'objets lies (la reponse reste lisible et legere).
            if (id != null && related.size() < MAX_OBJETS_LIES && related.stream().noneMatch(r -> r.type().equals(type) && r.id().equals(id))) {
                related.add(new RelatedEntity(type, id, label));
            }
            return this;
        }

        public Builder action(String code, String label, String operation, String targetType, String targetId) {
            if (actions.stream().noneMatch(a -> a.code().equals(code) && java.util.Objects.equals(a.targetId(), targetId))) {
                actions.add(new SuggestedAction(code, label, operation, targetType, targetId));
            }
            return this;
        }

        /** Donnees structurees propres au sujet (equation de solde, balance agee, factures ouvertes...). */
        public Builder detail(String cle, Object valeur) {
            details.put(cle, valeur);
            return this;
        }

        public boolean aUneAnomalie() {
            return causes.stream().anyMatch(c -> c.nature() == Nature.ANOMALY);
        }

        public InvestigationResult build() {
            // Les anomalies d'abord, puis ce qui explique, puis le contexte ; a nature egale, la base la plus sure d'abord.
            List<Cause> triees = causes.stream()
                    .sorted(Comparator.comparing((Cause c) -> c.nature().ordinal()).thenComparing(c -> c.basis().ordinal())).toList();
            List<TimelineEvent> chrono = timeline.stream()
                    .sorted(Comparator.comparing(TimelineEvent::date, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            Basis pire = triees.stream().filter(c -> c.nature() == Nature.ANOMALY).map(Cause::basis).max(Comparator.naturalOrder()).orElse(Basis.PERSISTED);
            Confidence confiance = pire == Basis.HEURISTIC ? Confidence.LOW : pire == Basis.EXACT ? Confidence.MEDIUM : Confidence.HIGH;
            return new InvestigationResult(subject, summary, comparison, triees, List.copyOf(evidence), chrono, List.copyOf(related), List.copyOf(actions),
                    java.util.Collections.unmodifiableMap(details), confiance, OffsetDateTime.now());
        }
    }
}
