package com.example.comptable.agent.finance.web.dto;

import com.example.comptable.finance.web.dto.FinancialSnapshotDto;

import java.math.BigDecimal;

/** Capability get_revenue/get_expenses/get_profit/get_margin (STEP 8, sections 8-9 du plan) : un
 * seul endpoint enrichit {@code FinancialSnapshotDto} (deja existant, jamais duplique) avec les
 * marges - plutot que quatre endpoints Java distincts pour quatre chiffres deja presents dans le
 * meme snapshot (section 17 du plan : "ne pas creer 10 endpoints si un endpoint snapshot peut
 * fournir plusieurs informations coherentes").
 * <p>
 * {@code netMargin} = profit / revenue, disponible des que revenue != 0.
 * <p>
 * {@code grossMargin} est volontairement TOUJOURS {@code null} avec
 * {@code grossMarginUnavailableReason} rempli dans cette version : le systeme ne distingue pas
 * aujourd'hui un cout des marchandises vendues (COGS) des achats de la periode (classe 60) ni des
 * mouvements de stock - utiliser les achats bruts comme proxy produirait un chiffre trompeur des
 * qu'un stock varie significativement sur la periode (exactement l'ecart que working_capital est
 * cense capturer). Conformement a la section 9 du plan ("preferer insufficient_data a un chiffre
 * invente"), ce calcul n'est pas approxime ici.
 */
public record AiFinancialSnapshotDto(
        FinancialSnapshotDto snapshot,
        BigDecimal netMargin,
        String netMarginUnavailableReason,
        BigDecimal grossMargin,
        String grossMarginUnavailableReason
) {
}
