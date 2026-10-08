package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AmbiguousAccount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Construction des constats ({@link ControlResult}) d'un etat financier. Un constat par (exercice, etat, code) : la liste des comptes est dans les metadonnees
 * (anti-explosion, comme les autres detecteurs) ; il se resout seul quand la cause disparait. Gravite {@code ERROR} (jamais {@code BLOCKING} : ces constats ne
 * bloquent pas la cloture de l'exercice, ils bloquent la FINALISATION de l'etat).
 */
final class StatementControls {

    static final String ENTITY_TYPE = "ETAT_FINANCIER";
    private static final int MAX_COMPTES = 50;

    private StatementControls() {
    }

    static String entityId(UUID exerciceId, StatementType type) {
        return exerciceId + ":" + type;
    }

    static ControlResult comptesNonMappes(UUID entrepriseId, UUID exerciceId, StatementType type, List<AccountInput> comptes, String contexte) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("etat", type.name());
        metadata.put("nombreComptes", comptes.size());
        metadata.put("comptes", comptes.stream().limit(MAX_COMPTES).map(c -> c.number() + " " + c.label() + " (" + c.net().toPlainString() + ")").toList());
        return ControlResult.ouvert(ControlCodes.UNMAPPED_ACCOUNT, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE, entityId(exerciceId, type),
                comptes.size() + " compte(s) pertinent(s) pour l'etat " + type + " sans correspondance dans la definition reglementaire",
                "Aucune regle de correspondance de la definition versionnee ne classe ces comptes" + (contexte == null ? "" : " (" + contexte + ")")
                        + " : ils ne sont inclus dans aucune rubrique. Rien n'est range par defaut.",
                new ArrayList<>(comptes.stream().map(c -> String.valueOf(c.id())).limit(MAX_COMPTES).toList()), metadata,
                "Verifier le compte (plan de comptes de l'entreprise) ou attendre la source reglementaire de correspondance.");
    }

    static ControlResult correspondanceAmbigue(UUID entrepriseId, UUID exerciceId, StatementType type, List<AmbiguousAccount> comptes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("etat", type.name());
        metadata.put("nombreComptes", comptes.size());
        metadata.put("comptes", comptes.stream().limit(MAX_COMPTES).map(c -> c.account().number() + " -> " + c.lineCodes()).toList());
        return ControlResult.ouvert(ControlCodes.AMBIGUOUS_MAPPING, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE, entityId(exerciceId, type),
                comptes.size() + " compte(s) accepte(s) par plusieurs lignes de l'etat " + type,
                "Un compte ne peut appartenir qu'a un seul poste : ces comptes sont exclus des montants.",
                comptes.stream().map(c -> String.valueOf(c.account().id())).limit(MAX_COMPTES).toList(), metadata, "Trancher la regle de correspondance (nouvelle version de la definition).");
    }

    static ControlResult ecartDeResultat(UUID entrepriseId, UUID exerciceId, StatementType type, BigDecimal resultatEtat, BigDecimal resultatGrandLivre) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("etat", type.name());
        metadata.put("resultatEtat", resultatEtat);
        metadata.put("resultatGrandLivre", resultatGrandLivre);
        metadata.put("ecart", resultatGrandLivre.subtract(resultatEtat));
        return ControlResult.ouvert(ControlCodes.RESULT_MISMATCH, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE, entityId(exerciceId, type),
                "Le resultat net de l'etat (" + resultatEtat.toPlainString() + ") differe du resultat du grand livre (" + resultatGrandLivre.toPlainString() + ")",
                "Des comptes de gestion (classes 6, 7, 8) ne sont pas pris en compte par l'etat : voir les comptes non mappes ou ambigus.",
                List.of(), metadata, "Traiter les comptes non mappes ou ambigus, puis regenerer l'etat.");
    }

    static ControlResult ecartDeFluxDeTresorerie(UUID entrepriseId, UUID exerciceId, String invariant, BigDecimal attendu, BigDecimal obtenu) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("invariant", invariant);
        metadata.put("attendu", attendu);
        metadata.put("obtenu", obtenu);
        return ControlResult.ouvert(ControlCodes.CASH_FLOW_MISMATCH, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE,
                entityId(exerciceId, StatementType.TFT),
                "Tableau des flux de tresorerie incoherent : " + invariant + " (attendu " + attendu.toPlainString() + ", obtenu " + obtenu.toPlainString() + ")",
                "Les formules officielles du TFT ne retombent pas : verifier les flux et les tresoreries d'ouverture et de cloture.",
                List.of(), metadata, "Corriger les flux a l'origine de l'ecart.");
    }

    static ControlResult composantCafgNonResolu(UUID entrepriseId, UUID exerciceId, Map<String, List<String>> comptesParRaison) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("comptesParTerme", comptesParRaison);
        return ControlResult.ouvert(ControlCodes.UNRESOLVED_CAFG_COMPONENT, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE,
                entityId(exerciceId, StatementType.TFT) + ":CAFG",
                "CAFG non calculable : mouvements sur des comptes dont le terme de la CAFG n'est pas resolu par la source",
                comptesParRaison.entrySet().stream().map(e -> e.getKey() + " : " + String.join(", ", e.getValue())).reduce((a, b) -> a + " ; " + b).orElse(""),
                List.of(), metadata, "Attendre la regle reglementaire de rattachement de ces termes (aucun mapping n'est invente).");
    }

    static ControlResult evenementTresorerieNonQualifie(UUID entrepriseId, UUID exerciceId, List<String> ecritures, List<String> lignesTft) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("lignesTft", lignesTft);
        metadata.put("ecritures", ecritures.stream().limit(MAX_COMPTES).toList());
        metadata.put("nombreEcritures", ecritures.size());
        return ControlResult.ouvert(ControlCodes.UNQUALIFIED_CASH_EVENT, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE,
                entityId(exerciceId, StatementType.TFT) + ":EVENEMENTS",
                ecritures.size() + " ecriture(s) de tresorerie sans evenement qualifie : lignes " + String.join(", ", lignesTft) + " non calculables",
                "La nature monetaire d'un mouvement de financement ou d'investissement n'est jamais deduite du numero de compte : elle est declaree a la saisie (evenement de tresorerie).",
                List.of(), metadata, "Qualifier l'evenement de tresorerie sur l'ecriture (nouvelle ecriture corrigee).");
    }

    static ControlResult desequilibreBilan(UUID entrepriseId, UUID exerciceId, BigDecimal totalActif, BigDecimal totalPassif) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("BZ", totalActif);
        metadata.put("DZ", totalPassif);
        metadata.put("ecart", totalActif.subtract(totalPassif));
        return ControlResult.ouvert(ControlCodes.BALANCE_SHEET_IMBALANCE, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entrepriseId, ENTITY_TYPE, entityId(exerciceId, StatementType.BILAN_ACTIF),
                "Le total de l'Actif (" + totalActif.toPlainString() + ") differe du total du Passif (" + totalPassif.toPlainString() + ")",
                "Tous les comptes sont mappes et aucune ligne n'est partielle : un ecart (tolerance nulle) signale une regle de correspondance erronee ou un double classement.",
                List.of(), metadata, "Verifier les regles de correspondance et le resultat de l'exercice.");
    }

    static ControlResult noteIncomplete(UUID entrepriseId, UUID exerciceId, String codeNote, String titre, List<NotesService.NoteGap> manques) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("noteCode", codeNote);
        metadata.put("manques", manques.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteCode", g.noteCode());
            m.put("section", g.section());
            m.put("missingInformation", g.missingInformation());
            m.put("kind", g.kind());
            m.put("fieldCodes", g.fieldCodes());
            return m;
        }).toList());
        return ControlResult.ouvert(ControlCodes.REQUIRED_NOTE_INCOMPLETE, ControlCategory.ACCOUNTING, ControlSeverity.WARNING, entrepriseId, ENTITY_TYPE,
                entityId(exerciceId, StatementType.NOTES_R4) + ":" + codeNote, "Note " + codeNote + " (" + titre + ") incomplete",
                manques.stream().map(g -> g.section() + " : " + g.missingInformation()).reduce((x, y) -> x + " ; " + y).orElse(""), List.of(), metadata,
                "Completer les informations ou les comptes manquants.");
    }
}
