package com.example.comptable.comptabilite.operation.tva;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultat FISCAL de la resolution TVA pour une operation : taux et montant. {@code nonSupporte=true}
 * signifie qu'aucune regle fiscale applicable n'a ete trouvee - le moteur ne calcule jamais de TVA
 * dans ce cas, {@code motif} explique pourquoi (jamais un taux devine).
 * <p>
 * Ce type ne porte volontairement AUCUN compte comptable : le taux (fiscalite, {@code TaxRule}) et le
 * compte (comptabilite, {@code AccountingAccountResolver}, purposes TVA_COLLECTEE / TVA_RECUPERABLE)
 * sont deux questions distinctes qui ne doivent jamais etre melangees.
 */
public record TvaResultat(
        BigDecimal taux,
        BigDecimal montantTva,
        UUID taxRuleId,
        boolean nonSupporte,
        String motif,
        boolean sourceDemo
) {
    public static TvaResultat nonSupporte(String motif) {
        return new TvaResultat(null, null, null, true, motif, false);
    }
}
