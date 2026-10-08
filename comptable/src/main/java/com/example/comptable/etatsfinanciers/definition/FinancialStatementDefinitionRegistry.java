package com.example.comptable.etatsfinanciers.definition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Charge et VALIDE les definitions reglementaires versionnees (ressources {@code etats-financiers/<cadre>/<version>/*.json}). Les definitions sont des fichiers du
 * depot : une version publiee n'est jamais modifiee (une evolution = un nouveau dossier de version), ce qui rend une definition utilisee par un etat FINAL
 * immuable ; l'empreinte SHA-256 du contenu est figee dans les snapshots.
 *
 * <p>Regles de validation (un demarrage echoue si elles sont violees) : codes et ordres uniques, parents et references de formule existants, formules ne referencant
 * que des lignes anterieures, seules des regles {@code VERIFIED} et non {@code CORROBORATED_ONLY} sont autorisees, aucune regle sur une ligne qui n'est pas
 * {@code MAPPED}, et aucun chevauchement de regles entre deux lignes (un compte ne peut pas etre range dans deux postes).</p>
 */
@Component
public class FinancialStatementDefinitionRegistry {

    /** Identifiant applicatif du referentiel (PAS un identifiant officiel OHADA). */
    public static final String FRAMEWORK = "SYSCOHADA_REVISE_AUDCIF_2017";
    public static final String VERSION_COURANTE = "2";

    private static final Map<StatementType, String> FICHIERS = new EnumMap<>(Map.of(
            StatementType.COMPTE_RESULTAT, "compte-resultat.json",
            StatementType.BILAN_ACTIF, "bilan-actif.json",
            StatementType.BILAN_PASSIF, "bilan-passif.json",
            StatementType.TFT, "tft.json"));

    private final Map<StatementType, FinancialStatementDefinition> definitions = new EnumMap<>(StatementType.class);
    private final NotesCatalogue notes;

    public FinancialStatementDefinitionRegistry() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        try {
            for (Map.Entry<StatementType, String> e : FICHIERS.entrySet()) {
                byte[] contenu = lire(e.getValue());
                FinancialStatementDefinition definition = mapper.readValue(contenu, FinancialStatementDefinition.class).avecEmpreinte(sha256(contenu));
                valider(definition);
                definitions.put(e.getKey(), definition);
            }
            byte[] contenuNotes = lire("notes-r4.json");
            NotesCatalogue brut = mapper.readValue(contenuNotes, NotesCatalogue.class);
            notes = new NotesCatalogue(brut.framework(), brut.version(), brut.statementType(), brut.presentationSystem(), brut.effectiveFrom(), brut.source(), brut.notes(), brut.rule(),
                    sha256(contenuNotes));
        } catch (IOException ex) {
            throw new IllegalStateException("Definitions d'etats financiers illisibles", ex);
        }
    }

    public FinancialStatementDefinition courante(StatementType type) {
        FinancialStatementDefinition definition = definitions.get(type);
        if (definition == null) {
            throw new IllegalArgumentException("Aucune definition pour " + type);
        }
        return definition;
    }

    public NotesCatalogue notes() {
        return notes;
    }

    private static byte[] lire(String fichier) throws IOException {
        String chemin = "etats-financiers/" + FRAMEWORK + "/" + VERSION_COURANTE + "/" + fichier;
        try (InputStream in = FinancialStatementDefinitionRegistry.class.getClassLoader().getResourceAsStream(chemin)) {
            if (in == null) {
                throw new IllegalStateException("Definition introuvable : " + chemin);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] contenu) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Validation structurelle d'une definition ; public pour etre testee sur des definitions volontairement invalides. */
    public static void valider(FinancialStatementDefinition definition) {
        java.util.Set<String> codes = new java.util.HashSet<>();
        java.util.Set<Integer> ordres = new java.util.HashSet<>();
        for (StatementLineDefinition ligne : definition.lines()) {
            exiger(codes.add(ligne.code()), definition, ligne, "code duplique");
            exiger(ordres.add(ligne.order()), definition, ligne, "ordre duplique");
        }
        for (StatementLineDefinition ligne : definition.lines()) {
            exiger(ligne.parentCode() == null || codes.contains(ligne.parentCode()), definition, ligne, "parent inconnu " + ligne.parentCode());
            if (ligne.calculationType() != CalculationType.MAPPED) {
                exiger(ligne.mappingRules().isEmpty(), definition, ligne, "une ligne non MAPPED ne porte aucune regle de correspondance");
            } else {
                exiger(!ligne.mappingRules().isEmpty(), definition, ligne, "une ligne MAPPED exige au moins une regle");
            }
            for (AccountMappingRule regle : ligne.mappingRules()) {
                exiger(regle.verificationStatus() == VerificationStatus.VERIFIED, definition, ligne, "seule une regle VERIFIED peut etre codee");
                exiger(regle.provenanceType() != ProvenanceType.CORROBORATED_ONLY, definition, ligne, "une corroboration seule n'entre jamais dans une definition");
                exiger(!regle.includePrefixes().isEmpty(), definition, ligne, "regle sans prefixe");
            }
            if (ligne.calculationType() == CalculationType.FORMULA) {
                exiger(ligne.formula() != null && !ligne.formula().isBlank(), definition, ligne, "formule absente");
                for (String code : FormulaEvaluator.codesReferences(ligne.formula())) {
                    exiger(codes.contains(code), definition, ligne, "formule : ligne inconnue " + code);
                }
            }
        }
        verifierAbsenceDeCycle(definition);
        verifierAbsenceDeChevauchement(definition);
    }

    private static void verifierAbsenceDeCycle(FinancialStatementDefinition definition) {
        Map<String, List<String>> deps = new java.util.HashMap<>();
        for (StatementLineDefinition l : definition.lines()) {
            deps.put(l.code(), l.calculationType() == CalculationType.FORMULA ? List.copyOf(FormulaEvaluator.codesReferences(l.formula())) : List.of());
        }
        java.util.Set<String> termines = new java.util.HashSet<>();
        for (String code : deps.keySet()) {
            visiter(code, deps, termines, new java.util.HashSet<>(), definition);
        }
    }

    private static void visiter(String code, Map<String, List<String>> deps, java.util.Set<String> termines, java.util.Set<String> enCours, FinancialStatementDefinition definition) {
        if (termines.contains(code)) {
            return;
        }
        if (!enCours.add(code)) {
            throw new IllegalStateException("Definition " + definition.statementType() + " : cycle de formules sur " + code);
        }
        for (String d : deps.get(code)) {
            visiter(d, deps, termines, enCours, definition);
        }
        enCours.remove(code);
        termines.add(code);
    }

    private static void verifierAbsenceDeChevauchement(FinancialStatementDefinition definition) {
        List<StatementLineDefinition> lignes = definition.lines();
        for (int i = 0; i < lignes.size(); i++) {
            for (int j = i + 1; j < lignes.size(); j++) {
                for (AccountMappingRule a : lignes.get(i).mappingRules()) {
                    for (AccountMappingRule b : lignes.get(j).mappingRules()) {
                        if (a.role() != b.role() || (a.side() != BalanceSide.ANY && b.side() != BalanceSide.ANY && a.side() != b.side())) {
                            continue;
                        }
                        for (String pa : a.includePrefixes()) {
                            for (String pb : b.includePrefixes()) {
                                if (pa.startsWith(pb) || pb.startsWith(pa)) {
                                    String specifique = pa.length() >= pb.length() ? pa : pb;
                                    if (a.accepte(specifique) && b.accepte(specifique)) {
                                        throw new IllegalStateException("Definition " + definition.statementType() + " : chevauchement entre " + lignes.get(i).code()
                                                + " et " + lignes.get(j).code() + " sur le prefixe " + specifique);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void exiger(boolean condition, FinancialStatementDefinition definition, StatementLineDefinition ligne, String message) {
        if (!condition) {
            throw new IllegalStateException("Definition " + definition.statementType() + " v" + definition.version() + ", ligne " + ligne.code() + " : " + message);
        }
    }

    /** Catalogue officiel des Notes (Fiche R4, p. 989). */
    public record NotesCatalogue(String framework, String version, String statementType, String presentationSystem, String effectiveFrom, SourceReference source,
                                 List<NoteDefinition> notes, String rule, String contentHash) {
        public NotesCatalogue {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /** Une note du catalogue R4. {@code automationType} est une PROPOSITION PRODUIT (distincte du statut de verification et de la provenance). */
    public record NoteDefinition(String code, String title, int order, String applicability, ApplicabilityRule applicabilityRule, String automationType, String automationTypeBasis,
                                 VerificationStatus verificationStatus, ProvenanceType provenanceType, String officialSourceReference, String detailedSchema, String readiness,
                                 List<String> manualRequiredSections, List<String> autoCalculatedSections, String remark, NoteTable table, List<NoteSectionDefinition> sections) {
        public NoteDefinition {
            manualRequiredSections = manualRequiredSections == null ? List.of() : List.copyOf(manualRequiredSections);
            autoCalculatedSections = autoCalculatedSections == null ? List.of() : List.copyOf(autoCalculatedSections);
            sections = sections == null ? List.of() : List.copyOf(sections);
        }

        /** {@code COMPLETE} si le schema imprime de la note est entierement lu ; {@code PARTIAL} (colonnes rognees) : la note n'est jamais finalisable. */
        public String schemaStatus() {
            return "COMPLETE".equals(detailedSchema) ? "COMPLETE" : "PARTIAL";
        }

        public NoteSectionDefinition section(String code) {
            return sections.stream().filter(s -> s.code().equals(code)).findFirst().orElse(null);
        }
    }

    /** Section d'une note : generee (aucun champ saisissable), manuelle ou mixte. Seuls les champs documentes par la source sont declares. */
    public record NoteSectionDefinition(String code, String label, String sourceType, boolean required, List<NoteFieldDefinition> fields) {
        public NoteSectionDefinition {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public NoteFieldDefinition field(String code) {
            return fields.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
        }
    }

    /** Champ saisissable : type TEXT, DECIMAL, INTEGER, DATE, BOOLEAN ou ENUM (valeurs dans {@code options}). */
    public record NoteFieldDefinition(String code, String label, String type, boolean required, List<String> options, String sourceType) {
        public NoteFieldDefinition {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** Regle d'applicabilite imprimee (ex. Note 35 : effectif strictement superieur a 250). */
    public record ApplicabilityRule(String type, int threshold, String page, String basis) {
    }

    /** Tableau chiffre d'une note implementee : base de calcul (soldes de cloture ou activite de la periode), perimetre de comptes et lignes. */
    public record NoteTable(String basis, String page, List<String> domainPrefixes, List<String> excludedFromDomain, List<NoteLine> lines) {
        public NoteTable {
            domainPrefixes = domainPrefixes == null ? List.of() : List.copyOf(domainPrefixes);
            excludedFromDomain = excludedFromDomain == null ? List.of() : List.copyOf(excludedFromDomain);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Ligne (LINE : comptes) ou total (TOTAL : formule sur d'autres lignes de la note). */
    public record NoteLine(String kind, String code, String label, List<String> includePrefixes, List<String> excludePrefixes, String sign, String formula) {
        public NoteLine {
            includePrefixes = includePrefixes == null ? List.of() : List.copyOf(includePrefixes);
            excludePrefixes = excludePrefixes == null ? List.of() : List.copyOf(excludePrefixes);
        }
    }
}
