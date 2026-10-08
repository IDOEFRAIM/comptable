package com.example.comptable.investigation;

/**
 * Codes de cause STABLES de l'investigation (jamais traduits, jamais renommes : statistiques et interface s'appuient dessus). Un code decrit
 * ce que l'on a CONSTATE ; l'explication lisible est portee a cote. Les codes qui existent deja comme code de controle gardent le meme nom.
 */
public final class CauseCodes {

    private CauseCodes() {
    }

    // Tiers : reglements et factures
    public static final String UNALLOCATED_PAYMENT = "UNALLOCATED_PAYMENT";
    public static final String PARTIAL_PAYMENT = "PARTIAL_PAYMENT";
    public static final String UNPAID_INVOICE = "UNPAID_INVOICE";
    public static final String OVERPAYMENT = "OVERPAYMENT";
    public static final String PAYMENT_WITHOUT_INVOICE = "PAYMENT_WITHOUT_INVOICE";
    public static final String CREDIT_NOTE_NOT_APPLIED = "CREDIT_NOTE_NOT_APPLIED";
    public static final String CREDIT_BALANCE_EXPLAINED = "CREDIT_BALANCE_EXPLAINED";
    public static final String CREDIT_BALANCE_UNEXPLAINED = "CREDIT_BALANCE_UNEXPLAINED";
    public static final String DEBIT_BALANCE_EXPLAINED = "DEBIT_BALANCE_EXPLAINED";
    public static final String DEBIT_BALANCE_UNEXPLAINED = "DEBIT_BALANCE_UNEXPLAINED";
    public static final String NORMAL_BALANCE = "NORMAL_BALANCE";

    // Banque
    public static final String BANK_TRANSACTION_UNMATCHED = "BANK_TRANSACTION_UNMATCHED";
    public static final String BANK_MATCH_AMBIGUOUS = "BANK_MATCH_AMBIGUOUS";
    public static final String BANK_MATCH_TO_CONFIRM = "BANK_MATCH_TO_CONFIRM";
    public static final String BANK_RECONCILIATION_REVERSED = "BANK_RECONCILIATION_REVERSED";
    public static final String BOOKED_NOT_ON_STATEMENT = "BOOKED_NOT_ON_STATEMENT";
    public static final String OPENING_BALANCE_MISMATCH = "OPENING_BALANCE_MISMATCH";
    public static final String BANK_DIFFERENCE_UNEXPLAINED = "BANK_DIFFERENCE_UNEXPLAINED";
    public static final String INTERNAL_TRANSFER_PENDING = "INTERNAL_TRANSFER_PENDING";
    public static final String INTERNAL_TRANSFER_WITHOUT_ENTRY = "INTERNAL_TRANSFER_WITHOUT_ENTRY";
    public static final String BANK_DUPLICATE_TRANSACTION = "BANK_DUPLICATE_TRANSACTION";
    public static final String BANK_RECONCILED = "BANK_RECONCILED";

    // Documents et ecritures
    public static final String MISSING_DOCUMENT = "MISSING_DOCUMENT";
    public static final String INVOICE_NOT_POSTED = "INVOICE_NOT_POSTED";
    public static final String DUPLICATE_INVOICE = "DUPLICATE_INVOICE";
    public static final String ACCOUNT_MAPPING_MISSING = "ACCOUNT_MAPPING_MISSING";
    public static final String DOCUMENT_REQUEST_PENDING = "DOCUMENT_REQUEST_PENDING";

    // Immobilisations
    public static final String FIXED_ASSET_WITHOUT_SOURCE = "FIXED_ASSET_WITHOUT_SOURCE";
    public static final String POSSIBLE_DOUBLE_ACQUISITION = "POSSIBLE_DOUBLE_ACQUISITION";
}
