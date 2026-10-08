package com.example.comptable.comptabilite.service;

import com.example.comptable.banque.BankLabelNormalizer;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.service.RapprochementClassifier.Classement;
import com.example.comptable.comptabilite.service.RapprochementClassifier.Statut;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Score EXPLICABLE d'un rapprochement bancaire, par dessus {@link RapprochementClassifier} (qui garde la decision de base : montant, fenetre de
 * date, exclusivite). Aucun score magique : chaque candidat porte la liste de ses criteres et des points qu'ils rapportent (somme = 100 max) :
 * montant 40, sens 10, date 15 (10 jusqu'a 5 jours, 5 jusqu'a 15), reference 20, tiers 10, libelle 5.
 *
 * <p>La REFERENCE est un discriminant legitime : plusieurs mouvements de meme montant dont UN SEUL porte la reference de la transaction
 * (numero de facture, reference de virement) ne sont plus ambigus. Sans reference, rien ne change : jamais « le premier ».</p>
 */
public final class RapprochementScorer {

    /** Un critere du score : code, phrase lisible, satisfait ou non, points obtenus (0 si non satisfait). */
    public record Critere(String code, String libelle, boolean satisfait, int points) {
    }

    /** Un candidat avec son score (0 a 100) et le detail de ses criteres. */
    public record Candidat(LigneEcriture ligne, int score, List<Critere> criteres) {
        public String explication() {
            return criteres.stream().map(c -> (c.satisfait() ? "OK " : "-- ") + c.libelle()).collect(Collectors.joining(" ; "));
        }

        public boolean aLaReference() {
            return criteres.stream().anyMatch(c -> "REFERENCE".equals(c.code()) && c.satisfait());
        }
    }

    public record ClassementNote(LigneReleve ligne, Statut statut, List<Candidat> candidats) {
        public Candidat meilleur() {
            return candidats.isEmpty() ? null : candidats.get(0);
        }
    }

    private static final Set<String> MOTS_VIDES = Set.of("ETS", "SARL", "SA", "SAS", "DE", "DU", "DES", "LA", "LE", "ET", "AU", "SUARL", "CIE");

    private RapprochementScorer() {
    }

    public static List<ClassementNote> classer(Collection<LigneReleve> lignes, List<LigneEcriture> mouvements, Set<UUID> dejaPointees) {
        List<Classement> base = RapprochementClassifier.classer(lignes, mouvements, dejaPointees);
        Map<UUID, Integer> choisisParReference = new HashMap<>();
        List<ClassementNote> provisoires = new ArrayList<>();
        List<Statut> statutsDeBase = new ArrayList<>();
        for (Classement c : base) {
            List<Candidat> candidats = c.candidats().stream().map(m -> noter(c.ligne(), m))
                    .sorted(Comparator.comparingInt(Candidat::score).reversed()).toList();
            Statut statut = c.statut();
            if (statut == Statut.AMBIGUOUS) {
                List<Candidat> avecReference = candidats.stream().filter(Candidat::aLaReference).toList();
                if (avecReference.size() == 1) {
                    candidats = avecReference;
                    statut = Statut.EXACT;
                    choisisParReference.merge(avecReference.get(0).ligne().getId(), 1, Integer::sum);
                }
            }
            // Une nature qui contredit la cible (frais bancaires, retrait, versement d'especes face a un reglement de tiers, sans AUCUN autre point
            // commun) interdit l'automatisme : montant et date ne suffisent pas. La transaction est proposee, jamais confirmee seule.
            if (statut == Statut.EXACT && !candidats.isEmpty() && natureContredit(c.ligne(), candidats.get(0))) {
                Candidat premier = candidats.get(0);
                List<Critere> criteres = new ArrayList<>(premier.criteres());
                criteres.add(new Critere("NATURE", "Libelle de nature « " + libelleNature(c.ligne()) + " » sans lien avec ce reglement", false, 0));
                candidats = List.of(new Candidat(premier.ligne(), premier.score(), criteres));
                statut = Statut.PROBABLE;
            }
            provisoires.add(new ClassementNote(c.ligne(), statut, candidats));
            statutsDeBase.add(c.statut());
        }
        List<ClassementNote> notes = new ArrayList<>();
        for (int i = 0; i < provisoires.size(); i++) {
            ClassementNote p = provisoires.get(i);
            Statut statut = p.statut();
            // Deux transactions designant le meme mouvement par leur reference : l'ambiguite redevient entiere.
            if (statut == Statut.EXACT && statutsDeBase.get(i) == Statut.AMBIGUOUS && choisisParReference.get(p.candidats().get(0).ligne().getId()) > 1) {
                statut = Statut.AMBIGUOUS;
            }
            notes.add(new ClassementNote(p.ligne(), statut, p.candidats()));
        }
        return notes;
    }

    private static final Set<String> NATURES_SANS_REGLEMENT = Set.of("FRAIS", "RETRAIT_ESPECES", "VERSEMENT_ESPECES", "VIREMENT_INTERNE");

    private static String nature(LigneReleve t) {
        return t.getPaymentMethod() != null ? t.getPaymentMethod() : BankLabelNormalizer.extraire(t.getLibelle()).moyenPaiement();
    }

    private static String libelleNature(LigneReleve t) {
        return switch (String.valueOf(nature(t))) {
            case "FRAIS" -> "frais bancaires";
            case "RETRAIT_ESPECES" -> "retrait d'especes";
            case "VERSEMENT_ESPECES" -> "versement d'especes";
            case "VIREMENT_INTERNE" -> "virement interne";
            default -> "autre";
        };
    }

