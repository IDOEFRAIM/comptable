package com.example.comptable.agent.finance.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Capability get_payables (STEP 8, section 11 du plan) : reshape de {@code BalanceAgeeDto}
 * (deja existant) filtre sur les tiers FOURNISSEUR - aucun second calcul. Tous les montants sont
 * exprimes positifs (une dette de 9.4M s'exprime "9400000", jamais "-9400000" - le signe negatif
 * de BalanceAgeeLigneDto pour un FOURNISSEUR est un detail d'implementation interne, pas expose
 * ici). */
public record PayablesDto(
        LocalDate asOf,
        BigDecimal total,
        BigDecimal current,
        BigDecimal overdue,
        BigDecimal aging0a30,
        BigDecimal aging31a60,
        BigDecimal aging61a90,
        BigDecimal agingPlus90,
        List<TopTiersDto> topCreditors
) {
}
