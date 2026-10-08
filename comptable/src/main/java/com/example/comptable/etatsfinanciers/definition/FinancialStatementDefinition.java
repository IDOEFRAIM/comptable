package com.example.comptable.etatsfinanciers.definition;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Definition reglementaire VERSIONNEE d'un etat financier : cadre, version, type, systeme de presentation, date d'effet, source, lignes. Immuable : un etat FINAL
 * fige la definition utilisee (cadre, version, empreinte {@code contentHash}) ; toute evolution reglementaire cree une NOUVELLE version, jamais une mise a jour
 * silencieuse. {@code framework} est un identifiant applicatif (pas un identifiant officiel OHADA).
 */
public record FinancialStatementDefinition(String framework, String version, StatementType statementType, PresentationSystem presentationSystem,
                                           LocalDate effectiveFrom, SourceReference source, StatementScope scope, List<StatementLineDefinition> lines,
                                           String contentHash) {

    public FinancialStatementDefinition {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public Optional<StatementLineDefinition> ligne(String code) {
        return lines.stream().filter(l -> l.code().equals(code)).findFirst();
    }

    public FinancialStatementDefinition avecEmpreinte(String hash) {
        return new FinancialStatementDefinition(framework, version, statementType, presentationSystem, effectiveFrom, source, scope, lines, hash);
    }
}
