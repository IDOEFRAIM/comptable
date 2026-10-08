package com.example.comptable.banque;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture d'un releve CSV. Rien n'est ignore en silence : chaque ligne non exploitable devient un {@link Rejet} avec sa raison.
 * Montants tolerants (« 1 234,56 », « 1.234,56 », « 1,234.56 », « (100) », « -100 ») ; dates ISO, jj/mm/aaaa, jj-mm-aaaa, jj.mm.aaaa.
 */
public final class BankCsvParser {

    public record LigneParsee(int numeroLigne, LocalDate date, LocalDate dateValeur, String libelle, String reference, BigDecimal montant,
                              BigDecimal solde) {
    }

    public record Rejet(int numeroLigne, String raison, String extrait) {
    }

    public record Resultat(BankCsvMapping mapping, List<LigneParsee> valides, List<Rejet> rejets, int totalLignes) {
    }

    private static final List<DateTimeFormatter> FORMATS_DATE = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"),
            DateTimeFormatter.ofPattern("dd.MM.uuuu"),
            DateTimeFormatter.ofPattern("dd/MM/uu"));

    private BankCsvParser() {
    }

    public static Resultat lire(byte[] contenu, BankCsvMapping demande) throws IOException {
        List<String> lignes = decouper(decoder(contenu));
        if (lignes.isEmpty()) {
            return new Resultat(demande != null ? demande : BankCsvMapping.historique(), List.of(), List.of(), 0);
        }
        String separateur = demande != null && demande.separateur() != null ? demande.separateur() : detecterSeparateur(lignes.get(0));
        BankCsvMapping mapping = demande;
        int premiere = 0;
        if (mapping == null) {
            BankCsvMapping deduit = BankCsvMapping.deduireDeLEnTete(champs(lignes.get(0), separateur), separateur);
            if (deduit != null) {
                mapping = deduit;
                premiere = 1;
            } else {
                BankCsvMapping historique = BankCsvMapping.historique();
                mapping = new BankCsvMapping(historique.date(), null, historique.libelle(), null, null, null, historique.montant(), null, true, separateur);
                premiere = estEnTete(lignes.get(0), separateur) ? 1 : 0;
            }
        } else {
            mapping = new BankCsvMapping(mapping.date(), mapping.dateValeur(), mapping.libelle(), mapping.reference(), mapping.debit(), mapping.credit(),
                    mapping.montant(), mapping.solde(), mapping.enTete(), separateur);
            premiere = mapping.enTete() ? 1 : 0;
        }

        List<LigneParsee> valides = new ArrayList<>();
        List<Rejet> rejets = new ArrayList<>();
        int total = 0;
        for (int i = premiere; i < lignes.size(); i++) {
            String brute = lignes.get(i);
            if (brute.isBlank()) {
                continue;
            }
            total++;
            List<String> c = champs(brute, separateur);
            String extrait = brute.length() > 120 ? brute.substring(0, 120) : brute;
            LocalDate date = parserDate(valeur(c, mapping.date()));
            if (date == null) {
                rejets.add(new Rejet(i + 1, "Date illisible : " + valeur(c, mapping.date()), extrait));
                continue;
            }
            BigDecimal montant;
            try {
                montant = montant(c, mapping);
            } catch (IllegalArgumentException e) {
                rejets.add(new Rejet(i + 1, e.getMessage(), extrait));
                continue;
            }
            if (montant == null) {
                rejets.add(new Rejet(i + 1, "Montant absent ou illisible", extrait));
                continue;
            }
            String libelle = valeur(c, mapping.libelle());
            String reference = valeur(c, mapping.reference());
            valides.add(new LigneParsee(i + 1, date, parserDate(valeur(c, mapping.dateValeur())), libelle == null ? "" : libelle,
                    reference == null || reference.isBlank() ? null : reference, montant, parserMontant(valeur(c, mapping.solde()))));
        }
        return new Resultat(mapping, valides, rejets, total);
    }

    private static BigDecimal montant(List<String> c, BankCsvMapping m) {
        if (m.montant() != null) {
            return parserMontant(valeur(c, m.montant()));
        }
        BigDecimal debit = parserMontant(valeur(c, m.debit()));
        BigDecimal credit = parserMontant(valeur(c, m.credit()));
        if (debit != null && credit != null && debit.signum() != 0 && credit.signum() != 0) {
            throw new IllegalArgumentException("Debit et credit renseignes sur la meme ligne");
        }
        if (credit != null && credit.signum() != 0) {
            return credit.abs();
        }
        if (debit != null && debit.signum() != 0) {
            return debit.abs().negate();
        }
        return debit != null || credit != null ? BigDecimal.ZERO : null;
    }

    private static String valeur(List<String> c, Integer index) {
        if (index == null || index < 0 || index >= c.size()) {
            return null;
        }
        String v = c.get(index).trim();
        return v.isEmpty() ? null : v;
    }

    static LocalDate parserDate(String texte) {
        if (texte == null) {
            return null;
        }
        for (DateTimeFormatter f : FORMATS_DATE) {
            try {
                return LocalDate.parse(texte.trim(), f);
            } catch (DateTimeParseException ignored) {
                // format suivant
            }
        }
        return null;
    }

    /** Dernier separateur rencontre = separateur decimal ; « (100) » = negatif ; espaces (y compris insecables) ignores. */
    public static BigDecimal parserMontant(String texte) {
        if (texte == null) {
            return null;
        }
        String t = texte.replace(" ", "").replace(" ", "").replace(" ", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        boolean negatif = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            negatif = true;
            t = t.substring(1, t.length() - 1);
        }
        if (t.endsWith("-")) {
            negatif = true;
            t = t.substring(0, t.length() - 1);
        }
        t = t.replaceAll("(?i)(FCFA|XOF|CFA|EUR|F)$", "");
        int virgule = t.lastIndexOf(',');
        int point = t.lastIndexOf('.');
        if (virgule >= 0 && point >= 0) {
            char decimal = virgule > point ? ',' : '.';
            char milliers = decimal == ',' ? '.' : ',';
            t = t.replace(String.valueOf(milliers), "").replace(decimal, '.');
        } else if (virgule >= 0 || point >= 0) {
            char sep = virgule >= 0 ? ',' : '.';
            int dernier = t.lastIndexOf(sep);
            int decimales = t.length() - dernier - 1;
            boolean plusieurs = t.indexOf(sep) != dernier;
            if (plusieurs || decimales == 3) {
                t = t.replace(String.valueOf(sep), ""); // separateur de milliers
            } else {
                t = t.replace(sep, '.');
            }
        }
        try {
            BigDecimal v = new BigDecimal(t);
            return negatif ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String decoder(byte[] contenu) {
        int debut = contenu.length >= 3 && contenu[0] == (byte) 0xEF && contenu[1] == (byte) 0xBB && contenu[2] == (byte) 0xBF ? 3 : 0;
        ByteBuffer tampon = ByteBuffer.wrap(contenu, debut, contenu.length - debut);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(tampon).toString();
        } catch (CharacterCodingException e) {
            return new String(contenu, debut, contenu.length - debut, StandardCharsets.ISO_8859_1);
        }
    }

    private static List<String> decouper(String texte) {
        List<String> lignes = new ArrayList<>();
        for (String l : texte.split("\\r?\\n", -1)) {
            lignes.add(l);
        }
        while (!lignes.isEmpty() && lignes.get(lignes.size() - 1).isBlank()) {
            lignes.remove(lignes.size() - 1);
        }
        return lignes;
    }

    private static String detecterSeparateur(String premiereLigne) {
        int pointsVirgules = compter(premiereLigne, ';');
        int tabs = compter(premiereLigne, '\t');
        int virgules = compter(premiereLigne, ',');
        if (tabs > pointsVirgules && tabs >= virgules) {
            return "\t";
        }
        return pointsVirgules >= virgules && pointsVirgules > 0 ? ";" : ",";
    }

    private static int compter(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) {
            if (x == c) {
                n++;
            }
        }
        return n;
    }

    private static boolean estEnTete(String ligne, String separateur) {
        List<String> c = champs(ligne, separateur);
        return !c.isEmpty() && parserDate(c.get(0)) == null;
    }

    /** Decoupage CSV avec champs entre guillemets (separateurs et guillemets doubles echappes). */
    static List<String> champs(String ligne, String separateur) {
        List<String> champs = new ArrayList<>();
        StringBuilder courant = new StringBuilder();
        boolean entreGuillemets = false;
        char sep = separateur.charAt(0);
        for (int i = 0; i < ligne.length(); i++) {
            char ch = ligne.charAt(i);
            if (entreGuillemets) {
                if (ch == '"' && i + 1 < ligne.length() && ligne.charAt(i + 1) == '"') {
                    courant.append('"');
                    i++;
                } else if (ch == '"') {
                    entreGuillemets = false;
                } else {
                    courant.append(ch);
                }
            } else if (ch == '"') {
                entreGuillemets = true;
            } else if (ch == sep) {
                champs.add(courant.toString());
                courant.setLength(0);
            } else {
                courant.append(ch);
            }
        }
        champs.add(courant.toString());
        return champs;
    }
}
