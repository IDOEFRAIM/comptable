package com.example.comptable.collecte.service;

import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.domain.TypeMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Textes des messages de collecte : des TEMPLATES parametrables, jamais du texte genere librement. Le message ne contient
 * que ce que le client comprend (montant, date, tiers, periode) - jamais d'identifiant interne, de code de constat ni de
 * numero technique. Le meme contenu structure ({@code payload}) sert l'apercu du comptable, le texte et les parametres du
 * template WhatsApp.
 *
 * <p>Payload : {@code entreprise}, {@code destinataire}, {@code periode} (AAAA-MM, facultatif), {@code lienDepot}
 * (facultatif), {@code elements} : liste de {@code {libelle, montant, devise, date, tiers}}.</p>
 */
@Component
public class MessageTemplates {

    public static final String DEMANDE = "DOCUMENT_REQUEST";
    public static final String RELANCE = "DOCUMENT_REMINDER";
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int LIMITE_PARAMETRE = 900;

    private final CollecteConfig config;

    public MessageTemplates(CollecteConfig config) {
        this.config = config;
    }

    /** Ce qui part chez le fournisseur : nom du template approuve et valeurs positionnelles ({{1}}, {{2}}, {{3}}). */
    public record Rendu(String texte, String templateFournisseur, List<String> parametres) {
    }

    public static String codeTemplate(TypeMessage type) {
        return type == TypeMessage.REMINDER ? RELANCE : DEMANDE;
    }

    public Rendu rendre(OutboundMessage message) {
        return rendre(message.getKind(), message.getPayload(), message.getRecipientName());
    }

    @SuppressWarnings("unchecked")
    public Rendu rendre(TypeMessage type, Map<String, Object> payload, String nomDestinataire) {
        List<Map<String, Object>> elements = payload.get("elements") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        String entreprise = String.valueOf(payload.getOrDefault("entreprise", ""));
        String periode = libellePeriode(payload.get("periode"));
        String lien = payload.get("lienDepot") instanceof String s && !s.isBlank() ? s : null;
        String nom = nomDestinataire != null && !nomDestinataire.isBlank() ? nomDestinataire : "";

        StringBuilder texte = new StringBuilder("Bonjour").append(nom.isEmpty() ? "" : " " + nom).append(",\n\n");
        if (type == TypeMessage.REMINDER) {
            texte.append("Petit rappel : ");
        }
        String dossier = periode != null ? "votre dossier de " + periode : "votre dossier comptable";
        if (elements.size() == 1) {
            texte.append("pour finaliser ").append(dossier).append(" (").append(entreprise).append("), il nous manque le justificatif suivant :\n\n")
                    .append(ligne(elements.get(0))).append("\n");
        } else {
            texte.append("pour ").append(dossier).append(" (").append(entreprise).append("), nous avons encore besoin de ")
                    .append(elements.size()).append(" justificatifs :\n\n");
            for (int i = 0; i < elements.size(); i++) {
                texte.append(i + 1).append(". ").append(ligne(elements.get(i))).append("\n");
            }
        }
        texte.append("\nMerci de nous transmettre ").append(elements.size() == 1 ? "la facture ou le justificatif correspondant" : "les pieces correspondantes")
                .append(lien != null ? " ici : " + lien : " en repondant a ce message").append(".");

        List<String> parametres = List.of(nom.isEmpty() ? "client" : nom, entreprise, resume(elements, lien));
        String template = type == TypeMessage.REMINDER ? config.templateRelance() : config.templateDemande();
        return new Rendu(texte.toString(), template, parametres);
    }

    /** Ligne visible par le client : « 125 000 FCFA - 03/09/2026 - Station XYZ ». */
    private String ligne(Map<String, Object> e) {
        List<String> morceaux = new ArrayList<>();
        String montant = montant(e.get("montant"), e.get("devise"));
        if (montant != null) {
            morceaux.add(montant);
        }
        String date = date(e.get("date"));
        if (date != null) {
            morceaux.add(date);
        }
        Object libelle = e.get("libelle");
        if (libelle instanceof String s && !s.isBlank()) {
            morceaux.add(s);
        } else if (e.get("tiers") instanceof String t && !t.isBlank()) {
            morceaux.add(t);
        }
        return String.join(" - ", morceaux);
    }

    /** Un parametre de template WhatsApp ne peut pas contenir de retour a la ligne : elements separes par « ; ». */
    private String resume(List<Map<String, Object>> elements, String lien) {
        StringBuilder sb = new StringBuilder();
        if (elements.size() > 1) {
            sb.append(elements.size()).append(" justificatifs : ");
        }
        for (int i = 0; i < elements.size(); i++) {
            sb.append(i > 0 ? " ; " : "").append(ligne(elements.get(i)));
        }
        if (lien != null) {
            sb.append(" - Deposer : ").append(lien);
        }
        String propre = sb.toString().replaceAll("\\s+", " ").trim();
        return propre.length() > LIMITE_PARAMETRE ? propre.substring(0, LIMITE_PARAMETRE - 1) + "…" : propre;
    }

    static String montant(Object montant, Object devise) {
        if (montant == null) {
            return null;
        }
        BigDecimal valeur = new BigDecimal(String.valueOf(montant));
        DecimalFormatSymbols symboles = new DecimalFormatSymbols(Locale.FRANCE);
        symboles.setGroupingSeparator(' ');
        String texte = new DecimalFormat("#,##0.##", symboles).format(valeur);
        String code = devise == null ? "XOF" : String.valueOf(devise);
        return texte + " " + ("XOF".equals(code) || "XAF".equals(code) ? "FCFA" : code);
    }

    private static String date(Object date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(date)).format(JOUR);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String libellePeriode(Object periode) {
        if (periode == null) {
            return null;
        }
        try {
            LocalDate debut = LocalDate.parse(periode + "-01");
            String mois = debut.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE);
            return mois + " " + debut.getYear();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
