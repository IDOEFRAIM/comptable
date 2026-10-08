package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Classification DETERMINISTE d'une ligne de releve face aux mouvements du compte de tresorerie
 * (Phase B, lot B4) - une fonction pure, sans acces base, partagee par l'import du releve, le
 * rapprochement automatique relance et le detecteur de controles.
 * <ul>
 *   <li>{@code EXACT} : un seul mouvement de meme montant, a au plus {@link #FENETRE_EXACTE_JOURS}
 *       jours, revendique par cette seule ligne -&gt; pointage automatique (CERTAIN) ;</li>
 *   <li>{@code PROBABLE} : un seul mouvement de meme montant mais date plus eloignee -&gt; a confirmer ;</li>
 *   <li>{@code AMBIGUOUS} : plusieurs mouvements possibles, ou un meme mouvement revendique par plusieurs
 *       lignes -&gt; jamais de choix arbitraire du premier, decision humaine ;</li>
 *   <li>{@code UNMATCHED} : aucun mouvement de meme montant.</li>
 * </ul>
 */
public final class RapprochementClassifier {

    public static final int FENETRE_EXACTE_JOURS = 5;

    public enum Statut { EXACT, PROBABLE, AMBIGUOUS, UNMATCHED }

    public record Classement(LigneReleve ligne, Statut statut, List<LigneEcriture> candidats) {
    }

    private RapprochementClassifier() {
    }

    /** @param dejaPointees lignes d'ecriture deja prises par un releve : jamais candidates. */
    public static List<Classement> classer(Collection<LigneReleve> lignes, List<LigneEcriture> mouvements, Set<UUID> dejaPointees) {
        // Index par (sens, montant) : la recherche des candidats d'une ligne est directe, jamais un balayage de tous les mouvements.
        Map<String, List<LigneEcriture>> parMontant = new HashMap<>();
        for (LigneEcriture m : mouvements) {
            if (dejaPointees.contains(m.getId())) {
                continue;
            }
            if (m.getDebit().signum() > 0) {
                parMontant.computeIfAbsent(cle(true, m.getDebit()), k -> new ArrayList<>()).add(m);
            }
            if (m.getCredit().signum() > 0) {
                parMontant.computeIfAbsent(cle(false, m.getCredit()), k -> new ArrayList<>()).add(m);
            }
        }
        Map<LigneReleve, List<LigneEcriture>> candidatsParLigne = new HashMap<>();
        Map<UUID, Integer> revendications = new HashMap<>();
        for (LigneReleve ligne : lignes) {
            List<LigneEcriture> candidats = parMontant.getOrDefault(cle(ligne.getMontant().signum() > 0, ligne.getMontant().abs()), List.of());
            candidatsParLigne.put(ligne, candidats);
            candidats.forEach(c -> revendications.merge(c.getId(), 1, Integer::sum));
        }

        List<Classement> resultat = new ArrayList<>();
        for (LigneReleve ligne : lignes) {
            List<LigneEcriture> candidats = candidatsParLigne.get(ligne);
            if (candidats.isEmpty()) {
                resultat.add(new Classement(ligne, Statut.UNMATCHED, List.of()));
            } else if (candidats.size() > 1 || revendications.get(candidats.get(0).getId()) > 1) {
                resultat.add(new Classement(ligne, Statut.AMBIGUOUS, candidats));
            } else {
                long ecart = Math.abs(ChronoUnit.DAYS.between(ligne.getDateOperation(), candidats.get(0).getEcriture().getDateEcriture()));
                resultat.add(new Classement(ligne, ecart <= FENETRE_EXACTE_JOURS ? Statut.EXACT : Statut.PROBABLE, candidats));
            }
        }
        return resultat;
    }

    private static String cle(boolean debit, BigDecimal montant) {
        return (debit ? "D" : "C") + montant.stripTrailingZeros().toPlainString();
    }

    /** Montant releve positif (entree d'argent) = debit du compte de tresorerie ; negatif = credit. */
    public static boolean montantCorrespond(BigDecimal montantReleve, LigneEcriture ligne) {
        BigDecimal montantAttendu = montantReleve.signum() > 0 ? ligne.getDebit() : ligne.getCredit();
        return montantAttendu.compareTo(montantReleve.abs()) == 0;
    }
}
