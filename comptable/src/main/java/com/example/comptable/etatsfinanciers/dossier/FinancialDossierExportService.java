package com.example.comptable.etatsfinanciers.dossier;

import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotRepository;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;

/**
 * Export imprimable du dossier financier (modele HTML, avant tout PDF) : memes libelles et structures que les definitions versionnees, aucune mise en page reglementaire inventee.
 * Le dossier FINAL est rendu UNIQUEMENT depuis le snapshot immuable ; un export d'un dossier non final porte un filigrane et un bandeau BROUILLON visibles. Les notes non applicables
 * ne figurent pas dans les etats publies (elles restent dans la tracabilite du snapshot).
 */
@Service
@RequiredArgsConstructor
public class FinancialDossierExportService {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final EntrepriseAccessService entrepriseAccessService;
    private final FinancialStatementSnapshotRepository snapshotRepository;
    private final FinancialStatementSnapshotService snapshotService;

    /** Export FINAL : lu dans le snapshot, jamais dans les tables vivantes. Autre entreprise : 404. */
    @Transactional(readOnly = true)
    public String exporterFinal(UUID entrepriseId, UUID snapshotId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        var snapshot = snapshotRepository.findByIdAndEntrepriseId(snapshotId, entrepriseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Etat financier introuvable"));
        if (snapshot.getStatementType() != StatementType.DOSSIER_COMPLET) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Seul le snapshot du dossier complet s'exporte");
        }
        try {
            JsonNode payload = MAPPER.readTree(snapshot.getPayload());
            return rendre(payload, true, "Snapshot " + snapshot.getId() + " - empreinte " + snapshot.getPayloadSha256());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Export BROUILLON du dossier courant (calcule a la demande, jamais presente comme final). */
    @Transactional
    public String exporterBrouillon(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        JsonNode payload = MAPPER.valueToTree(snapshotService.apercu(entrepriseId, exerciceId, principal));
        return rendre(payload, false, "Apercu non finalise - ne pas utiliser comme dossier final");
    }

    // ------------------------------------------------------------------------------------------------ rendu

    private static String rendre(JsonNode d, boolean fige, String reference) {
        StringBuilder h = new StringBuilder(32_000);
        String devise = texte(d.path("entreprise"), "devise", "XOF");
        h.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\"><title>Dossier financier</title><style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#111;margin:24px;position:relative}")
                .append("h1{font-size:20px;margin:0}h2{font-size:15px;margin:22px 0 6px;border-bottom:1px solid #444;padding-bottom:2px}h3{font-size:13px;margin:14px 0 4px}")
                .append("table{border-collapse:collapse;width:100%;margin-bottom:8px}th,td{border:1px solid #bbb;padding:3px 6px;text-align:left}")
                .append("td.n,th.n{text-align:right;white-space:nowrap}tr.total td{font-weight:bold;background:#f0f0f0}")
                .append(".bandeau{padding:6px 10px;font-weight:bold;margin-bottom:12px}")
                .append(".brouillon{background:#fff3cd;border:2px solid #b58900;color:#7a5c00}.final{background:#e7f6e7;border:2px solid #2e7d32;color:#1b5e20}")
                .append(".filigrane{position:fixed;top:40%;left:5%;font-size:120px;color:rgba(200,0,0,.12);transform:rotate(-25deg);pointer-events:none;z-index:0}")
                .append(".nd{color:#a33;font-style:italic}small{color:#555}@media print{.noprint{display:none}}")
                .append("</style></head><body>");
        if (!fige) {
            h.append("<div class=\"filigrane\">BROUILLON</div>");
        }
        h.append("<div class=\"bandeau ").append(fige ? "final" : "brouillon").append("\">")
                .append(fige ? "DOSSIER FINANCIER FINAL (version immuable)" : "BROUILLON - dossier non finalise, susceptible d'etre incomplet").append("</div>");
        h.append("<h1>Dossier financier</h1>");
        JsonNode e = d.path("entreprise");
        JsonNode x = d.path("exercice");
        h.append("<p><b>").append(esc(texte(e, "raisonSociale", ""))).append("</b>");
        if (!texte(e, "numeroContribuable", "").isBlank()) {
            h.append(" - N&deg; contribuable ").append(esc(texte(e, "numeroContribuable", "")));
        }
        h.append("<br>Exercice du ").append(esc(texte(x, "debut", ""))).append(" au ").append(esc(texte(x, "fin", ""))).append(" - montants en ").append(esc(devise)).append("</p>");

        etat(h, "Compte de resultat", d.path("compteResultat"), false, false);
        h.append("<h2>Bilan</h2>");
        etat(h, "Actif", d.path("bilanActif"), true, true);
        etat(h, "Passif", d.path("bilanPassif"), false, true);
        etat(h, "Tableau des flux de tresorerie", d.path("tft"), false, false);

        h.append("<h2>Notes annexes</h2>");
        JsonNode notes = d.path("notes").path("notes");
        if (!notes.isArray() || notes.isEmpty()) {
            h.append("<p><small>Aucune note applicable jointe.</small></p>");
        } else {
            for (JsonNode n : notes) {
                h.append("<h3>Note ").append(esc(texte(n, "noteCode", ""))).append(" - ").append(esc(texte(n, "title", ""))).append("</h3>");
                JsonNode lignes = n.path("generatedData");
                if (lignes.isArray() && !lignes.isEmpty()) {
                    h.append("<table><tr><th>Libelle</th><th class=\"n\">N</th><th class=\"n\">N-1</th></tr>");
                    for (JsonNode l : lignes) {
                        boolean total = "TOTAL".equals(texte(l, "kind", ""));
                        h.append("<tr").append(total ? " class=\"total\"" : "").append("><td>").append(esc(texte(l, "label", ""))).append("</td><td class=\"n\">")
                                .append(montant(l.path("amountN"))).append("</td><td class=\"n\">").append(montant(l.path("amountNMinus1"))).append("</td></tr>");
                    }
                    h.append("</table>");
                }
                JsonNode manuel = n.path("manualData");
                if (manuel.isObject()) {
                    manuel.fields().forEachRemaining(section -> section.getValue().fields().forEachRemaining(champ -> {
                        h.append("<p><b>").append(esc(section.getKey())).append("</b> : ").append(esc(texte(champ.getValue(), "value", "")));
                        if (!texte(champ.getValue(), "comment", "").isBlank()) {
                            h.append("<br><small>").append(esc(texte(champ.getValue(), "comment", ""))).append("</small>");
                        }
                        h.append("</p>");
                    }));
                }
                if (!texte(n, "manualComment", "").isBlank()) {
                    h.append("<p><small>").append(esc(texte(n, "manualComment", ""))).append("</small></p>");
                }
            }
        }

        h.append("<h2>Informations de version</h2><p><small>Cadre reglementaire : ").append(esc(texte(d, "frameworkVersion", ""))).append("<br>Genere le ")
                .append(esc(texte(d, "generatedAt", ""))).append("<br>").append(esc(reference)).append("<br>Empreintes des definitions : ");
        d.path("definitionHashes").fields().forEachRemaining(f -> h.append(esc(f.getKey())).append(" ").append(esc(f.getValue().asText().substring(0, Math.min(12, f.getValue().asText().length()))))
                .append(" ; "));
        h.append("</small></p></body></html>");
        return h.toString();
    }

    private static void etat(StringBuilder h, String titre, JsonNode etat, boolean actif, boolean sousTitre) {
        h.append(sousTitre ? "<h3>" : "<h2>").append(esc(titre)).append(sousTitre ? "</h3>" : "</h2>");
        JsonNode lignes = etat.path("lines");
        if (!lignes.isArray()) {
            return;
        }
        h.append("<table><tr><th>Code</th><th>Libelle</th>");
        if (actif) {
            h.append("<th class=\"n\">Brut</th><th class=\"n\">Amort. / deprec.</th><th class=\"n\">Net N</th><th class=\"n\">Net N-1</th>");
        } else {
            h.append("<th class=\"n\">N</th><th class=\"n\">N-1</th>");
        }
        h.append("</tr>");
        for (JsonNode l : lignes) {
            boolean total = "FORMULA".equals(texte(l, "calculationType", ""));
            boolean complete = !l.has("complete") || l.get("complete").asBoolean(true);
            h.append("<tr").append(total ? " class=\"total\"" : "").append("><td>").append(esc(texte(l, "code", ""))).append("</td><td>").append(esc(texte(l, "label", ""))).append("</td>");
            if (actif) {
                h.append("<td class=\"n\">").append(montant(l.path("grossN"))).append("</td><td class=\"n\">").append(montant(l.path("depreciationN"))).append("</td>");
            }
            h.append("<td class=\"n\">").append(complete ? montant(l.path("amountN")) : "<span class=\"nd\">Incomplet</span>").append("</td><td class=\"n\">")
                    .append(montant(l.path("amountNMinus1"))).append("</td></tr>");
        }
        h.append("</table>");
    }

    private static String montant(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "<span class=\"nd\">Non determine</span>";
        }
        BigDecimal v = n.isNumber() ? n.decimalValue() : new BigDecimal(n.asText());
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRANCE);
        s.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.##", s).format(v);
    }

    private static String texte(JsonNode n, String champ, String defaut) {
        JsonNode v = n == null ? null : n.get(champ);
        return v == null || v.isNull() ? defaut : v.asText();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
