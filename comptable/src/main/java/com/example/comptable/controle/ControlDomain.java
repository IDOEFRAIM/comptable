package com.example.comptable.controle;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Domaine de REVISION d'un controle ou d'un constat (banque, clients, fournisseurs, documents, TVA, stock, immobilisations, journal, comptabilite,
 * cloture). Derive du CODE du controle (jamais d'une nouvelle colonne) : un seul endroit decide dans quel domaine tombe un controle, pour la
 * pre-cloture comme pour l'espace de revision.
 */
public enum ControlDomain {
    BANK("Banque"),
    CUSTOMER("Clients"),
    SUPPLIER("Fournisseurs"),
    DOCUMENT("Documents"),
    VAT("TVA"),
    STOCK("Stock"),
    FIXED_ASSET("Immobilisations"),
    JOURNAL("Journal"),
    ACCOUNTING("Comptabilite"),
    CLOSING("Cloture"),
    OTHER("Autres");

    private final String libelle;

    ControlDomain(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }

    private static final Map<String, ControlDomain> PAR_CODE = Map.ofEntries(
            // Constats (codes de controle)
            Map.entry(ControlCodes.UNMATCHED_PAYMENT, BANK), Map.entry(ControlCodes.AMBIGUOUS_BANK_MATCH, BANK), Map.entry(ControlCodes.MATCH_TO_CONFIRM, BANK),
            Map.entry(ControlCodes.BANK_DUPLICATE_TRANSACTION, BANK), Map.entry(ControlCodes.INTERNAL_TRANSFER_TO_CONFIRM, BANK),
            Map.entry(ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS, BANK), Map.entry(ControlCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY, BANK),
            Map.entry(ControlCodes.CLIENT_CREDIT_BALANCE, CUSTOMER), Map.entry(ControlCodes.SUPPLIER_DEBIT_BALANCE, SUPPLIER),
            Map.entry(ControlCodes.VAT_INCONSISTENCY, VAT), Map.entry(ControlCodes.TAX_DECLARATION_MISSING, VAT), Map.entry(ControlCodes.TAX_DECLARATION_LATE, VAT),
            Map.entry(ControlCodes.STOCK_INCONSISTENCY, STOCK),
            Map.entry(ControlCodes.FIXED_ASSET_WITHOUT_SOURCE, FIXED_ASSET), Map.entry(ControlCodes.POSSIBLE_DOUBLE_ACQUISITION, FIXED_ASSET),
            Map.entry(ControlCodes.ACCOUNT_NOT_CONFIGURED, ACCOUNTING), Map.entry(ControlCodes.ACCOUNT_AMBIGUOUS, ACCOUNTING), Map.entry(ControlCodes.ACCOUNT_INVALID, ACCOUNTING),
            Map.entry(ControlCodes.UNMAPPED_ACCOUNT, ACCOUNTING), Map.entry(ControlCodes.AMBIGUOUS_MAPPING, ACCOUNTING), Map.entry(ControlCodes.RESULT_MISMATCH, ACCOUNTING),
            Map.entry(ControlCodes.CASH_FLOW_MISMATCH, ACCOUNTING), Map.entry(ControlCodes.BALANCE_SHEET_IMBALANCE, ACCOUNTING),
            Map.entry(ControlCodes.UNRESOLVED_CAFG_COMPONENT, ACCOUNTING), Map.entry(ControlCodes.UNQUALIFIED_CASH_EVENT, ACCOUNTING),
            Map.entry(ControlCodes.REQUIRED_NOTE_INCOMPLETE, ACCOUNTING),
            Map.entry(ControlCodes.DRAFT_ENTRY_OLD, JOURNAL), Map.entry(ControlCodes.CLOSING_BLOCKER, CLOSING),
            // Points de la checklist de pre-cloture
            Map.entry("banque", BANK), Map.entry("caisse", BANK), Map.entry("rapprochement", BANK),
            Map.entry("clients", CUSTOMER), Map.entry("lettrage", CUSTOMER), Map.entry("fournisseurs", SUPPLIER),
            Map.entry("documents_non_comptabilises", DOCUMENT), Map.entry("pieces_manquantes", DOCUMENT),
            Map.entry("fiscalite", VAT), Map.entry("stocks", STOCK),
            Map.entry("immobilisations", FIXED_ASSET), Map.entry("amortissements", FIXED_ASSET), Map.entry("depreciations", FIXED_ASSET),
            Map.entry("equilibre_ecritures", JOURNAL), Map.entry("brouillons", JOURNAL),
            Map.entry("comptes_desactives", ACCOUNTING), Map.entry("mappings_parametrage", ACCOUNTING), Map.entry("provisions", ACCOUNTING),
            Map.entry("exercices", CLOSING), Map.entry("regularisations", CLOSING), Map.entry("resultat", CLOSING), Map.entry("findings", CLOSING));

    private static final Set<String> CODES_DOCUMENT = Set.of(ControlCodes.MISSING_DOCUMENT, ControlCodes.DUPLICATE_INVOICE, ControlCodes.DOCUMENT_AT_RISK,
            ControlCodes.EXACT_DUPLICATE, ControlCodes.POSSIBLE_DUPLICATE);

    /** Domaine d'un code de controle (constat ou point de checklist) ; {@code typeTiers} (CLIENT / FOURNISSEUR) departage les reglements. */
    public static ControlDomain de(String code, String typeTiers) {
        if (code == null) {
            return OTHER;
        }
        ControlDomain direct = PAR_CODE.get(code);
        if (direct != null) {
            return direct;
        }
        if (CODES_DOCUMENT.contains(code) || code.startsWith("DOCUMENT_")) {
            return DOCUMENT;
        }
        if (code.equals(ControlCodes.PAYMENT_WITHOUT_INVOICE) || code.equals(ControlCodes.OVERPAYMENT) || code.equals(ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW)) {
            if (typeTiers == null) {
                return OTHER;
            }
            return typeTiers.toUpperCase(Locale.ROOT).startsWith("CLIENT") ? CUSTOMER : SUPPLIER;
        }
        return OTHER;
    }
}
