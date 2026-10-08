package com.example.comptable.etatsfinanciers.mapping;

import com.example.comptable.etatsfinanciers.definition.AccountMappingRule;
import com.example.comptable.etatsfinanciers.definition.BalanceSide;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.MappingRole;
import com.example.comptable.etatsfinanciers.definition.StatementLineDefinition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MOTEUR UNIQUE de correspondance comptes -> lignes d'un etat financier. Aucun service d'etat (Bilan, Compte de resultat, TFT) ne contient de test de prefixe de
 * compte : les regles sont des DONNEES versionnees de la definition ({@code AccountMappingRule}), appliquees ici et nulle part ailleurs.
 *
 * <p>Pour chaque compte pertinent (classe du perimetre de l'etat, solde non nul) : exactement une ligne qui l'accepte -&gt; mappe ; aucune -&gt; {@code UNMAPPED}
 * (jamais « la premiere rubrique plausible ») ; plusieurs lignes distinctes -&gt; {@code AMBIGUOUS} (exclu des montants, signale).</p>
 */
@Component
public class FinancialStatementMappingResolver {

    /** Compte a classer : {@code net} = debit - credit (sert au filtre « solde debiteur / crediteur »). */
    public record AccountInput(UUID id, String number, String label, BigDecimal net) {
    }

    public record MappedAccount(AccountInput account, String lineCode, MappingRole role) {
    }

    public record AmbiguousAccount(AccountInput account, Set<String> lineCodes) {
    }

    public record MappingOutcome(List<MappedAccount> mapped, List<AccountInput> unmapped, List<AmbiguousAccount> ambiguous) {
        public List<MappedAccount> pourLigne(String code) {
            return mapped.stream().filter(m -> m.lineCode().equals(code)).toList();
        }
    }

    public MappingOutcome resoudre(FinancialStatementDefinition definition, List<AccountInput> comptes) {
        List<MappedAccount> mappes = new ArrayList<>();
        List<AccountInput> nonMappes = new ArrayList<>();
        List<AmbiguousAccount> ambigus = new ArrayList<>();
        for (AccountInput compte : comptes) {
            if (!definition.scope().concerne(compte.number())) {
                continue;
            }
            Set<String> lignes = new LinkedHashSet<>();
            MappedAccount retenu = null;
            for (StatementLineDefinition ligne : definition.lines()) {
                for (AccountMappingRule regle : ligne.mappingRules()) {
                    if (regle.accepte(compte.number()) && coteAccepte(regle.side(), compte.net())) {
                        lignes.add(ligne.code());
                        if (retenu == null) {
                            retenu = new MappedAccount(compte, ligne.code(), regle.role());
                        }
                    }
                }
            }
            if (lignes.isEmpty()) {
                nonMappes.add(compte);
            } else if (lignes.size() > 1) {
                ambigus.add(new AmbiguousAccount(compte, lignes));
            } else {
                mappes.add(retenu);
            }
        }
        return new MappingOutcome(mappes, nonMappes, ambigus);
    }

    private static boolean coteAccepte(BalanceSide cote, BigDecimal net) {
        return switch (cote) {
            case ANY -> true;
            case DEBIT -> net.signum() > 0;
            case CREDIT -> net.signum() < 0;
        };
    }
}
