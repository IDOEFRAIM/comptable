package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteFieldDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteSectionDefinition;
import com.example.comptable.etatsfinanciers.service.NotesService.NoteGap;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcul unique du statut d'une note (jamais disperse dans les controleurs). Entrees : applicabilite, manques de calcul (donnees generees, etats sources), schema de la note
 * et donnees manuelles. Sortie : {@code NOT_APPLICABLE}, {@code READY}, {@code INCOMPLETE} ou {@code MANUAL_REQUIRED}.
 *
 * <ul>
 *   <li>non applicable (decision validee) : {@code NOT_APPLICABLE} ; applicabilite non decidee (a determiner ou candidate non validee) : {@code MANUAL_REQUIRED} (decision humaine) ;</li>
 *   <li>applicable : {@code INCOMPLETE} si le calcul est impossible (compte non place, etat source non READY, tableau non genere) ou si le schema reglementaire est partiel ;
 *       sinon, si un champ obligatoire manque : {@code MANUAL_REQUIRED} pour une note entierement manuelle, {@code INCOMPLETE} pour une note a partie generee ;
 *       sinon {@code READY}.</li>
 * </ul>
 */
public final class NoteCompletionEvaluator {

    private NoteCompletionEvaluator() {
    }

    public record FieldState(String fieldCode, String label, String type, boolean required, boolean filled, String value, String comment, String source, List<String> options) {
    }

    /** Etat d'une section : {@code GENERATED}, {@code COMPLETE}, {@code MISSING} (champ obligatoire manquant) ou {@code OPTIONAL} (facultative, non remplie). */
    public record SectionState(String sectionCode, String label, String sourceType, boolean required, String status, List<FieldState> fields) {
    }

    public record Evaluation(String status, List<NoteGap> gaps, List<SectionState> sections, List<String> missingSections) {
    }

    public static Evaluation evaluer(NoteDefinition note, String applicabilite, List<NoteGap> manquesDeCalcul, boolean implemente, NoteManualData manuel) {
        List<SectionState> sections = new ArrayList<>();
        List<String> manquantes = new ArrayList<>();
        List<NoteGap> gaps = new ArrayList<>(manquesDeCalcul);
        List<NoteGap> gapsManuels = new ArrayList<>();
        for (NoteSectionDefinition s : note.sections()) {
            if ("GENERATED".equals(s.sourceType())) {
                sections.add(new SectionState(s.code(), s.label(), s.sourceType(), false, implemente ? "GENERATED" : "MISSING", List.of()));
                continue;
            }
            List<FieldState> champs = new ArrayList<>();
            List<String> champsManquants = new ArrayList<>();
            boolean auMoinsUn = false;
            for (NoteFieldDefinition f : s.fields()) {
                NoteManualData.ManualValue v = manuel.valeur(s.code(), f.code());
                boolean rempli = v != null && v.value() != null && !v.value().isBlank();
                auMoinsUn |= rempli;
                if (f.required() && !rempli) {
                    champsManquants.add(f.code());
                }
                champs.add(new FieldState(f.code(), f.label(), f.type(), f.required(), rempli, rempli ? v.value() : null, v == null ? null : v.comment(), v == null ? null : v.source(), f.options()));
            }
            String etat = !champsManquants.isEmpty() ? "MISSING" : auMoinsUn || s.required() ? "COMPLETE" : "OPTIONAL";
            sections.add(new SectionState(s.code(), s.label(), s.sourceType(), s.required(), etat, champs));
            if (!champsManquants.isEmpty()) {
                manquantes.add(s.code());
                gapsManuels.add(new NoteGap(note.code(), s.code(), "information a fournir : " + s.label(), "MANUAL", champsManquants));
            }
        }

        String statut;
        if ("NOT_APPLICABLE".equals(applicabilite)) {
            return new Evaluation("NOT_APPLICABLE", List.of(), sections, List.of());
        }
        if (!"APPLICABLE".equals(applicabilite)) {
            gaps.add(new NoteGap(note.code(), "applicabilite", "NOT_APPLICABLE_CANDIDATE".equals(applicabilite) ? "non-applicabilite prouvee par les comptes, a valider"
                    : "applicabilite a decider (aucune preuve comptable possible)", "VALIDATION", List.of()));
            return new Evaluation("MANUAL_REQUIRED", gaps, sections, List.of());
        }
        if (!implemente && !"MANUAL_REQUIRED".equals(note.automationType())) {
            gaps.add(new NoteGap(note.code(), "tableau", "tableau de la note non genere (" + note.readiness() + ")", "COMPUTED", List.of()));
        }
        if ("PARTIAL".equals(note.schemaStatus())) {
            gaps.add(new NoteGap(note.code(), "schema", "schema reglementaire partiel (colonnes rognees) : la note n'est pas finalisable", "SCHEMA", List.of()));
        }
        gaps.addAll(gapsManuels);
        boolean calculImpossible = gaps.stream().anyMatch(g -> g.kind().equals("COMPUTED") || g.kind().equals("SCHEMA"));
        if (calculImpossible) {
            statut = "INCOMPLETE";
        } else if (!gapsManuels.isEmpty()) {
            statut = "MANUAL_REQUIRED".equals(note.automationType()) ? "MANUAL_REQUIRED" : "INCOMPLETE";
        } else {
            statut = "READY";
        }
        return new Evaluation(statut, gaps, sections, manquantes);
    }
}