    /** Vrai si le libelle annonce une nature qui n'est pas un reglement de tiers ET que rien d'autre (reference, tiers, libelle) ne rapproche les deux. */
    private static boolean natureContredit(LigneReleve transaction, Candidat candidat) {
        String nature = nature(transaction);
        // Un libelle sans moyen de paiement reconnaissable (« Operation 1 ») n'a pas de nature : Set.of(...).contains(null) leverait une NPE.
        if (nature == null || !NATURES_SANS_REGLEMENT.contains(nature)) {
            return false;
        }
        return candidat.criteres().stream().noneMatch(c -> c.satisfait() && ("REFERENCE".equals(c.code()) || "TIERS".equals(c.code()) || "LIBELLE".equals(c.code())));
    }

    static Candidat noter(LigneReleve transaction, LigneEcriture mouvement) {
        return noter(transaction, mouvement, true, true);
    }

    /** Note n'importe quel mouvement du compte (recherche libre du comptable) : le montant et le sens deviennent des criteres comme les autres. */
    public static Candidat noterLibre(LigneReleve transaction, LigneEcriture mouvement) {
        boolean montantOk = RapprochementClassifier.montantCorrespond(transaction.getMontant(), mouvement);
        boolean sensOk = transaction.getMontant().signum() > 0 ? mouvement.getDebit().signum() > 0 : mouvement.getCredit().signum() > 0;
        return noter(transaction, mouvement, montantOk, sensOk);
    }

    private static Candidat noter(LigneReleve transaction, LigneEcriture mouvement, boolean montantOk, boolean sensOk) {
        List<Critere> criteres = new ArrayList<>();
        criteres.add(new Critere("MONTANT", montantOk ? "Montant exact" : "Montant different", montantOk, montantOk ? 40 : 0));
        criteres.add(new Critere("SENS", sensOk ? "Sens compatible" : "Sens oppose", sensOk, sensOk ? 10 : 0));

        long ecart = Math.abs(ChronoUnit.DAYS.between(transaction.getDateOperation(), mouvement.getEcriture().getDateEcriture()));
        int pointsDate = ecart <= 2 ? 15 : ecart <= RapprochementClassifier.FENETRE_EXACTE_JOURS ? 10 : ecart <= 15 ? 5 : 0;
        criteres.add(new Critere("DATE", ecart == 0 ? "Meme date" : "Date a " + ecart + " jour(s)", pointsDate > 0, pointsDate));

        String texte = texteDe(mouvement);
        String reference = referenceDe(transaction);
        boolean referenceTrouvee = reference != null && compacte(reference).length() >= 4 && compacte(texte).contains(compacte(reference));
        criteres.add(new Critere("REFERENCE", reference == null ? "Reference absente"
                : referenceTrouvee ? "Reference " + reference + " retrouvee" : "Reference " + reference + " non retrouvee",
                referenceTrouvee, referenceTrouvee ? 20 : 0));

        String nomTiers = transaction.getCounterpartyName() != null ? transaction.getCounterpartyName()
                : BankLabelNormalizer.extraire(transaction.getLibelle()).nomTiers();
        List<String> motsTiers = motsSignificatifs(nomTiers);
        long trouves = motsTiers.stream().filter(texte::contains).count();
        boolean tiersOk = !motsTiers.isEmpty() && trouves * 2 >= motsTiers.size();
        criteres.add(new Critere("TIERS", motsTiers.isEmpty() ? "Tiers non identifie dans le libelle"
                : tiersOk ? "Meme tiers" : "Tiers different ou non retrouve", tiersOk, tiersOk ? 10 : 0));

        List<String> motsLibelle = motsSignificatifs(transaction.getLibelleNormalise() != null ? transaction.getLibelleNormalise() : transaction.getLibelle());
        long communs = motsLibelle.stream().filter(texte::contains).count();
        boolean libelleOk = !motsLibelle.isEmpty() && communs * 5 >= motsLibelle.size() * 2L;
        criteres.add(new Critere("LIBELLE", libelleOk ? "Libelles proches" : "Libelles differents", libelleOk, libelleOk ? 5 : 0));

        int score = criteres.stream().mapToInt(Critere::points).sum();
        return new Candidat(mouvement, Math.min(100, score), criteres);
    }

    private static String referenceDe(LigneReleve t) {
        if (t.getReference() != null && !t.getReference().isBlank()) {
            return t.getReference();
        }
        if (t.getInvoiceHint() != null) {
            return t.getInvoiceHint();
        }
        BankLabelNormalizer.Indices indices = BankLabelNormalizer.extraire(t.getLibelle());
        return indices.numeroFacture() != null ? indices.numeroFacture() : indices.reference();
    }

    private static String texteDe(LigneEcriture m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getEcriture().getNumeroPiece()).append(' ');
        if (m.getEcriture().getLibelle() != null) {
            sb.append(m.getEcriture().getLibelle()).append(' ');
        }
        if (m.getLibelle() != null) {
            sb.append(m.getLibelle()).append(' ');
        }
        if (m.getTiers() != null && m.getTiers().getNom() != null) {
            sb.append(m.getTiers().getNom());
        }
        return BankLabelNormalizer.normaliser(sb.toString());
    }

    private static String compacte(String s) {
        return BankLabelNormalizer.normaliser(s).replaceAll("[^A-Z0-9]", "");
    }

    private static List<String> motsSignificatifs(String texte) {
        if (texte == null) {
            return List.of();
        }
        return Arrays.stream(BankLabelNormalizer.normaliser(texte).split(" "))
                .filter(m -> m.length() >= 3 && !MOTS_VIDES.contains(m) && !m.matches("[0-9/-]+")).distinct().toList();
    }
}
