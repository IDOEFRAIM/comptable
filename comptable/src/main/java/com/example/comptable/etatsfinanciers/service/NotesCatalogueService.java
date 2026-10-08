package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NotesCatalogue;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalogue officiel versionne des Notes annexes (Fiche R4, p. 989). Phase F8 : le CATALOGUE seulement. {@code automationType} (AUTOMATIC / MIXED / MANUAL_REQUIRED)
 * n'est decide qu'apres audit des donnees disponibles : il vaut {@code TO_BE_AUDITED} pour toutes les notes. {@code applicability} vaut {@code TO_DETERMINE}.
 * Regle de la Fiche R4 : une note reellement non applicable n'est PAS jointe (jamais de page vide).
 */
@Service
public class NotesCatalogueService {

    public static final Set<String> AUTOMATION_TYPES = Set.of("AUTOMATIC", "MIXED", "MANUAL_REQUIRED");
    public static final Set<String> APPLICABILITES = Set.of("APPLICABLE", "NOT_APPLICABLE");

    private final FinancialStatementDefinitionRegistry registry;

    public NotesCatalogueService(FinancialStatementDefinitionRegistry registry) {
        this.registry = registry;
    }

    public NotesCatalogue catalogue() {
        return registry.notes();
    }

    /**
     * Notes a joindre au dossier : celles decidees {@code APPLICABLE} ; les notes {@code NOT_APPLICABLE} sont ecartees ; une note dont l'applicabilite n'est pas
     * encore decidee n'est jamais jointe silencieusement ni ecartee silencieusement (elle ressort dans {@code aDecider}).
     */
    public Selection selectionner(Map<String, String> decisions) {
        List<NoteDefinition> ajoindre = new java.util.ArrayList<>();
        List<NoteDefinition> aDecider = new java.util.ArrayList<>();
        for (NoteDefinition note : catalogue().notes()) {
            String decision = decisions.get(note.code());
            if ("APPLICABLE".equals(decision)) {
                ajoindre.add(note);
            } else if (!"NOT_APPLICABLE".equals(decision)) {
                aDecider.add(note);
            }
        }
        return new Selection(List.copyOf(ajoindre), List.copyOf(aDecider));
    }

    public record Selection(List<NoteDefinition> aJoindre, List<NoteDefinition> aDecider) {
    }
}
