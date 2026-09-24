package com.example.comptable.agent.finance.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Un tiers (client ou fournisseur) et le montant qu'il represente dans la creance/dette totale -
 * toujours un montant positif (le sens debiteur/crediteur est deja porte par le contexte
 * receivables/payables, jamais un signe a interpreter ici). */
public record TopTiersDto(
        UUID tiersId,
        String nom,
        BigDecimal montant
) {
}
