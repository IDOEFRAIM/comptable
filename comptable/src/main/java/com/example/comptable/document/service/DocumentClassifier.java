package com.example.comptable.document.service;

import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.QualiteClassification;
import com.example.comptable.document.domain.TypeDocument;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identification DETERMINISTE du tiers d'une piece (fournisseur pour un achat, client pour une vente) a partir du
 * nom extrait. L'IA aide a LIRE le nom ; la decision est une regle Java :
 * <ul>
 *   <li>{@code EXACT} (0,97) : un seul tiers au nom normalise identique -&gt; la piece peut progresser seule ;</li>
 *   <li>{@code PROBABLE} (jusqu'a 0,89) : un seul tiers au nom proche (inclusion ou mots communs) -&gt; propose ;</li>
 *   <li>{@code AMBIGU} : plusieurs tiers egalement plausibles -&gt; jamais le premier, constat ;</li>
 *   <li>{@code INCONNU} : aucun tiers ne correspond.</li>
 * </ul>
 */
@Component
public class DocumentClassifier {

    static final double CONFIANCE_EXACT = 0.97;
    private static final Set<String> MOTS_VIDES = Set.of("SARL", "SA", "SAS", "SUARL", "ETS", "STE", "SOCIETE",
            "ETABLISSEMENT", "ETABLISSEMENTS", "CIE", "COMPAGNIE", "GIE", "ET", "DE", "DU", "DES", "LA", "LE", "LES");

    public record Resultat(Tiers tiers, QualiteClassification qualite, double confiance) {
    }

    private final TiersRepository tiersRepository;

    public DocumentClassifier(TiersRepository tiersRepository) {
        this.tiersRepository = tiersRepository;
    }

    /** Nom du tiers lu par l'extraction, ou {@code null}. */
    public static String nomExtrait(Document document) {
        if (document.getDonneesOcr() == null) {
            return null;
        }
        Object nom = document.getDonneesOcr().get("fournisseur");
        return nom instanceof String s && !s.isBlank() ? s : null;
    }

    public Resultat identifierTiers(Document document) {
        String nom = nomExtrait(document);
        if (nom == null || !document.getTypeDocument().estFacture()) {
            return new Resultat(null, QualiteClassification.INCONNU, 0.0);
        }
        TypeTiers type = document.getTypeDocument() == TypeDocument.FACTURE_ACHAT ? TypeTiers.FOURNISSEUR : TypeTiers.CLIENT;
        return identifier(nom, tiersRepository.findByEntrepriseIdAndType(document.getEntreprise().getId(), type));
    }

    /** Visible pour les tests : l'identification ne depend que du nom et de la liste des tiers. */
    static Resultat identifier(String nomExtrait, List<Tiers> tiersDeLEntreprise) {
        String cible = normaliserNom(nomExtrait);
        if (cible.isEmpty()) {
            return new Resultat(null, QualiteClassification.INCONNU, 0.0);
        }
        List<Tiers> exacts = tiersDeLEntreprise.stream().filter(t -> normaliserNom(t.getNom()).equals(cible)).toList();
        if (exacts.size() == 1) {
            return new Resultat(exacts.get(0), QualiteClassification.EXACT, CONFIANCE_EXACT);
        }
        if (exacts.size() > 1) {
            return new Resultat(null, QualiteClassification.AMBIGU, 0.0);
        }
        record Score(Tiers tiers, double score) {
        }
        List<Score> proches = new ArrayList<>();
        for (Tiers t : tiersDeLEntreprise) {
            double score = similarite(cible, normaliserNom(t.getNom()));
            if (score >= 0.5) {
                proches.add(new Score(t, score));
            }
        }
        if (proches.isEmpty()) {
            return new Resultat(null, QualiteClassification.INCONNU, 0.0);
        }
        proches.sort(Comparator.comparingDouble(Score::score).reversed());
        boolean egalite = proches.size() > 1 && proches.get(1).score() >= proches.get(0).score() - 0.05;
        if (egalite) {
            return new Resultat(null, QualiteClassification.AMBIGU, 0.0);
        }
        // Jamais au niveau d'un nom exact : un nom seulement proche reste une proposition.
        return new Resultat(proches.get(0).tiers(), QualiteClassification.PROBABLE, Math.min(0.89, 0.4 + 0.5 * proches.get(0).score()));
    }

    /** Accents retires, majuscules, ponctuation supprimee, formes juridiques et mots vides ignores. */
    static String normaliserNom(String nom) {
        if (nom == null) {
            return "";
        }
        String propre = Normalizer.normalize(nom, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT).replace(".", "").replaceAll("[^A-Z0-9]+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String mot : propre.split(" ")) {
            if (!mot.isEmpty() && !MOTS_VIDES.contains(mot)) {
                sb.append(sb.length() > 0 ? " " : "").append(mot);
            }
        }
        return sb.toString();
    }

    private static double similarite(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if ((a.contains(b) || b.contains(a)) && Math.min(a.length(), b.length()) >= 4) {
            return 0.85;
        }
        Set<String> motsA = new HashSet<>(List.of(a.split(" ")));
        Set<String> motsB = new HashSet<>(List.of(b.split(" ")));
        Set<String> commun = new HashSet<>(motsA);
        commun.retainAll(motsB);
        Set<String> union = new HashSet<>(motsA);
        union.addAll(motsB);
        return union.isEmpty() ? 0.0 : (double) commun.size() / union.size();
    }
}
