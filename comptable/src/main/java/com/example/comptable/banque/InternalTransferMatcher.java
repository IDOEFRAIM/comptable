package com.example.comptable.banque;

import com.example.comptable.comptabilite.domain.LigneReleve;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Appariement DETERMINISTE des deux jambes d'un virement interne : la sortie du compte A et l'entree du compte B, meme montant, comptes differents,
 * dates proches. Fonction pure, sans acces base. Jamais « la premiere » :
 * <ul>
 *   <li>{@code EXACT} : une seule jambe possible de chaque cote, a au plus 2 jours, ET une preuve que c'est un transfert (au moins un libelle de
 *       virement interne, ou une reference commune) ;</li>
 *   <li>{@code PROBABLE} : une seule jambe possible de chaque cote, mais rien d'autre que le montant et la date (deux flux independants de meme
 *       montant sont possibles) : propose, jamais lie seul ;</li>
 *   <li>{@code AMBIGUOUS} : plusieurs jambes possibles : decision humaine, aucune n'est choisie.</li>
 * </ul>
 */
public final class InternalTransferMatcher {

    public static final int FENETRE_JOURS = 3;
    public static final int FENETRE_EXACTE_JOURS = 2;

    public enum Niveau { EXACT, PROBABLE, AMBIGUOUS }

    public record Critere(String code, String libelle, boolean satisfait, int points) {
    }

    /** {@code entree} est null pour un cas ambigu ; {@code alternatives} liste alors toutes les entrees possibles. */
    public record Appariement(LigneReleve sortie, LigneReleve entree, Niveau niveau, int score, List<Critere> criteres, List<LigneReleve> alternatives) {
        public String explication() {
            return criteres.stream().map(c -> (c.satisfait() ? "OK " : "-- ") + c.libelle()).reduce((a, b) -> a + " ; " + b).orElse("");
        }
    }

    private InternalTransferMatcher() {
    }

    /** @param exclus couples {@code idSortie|idEntree} rejetes ou annules par un humain : jamais reproposes. */
    public static List<Appariement> apparier(Collection<LigneReleve> lignes, Set<String> exclus) {
        List<LigneReleve> sorties = lignes.stream().filter(l -> l.getMontant().signum() < 0).toList();
        Map<String, List<LigneReleve>> entreesParMontant = new HashMap<>();
        for (LigneReleve l : lignes) {
            if (l.getMontant().signum() > 0) {
                entreesParMontant.computeIfAbsent(cle(l), k -> new ArrayList<>()).add(l);
            }
        }
        Map<UUID, List<LigneReleve>> candidatsDeLaSortie = new HashMap<>();
        Map<UUID, List<LigneReleve>> sortiesDeLEntree = new HashMap<>();
        for (LigneReleve s : sorties) {
            List<LigneReleve> candidats = new ArrayList<>();
            for (LigneReleve e : entreesParMontant.getOrDefault(cle(s), List.of())) {
                boolean autreCompte = !s.getCompteId().equals(e.getCompteId());
                long jours = Math.abs(ChronoUnit.DAYS.between(s.getDateOperation(), e.getDateOperation()));
                if (autreCompte && jours <= FENETRE_JOURS && !exclus.contains(s.getId() + "|" + e.getId())) {
                    candidats.add(e);
                    sortiesDeLEntree.computeIfAbsent(e.getId(), k -> new ArrayList<>()).add(s);
                }
            }
            if (!candidats.isEmpty()) {
                candidatsDeLaSortie.put(s.getId(), candidats);
            }
        }
        List<Appariement> resultat = new ArrayList<>();
        for (LigneReleve s : sorties) {
            List<LigneReleve> candidats = candidatsDeLaSortie.get(s.getId());
            if (candidats == null) {
                continue;
            }
            if (candidats.size() > 1 || sortiesDeLEntree.get(candidats.get(0).getId()).size() > 1) {
                resultat.add(new Appariement(s, null, Niveau.AMBIGUOUS, 0, List.of(), candidats));
                continue;
            }
            LigneReleve e = candidats.get(0);
            List<Critere> criteres = noter(s, e);
            int score = Math.min(100, criteres.stream().mapToInt(Critere::points).sum());
            long jours = Math.abs(ChronoUnit.DAYS.between(s.getDateOperation(), e.getDateOperation()));
            boolean preuve = criteres.stream().anyMatch(c -> c.satisfait() && ("NATURE".equals(c.code()) || "REFERENCE".equals(c.code())));
            Niveau niveau = jours <= FENETRE_EXACTE_JOURS && preuve ? Niveau.EXACT : Niveau.PROBABLE;
            resultat.add(new Appariement(s, e, niveau, score, criteres, List.of(e)));
        }
        return resultat;
    }

    static List<Critere> noter(LigneReleve sortie, LigneReleve entree) {
        List<Critere> criteres = new ArrayList<>();
        criteres.add(new Critere("MONTANT", "Meme montant", true, 40));
        criteres.add(new Critere("SENS", "Sorties et entrees opposees", true, 10));
        criteres.add(new Critere("COMPTES", "Deux comptes bancaires differents", true, 10));
        long jours = Math.abs(ChronoUnit.DAYS.between(sortie.getDateOperation(), entree.getDateOperation()));
        int pointsDate = jours <= 1 ? 20 : 10;
        criteres.add(new Critere("DATE", jours == 0 ? "Meme jour" : "Dates a " + jours + " jour(s)", true, pointsDate));
        boolean natureSortie = evoqueUnTransfert(sortie);
        boolean natureEntree = evoqueUnTransfert(entree);
        int pointsNature = natureSortie && natureEntree ? 20 : natureSortie || natureEntree ? 10 : 0;
        criteres.add(new Critere("NATURE", pointsNature == 20 ? "Les deux libelles evoquent un virement interne"
                : pointsNature == 10 ? "Un libelle evoque un virement interne" : "Aucun libelle n'evoque un virement interne", pointsNature > 0, pointsNature));
        boolean reference = referencesCommunes(sortie, entree);
        criteres.add(new Critere("REFERENCE", reference ? "Reference commune" : "Pas de reference commune", reference, reference ? 10 : 0));
        return criteres;
    }

    private static boolean evoqueUnTransfert(LigneReleve l) {
        return BankLabelNormalizer.evoqueUnVirementInterne(l.getLibelle());
    }

    private static boolean referencesCommunes(LigneReleve a, LigneReleve b) {
        String ra = compacte(a.getReference());
        String rb = compacte(b.getReference());
        return ra.length() >= 4 && ra.equals(rb);
    }

    private static String compacte(String s) {
        return s == null ? "" : BankLabelNormalizer.normaliser(s).replaceAll("[^A-Z0-9]", "");
    }

    private static String cle(LigneReleve l) {
        return l.getMontant().abs().stripTrailingZeros().toPlainString();
    }
}
