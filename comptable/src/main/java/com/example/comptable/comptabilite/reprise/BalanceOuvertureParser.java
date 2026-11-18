package com.example.comptable.comptabilite.reprise;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lit une balance de reprise saisie sous Excel (.xlsx) ou exportée en CSV : une ligne par compte avec son débit et son crédit.
 * <p>
 * Colonnes reconnues par leur titre (peu importe l'ordre, majuscules et accents) : {@code Compte}, {@code Débit}, {@code Crédit}, et facultativement {@code Libellé} et {@code Tiers}. Sans titres, on suppose l'ordre
 * Compte, Libellé, Débit, Crédit. Les lignes de total sont ignorées. Les montants s'écrivent à la française (« 1 234 567,50 ») ou « 1234567.50 » ; un montant négatif est refusé plutôt que deviné.
 * Rien n'est écrit en base ici : ce n'est que de la lecture.
 */
public final class BalanceOuvertureParser {

    public static final int TAILLE_MAX_OCTETS = 5 * 1024 * 1024;
    private static final int LIGNES_MAX = 5000;
    private static final long XML_MAX_OCTETS = 40L * 1024 * 1024;

    /** Une ligne telle que lue dans le fichier, avant toute interprétation comptable. {@code erreur} non nul : la ligne est inutilisable. */
    public record LigneBrute(int ligne, String compte, String libelle, String tiers, BigDecimal debit, BigDecimal credit, String erreur) {
    }

    public record Lecture(List<LigneBrute> lignes, List<String> ignorees) {
    }

    private static final Pattern MONTANT_MILLIERS_POINT = Pattern.compile("^\\d{1,3}(\\.\\d{3})+$");

    private BalanceOuvertureParser() {
    }

    public static Lecture lire(byte[] contenu, String nomFichier) {
        if (contenu == null || contenu.length == 0) {
            throw refus("Le fichier est vide.");
        }
        if (contenu.length > TAILLE_MAX_OCTETS) {
            throw refus("Le fichier dépasse 5 Mo : une balance de reprise est bien plus légère. Vérifiez qu'il ne contient pas autre chose.");
        }
        String nom = nomFichier == null ? "" : nomFichier.toLowerCase();
        List<List<String>> lignes;
        if (nom.endsWith(".xlsx") || estZip(contenu)) {
            lignes = lireXlsx(contenu);
        } else if (nom.endsWith(".xls")) {
            throw refus("Le format Excel ancien (.xls) n'est pas lu. Dans Excel : Enregistrer sous → « Classeur Excel (.xlsx) » ou « CSV UTF-8 », puis réimportez.");
        } else {
            lignes = lireCsv(contenu);
        }
        return interpreter(lignes);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ interprétation

    private static Lecture interpreter(List<List<String>> lignes) {
        if (lignes.size() > LIGNES_MAX) {
            throw refus("Plus de " + LIGNES_MAX + " lignes : ce n'est pas une balance de reprise.");
        }
        int entete = -1;
        Map<String, Integer> colonnes = new HashMap<>();
        for (int i = 0; i < Math.min(lignes.size(), 15) && entete < 0; i++) {
            Map<String, Integer> candidat = new HashMap<>();
            List<String> cellules = lignes.get(i);
            for (int c = 0; c < cellules.size(); c++) {
                String role = roleColonne(cellules.get(c));
                if (role != null) {
                    candidat.putIfAbsent(role, c);
                }
            }
            if (candidat.containsKey("compte") && (candidat.containsKey("debit") || candidat.containsKey("credit"))) {
                entete = i;
                colonnes = candidat;
            }
        }
        int debut;
        if (entete >= 0) {
            debut = entete + 1;
        } else {
            // sans titres : le premier cadre qui commence par un numéro de compte donne l'ordre Compte, Libellé, Débit, Crédit
            debut = -1;
            for (int i = 0; i < Math.min(lignes.size(), 15); i++) {
                List<String> cellules = lignes.get(i);
                if (!cellules.isEmpty() && cellules.get(0).trim().matches("\\d{2,}") && cellules.size() >= 4) {
                    debut = i;
                    break;
                }
            }
            if (debut < 0) {
                throw refus("Colonnes introuvables. Le fichier doit avoir des colonnes « Compte », « Débit » et « Crédit » (« Libellé » et « Tiers » sont facultatives).");
            }
            colonnes = Map.of("compte", 0, "libelle", 1, "debit", 2, "credit", 3);
        }

        List<LigneBrute> resultat = new ArrayList<>();
        List<String> ignorees = new ArrayList<>();
        for (int i = debut; i < lignes.size(); i++) {
            List<String> cellules = lignes.get(i);
            String compte = cellule(cellules, colonnes.get("compte"));
            String libelle = cellule(cellules, colonnes.get("libelle"));
            String tiers = cellule(cellules, colonnes.get("tiers"));
            String debitBrut = cellule(cellules, colonnes.get("debit"));
            String creditBrut = cellule(cellules, colonnes.get("credit"));
            if (compte.isBlank() && libelle.isBlank() && debitBrut.isBlank() && creditBrut.isBlank()) {
                continue;
            }
            int numero = i + 1;
            String sansAccent = sansAccent(compte + " " + libelle);
            if (sansAccent.contains("total") && !compte.matches("\\d+")) {
                ignorees.add("Ligne " + numero + " ignorée : ligne de total.");
                continue;
            }
            String compteNet = compte.replaceAll("[\\s.]", "");
            if (compteNet.matches("\\d+[.,]0+")) {
                compteNet = compteNet.replaceAll("[.,]0+$", "");
            }
            if (!compteNet.matches("\\d{2,12}")) {
                resultat.add(new LigneBrute(numero, compte, libelle, tiers, null, null, compte.isBlank() ? "Numéro de compte manquant." : "Numéro de compte invalide : « " + compte + " » (chiffres uniquement)."));
                continue;
            }
            try {
                BigDecimal debit = montant(debitBrut);
                BigDecimal credit = montant(creditBrut);
                resultat.add(new LigneBrute(numero, compteNet, libelle, tiers, debit, credit, null));
            } catch (IllegalArgumentException e) {
                resultat.add(new LigneBrute(numero, compteNet, libelle, tiers, null, null, e.getMessage()));
            }
        }
        if (resultat.isEmpty()) {
            throw refus("Aucune ligne de compte trouvée sous les titres de colonnes.");
        }
        return new Lecture(resultat, ignorees);
    }

    /** Montant lu à la française ; vide = 0. Refuse le négatif et tout ce qui n'est pas un nombre. */
    static BigDecimal montant(String brut) {
        if (brut == null || brut.isBlank()) {
            return BigDecimal.ZERO;
        }
        String s = brut.trim().replaceAll("[\\s\\u00A0\\u202F]", "").replaceAll("(?i)(fcfa|xof|f|cfa)$", "");
        if (s.startsWith("-") || s.startsWith("(")) {
            throw new IllegalArgumentException("Montant négatif « " + brut.trim() + " » : un solde créditeur va dans la colonne Crédit, pas en négatif.");
        }
        boolean virgule = s.contains(",");
        boolean point = s.contains(".");
        if (virgule && point) {
            // le dernier séparateur est la décimale, l'autre sépare les milliers
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s = s.replace(".", "").replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (virgule) {
            s = s.replace(',', '.');
        } else if (point && MONTANT_MILLIERS_POINT.matcher(s).matches()) {
            s = s.replace(".", "");
        }
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.scale() > 2) {
                throw new IllegalArgumentException("Montant « " + brut.trim() + " » : deux décimales au plus.");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Montant illisible : « " + brut.trim() + " ».");
        }
    }

    private static String roleColonne(String titre) {
        String t = sansAccent(titre).replaceAll("[^a-z0-9]", "");
        if (t.isEmpty()) {
            return null;
        }
        return switch (t) {
            case "compte", "numerocompte", "ncompte", "numero", "ncomptes", "comptegeneral", "comptes", "account", "numdecompte", "nocompte" -> "compte";
            case "libelle", "intitule", "designation", "nomducompte", "libellecompte", "intitulecompte", "description" -> "libelle";
            case "tiers", "auxiliaire", "client", "fournisseur", "nomtiers", "tiersnom", "clientfournisseur" -> "tiers";
            case "debit", "soldedebit", "soldedebiteur", "debiteur", "sd", "mouvementdebit", "debitfcfa" -> "debit";
            case "credit", "soldecredit", "soldecrediteur", "crediteur", "sc", "mouvementcredit", "creditfcfa" -> "credit";
            default -> null;
        };
    }

    private static String cellule(List<String> cellules, Integer index) {
        return index == null || index >= cellules.size() || cellules.get(index) == null ? "" : cellules.get(index).trim();
    }

    static String sansAccent(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
    }

    private static ResponseStatusException refus(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ CSV

    private static List<List<String>> lireCsv(byte[] contenu) {
        String texte;
        try {
            texte = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(contenu)).toString();
        } catch (CharacterCodingException e) {
            texte = new String(contenu, java.nio.charset.Charset.forName("windows-1252")); // export Excel « CSV » classique en français
        }
        if (texte.startsWith("﻿")) {
            texte = texte.substring(1);
        }
        String premiere = texte.lines().filter((l) -> !l.isBlank()).findFirst().orElse("");
        char separateur = ';';
        int meilleur = -1;
        for (char c : new char[] {';', '\t', ',', '|'}) {
            int n = (int) premiere.chars().filter((x) -> x == c).count();
            if (n > meilleur) {
                meilleur = n;
                separateur = c;
            }
        }
        List<List<String>> lignes = new ArrayList<>();
        for (String ligne : texte.split("\r\n|\n|\r", -1)) {
            lignes.add(decouperCsv(ligne, separateur));
        }
        return lignes;
    }

    private static List<String> decouperCsv(String ligne, char separateur) {
        List<String> cellules = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        boolean guillemets = false;
        for (int i = 0; i < ligne.length(); i++) {
            char c = ligne.charAt(i);
            if (c == '"') {
                if (guillemets && i + 1 < ligne.length() && ligne.charAt(i + 1) == '"') {
                    courante.append('"');
                    i++;
                } else {
                    guillemets = !guillemets;
                }
            } else if (c == separateur && !guillemets) {
                cellules.add(courante.toString());
                courante.setLength(0);
            } else {
                courante.append(c);
            }
        }
        cellules.add(courante.toString());
        return cellules;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ XLSX

    private static boolean estZip(byte[] contenu) {
        return contenu.length > 4 && contenu[0] == 'P' && contenu[1] == 'K';
    }

    /** Un .xlsx est une archive zip de fichiers XML : on lit la première feuille et les textes partagés, sans bibliothèque. Entités externes et DTD sont refusées, la taille décompressée est plafonnée. */
    private static List<List<String>> lireXlsx(byte[] contenu) {
        Map<String, byte[]> parties = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(contenu))) {
            ZipEntry entree;
            while ((entree = zip.getNextEntry()) != null) {
                String nom = entree.getName();
                if (nom.equals("xl/sharedStrings.xml") || nom.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    byte[] lu = zip.readNBytes((int) Math.min(XML_MAX_OCTETS, Integer.MAX_VALUE));
                    total += lu.length;
                    if (total >= XML_MAX_OCTETS) {
                        throw refus("Le classeur est trop volumineux une fois décompressé.");
                    }
                    parties.put(nom, lu);
                }
            }
        } catch (IOException e) {
            throw refus("Ce fichier n'est pas un classeur Excel (.xlsx) lisible.");
        }
        String feuille = parties.keySet().stream().filter((n) -> n.startsWith("xl/worksheets/")).min((a, b) -> Integer.compare(numeroFeuille(a), numeroFeuille(b))).orElse(null);
        if (feuille == null) {
            throw refus("Ce fichier n'est pas un classeur Excel (.xlsx) lisible : aucune feuille trouvée.");
        }
        try {
            List<String> partages = parties.containsKey("xl/sharedStrings.xml") ? lireTextesPartages(parties.get("xl/sharedStrings.xml")) : List.of();
            return lireFeuille(parties.get(feuille), partages);
        } catch (XMLStreamException e) {
            throw refus("Le classeur Excel est illisible (" + e.getMessage() + ").");
        }
    }

    private static int numeroFeuille(String nom) {
        return Integer.parseInt(nom.replaceAll("\\D", ""));
    }

    private static XMLInputFactory usine() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true); // les classeurs réels déclarent leur espace de noms (et certains préfixent les balises)
        return f;
    }

    private static List<String> lireTextesPartages(byte[] xml) throws XMLStreamException {
        List<String> textes = new ArrayList<>();
        XMLStreamReader r = usine().createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder courant = null;
        boolean dansT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String nom = r.getLocalName();
                if (nom.equals("si")) {
                    courant = new StringBuilder();
                } else if (nom.equals("t") && courant != null) {
                    dansT = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && dansT && courant != null) {
                courant.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String nom = r.getLocalName();
                if (nom.equals("t")) {
                    dansT = false;
                } else if (nom.equals("si") && courant != null) {
                    textes.add(courant.toString());
                    courant = null;
                }
            }
        }
        return textes;
    }

    private static List<List<String>> lireFeuille(byte[] xml, List<String> partages) throws XMLStreamException {
        List<List<String>> lignes = new ArrayList<>();
        XMLStreamReader r = usine().createXMLStreamReader(new ByteArrayInputStream(xml));
        List<String> ligne = null;
        String type = null;
        int colonne = 0;
        StringBuilder valeur = null;
        boolean dansValeur = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> ligne = new ArrayList<>();
                    case "c" -> {
                        type = r.getAttributeValue(null, "t");
                        String ref = r.getAttributeValue(null, "r");
                        colonne = ref == null ? (ligne == null ? 0 : ligne.size()) : indexColonne(ref);
                        valeur = new StringBuilder();
                    }
                    case "v", "t" -> dansValeur = valeur != null;
                    default -> {
                    }
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && dansValeur && valeur != null) {
                valeur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> dansValeur = false;
                    case "c" -> {
                        if (ligne != null && valeur != null) {
                            while (ligne.size() < colonne) {
                                ligne.add("");
                            }
                            String brut = valeur.toString();
                            ligne.add(valeurCellule(type, brut, partages));
                        }
                        valeur = null;
                    }
                    case "row" -> {
                        if (ligne != null) {
                            lignes.add(ligne);
                        }
                        ligne = null;
                    }
                    default -> {
                    }
                }
            }
        }
        return lignes;
    }

    private static String valeurCellule(String type, String brut, List<String> partages) {
        if ("s".equals(type)) {
            try {
                int i = Integer.parseInt(brut.trim());
                return i >= 0 && i < partages.size() ? partages.get(i) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if (type == null || "n".equals(type)) {
            String b = brut.trim();
            if (b.isEmpty()) {
                return "";
            }
            try {
                return new BigDecimal(b).stripTrailingZeros().toPlainString(); // « 1.5E7 » devient « 15000000 »
            } catch (NumberFormatException e) {
                return b;
            }
        }
        return brut;
    }

    /** « A » → 0, « C » → 2, « AA » → 26. */
    private static int indexColonne(String reference) {
        int n = 0;
        for (char c : reference.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                n = n * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return Math.max(0, n - 1);
    }
}
