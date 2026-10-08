package com.example.comptable.controle;

import com.example.comptable.agent.finding.domain.TypeFinding;

import java.util.Map;

/**
 * Catalogue des codes de controle (Phase B, lot B1). Un code = une decision humaine possible ; le
 * {@link TypeFinding} n'est que le regroupement grossier historique du {@code Finding}.
 */
public final class ControlCodes {

    // Imputation comptable
    public static final String ACCOUNT_NOT_CONFIGURED = "ACCOUNT_NOT_CONFIGURED";
    public static final String ACCOUNT_AMBIGUOUS = "ACCOUNT_AMBIGUOUS";
    public static final String ACCOUNT_INVALID = "ACCOUNT_INVALID";
    // Etats financiers (phase F) : controles reellement implementes par les services d'etats
    /** Compte pertinent pour un etat financier mais qu'aucune regle de correspondance de la definition versionnee ne classe : jamais range dans la premiere rubrique plausible. */
    public static final String UNMAPPED_ACCOUNT = "UNMAPPED_ACCOUNT";
    /** Compte accepte par les regles de plusieurs lignes d'un meme etat : exclu des montants, a trancher par une decision reglementaire. */
    public static final String AMBIGUOUS_MAPPING = "AMBIGUOUS_MAPPING";
    /** Le resultat net de l'etat differe du resultat du grand livre (classes 6, 7, 8) : comptes non pris en compte. */
    public static final String RESULT_MISMATCH = "RESULT_MISMATCH";
    /** Les formules du tableau de flux de tresorerie ne retombent pas (ZG, ZA + ZG = ZH). */
    public static final String CASH_FLOW_MISMATCH = "CASH_FLOW_MISMATCH";
    /** Total de l'Actif (BZ) different du total du Passif (DZ) alors que tous les comptes sont mappes : tolerance nulle (BigDecimal). */
    public static final String BALANCE_SHEET_IMBALANCE = "BALANCE_SHEET_IMBALANCE";
    /** Une note applicable (ou dont l'applicabilite n'est pas exclue) ne peut pas etre completement produite : donnees, comptes ou sections manuelles manquants. */
    public static final String REQUIRED_NOTE_INCOMPLETE = "REQUIRED_NOTE_INCOMPLETE";
    /** Un terme de la CAFG dont le rattachement aux comptes est PARTIAL dans la source (ou un compte de gestion non classe) a des mouvements : la CAFG n'est pas calculable. */
    public static final String UNRESOLVED_CAFG_COMPONENT = "UNRESOLVED_CAFG_COMPONENT";
    /** Une ecriture de tresorerie touche un compte de financement ou d'investissement sans evenement de tresorerie qualifie (ou qualifie de facon incoherente). */
    public static final String UNQUALIFIED_CASH_EVENT = "UNQUALIFIED_CASH_EVENT";
    // Pieces / documents
    public static final String MISSING_DOCUMENT = "MISSING_DOCUMENT";
    public static final String DUPLICATE_INVOICE = "DUPLICATE_INVOICE";
    public static final String DOCUMENT_AT_RISK = "DOCUMENT_AT_RISK";
    /** Meme fichier (meme SHA-256) recu plusieurs fois. */
    public static final String EXACT_DUPLICATE = "EXACT_DUPLICATE";
    /** Meme fournisseur et meme numero de facture normalise sur plusieurs pieces (detecte par DetecteurDocuments). */
    /** Meme tiers, meme montant, dates proches, numero absent : suspicion, jamais certitude. */
    public static final String POSSIBLE_DUPLICATE = "POSSIBLE_DUPLICATE";
    public static final String DOCUMENT_UNREADABLE = "DOCUMENT_UNREADABLE";
    /** Plusieurs tiers ou plusieurs factures egalement plausibles pour une piece. */
    public static final String DOCUMENT_AMBIGUOUS = "DOCUMENT_AMBIGUOUS";
    /** Piece de facture ni rapprochee ni comptabilisee depuis trop longtemps. */
    public static final String DOCUMENT_UNMATCHED = "DOCUMENT_UNMATCHED";
    /** Meme fournisseur et meme numero qu'une facture, mais un autre montant. */
    public static final String DOCUMENT_AMOUNT_MISMATCH = "DOCUMENT_AMOUNT_MISMATCH";
    // Collecte des pieces
    /** Des demandes de piece ne peuvent pas partir : aucun interlocuteur joignable, ou numero invalide. */
    public static final String DOCUMENT_REQUEST_NO_CONTACT = "DOCUMENT_REQUEST_NO_CONTACT";
    /** Des demandes n'ont pas pu etre envoyees malgre les nouvelles tentatives techniques. */
    public static final String DOCUMENT_REQUEST_SEND_FAILED = "DOCUMENT_REQUEST_SEND_FAILED";
    /** Le client n'a pas repondu apres toutes les relances prevues par la politique : intervention humaine. */
    public static final String DOCUMENT_REQUEST_NO_RESPONSE = "DOCUMENT_REQUEST_NO_RESPONSE";
    /** Une piece recue ressemble a une demande sans certitude : a confirmer. */
    public static final String DOCUMENT_MATCH_TO_CONFIRM = "DOCUMENT_MATCH_TO_CONFIRM";
    /** Une piece recue pourrait repondre a plusieurs demandes : jamais tranche par le systeme. */
    public static final String DOCUMENT_RECEIVED_AMBIGUOUS = "DOCUMENT_RECEIVED_AMBIGUOUS";
    // Fiscalite
    public static final String VAT_INCONSISTENCY = "VAT_INCONSISTENCY";
    public static final String TAX_DECLARATION_MISSING = "TAX_DECLARATION_MISSING";
    public static final String TAX_DECLARATION_LATE = "TAX_DECLARATION_LATE";
    // Fiscalite : TVA calculee depuis la comptabilite (Phase G)
    public static final String TAX_TREATMENT_MISSING = "TAX_TREATMENT_MISSING";
    public static final String TAX_RATE_UNRESOLVED = "TAX_RATE_UNRESOLVED";
    public static final String VAT_BASE_MISMATCH = "VAT_BASE_MISMATCH";
    public static final String VAT_AMOUNT_MISMATCH = "VAT_AMOUNT_MISMATCH";
    public static final String DUPLICATE_TAX_DOCUMENT = "DUPLICATE_TAX_DOCUMENT";
    public static final String TAX_PERIOD_INCOMPLETE = "TAX_PERIOD_INCOMPLETE";
    /** Constat de niveau période (jamais par opération) : aucun cadre réglementaire VERIFIED ne couvre la période ; résolu automatiquement quand un cadre vérifié est installé. */
    public static final String TAX_FRAMEWORK_NOT_VERIFIED = "TAX_FRAMEWORK_NOT_VERIFIED";
    // Rapprochement
    public static final String UNMATCHED_PAYMENT = "UNMATCHED_PAYMENT";
    public static final String AMBIGUOUS_BANK_MATCH = "AMBIGUOUS_BANK_MATCH";
    public static final String MATCH_TO_CONFIRM = "MATCH_TO_CONFIRM";
    /** Transaction bancaire importee qui ressemble a une autre (meme compte, jour, montant, sens) sans etre la meme : a regarder. */
    public static final String BANK_DUPLICATE_TRANSACTION = "BANK_DUPLICATE_TRANSACTION";
    /** Reglement non affecte a aucune facture alors que le tiers n a plus de facture ouverte. */
    public static final String PAYMENT_WITHOUT_INVOICE = "PAYMENT_WITHOUT_INVOICE";
    /** Reglement qui depasse ce que les factures ouvertes du tiers peuvent absorber : reliquat non affecte. */
    public static final String OVERPAYMENT = "OVERPAYMENT";
    /** Reglement non affecte alors que le tiers a des factures ouvertes sans correspondance certaine : le comptable repartit (jamais la premiere facture). */
    public static final String PAYMENT_ALLOCATION_NEEDS_REVIEW = "PAYMENT_ALLOCATION_NEEDS_REVIEW";
    /** Deux comptes bancaires de l'entreprise : une sortie et une entree de meme montant qui ressemblent a un virement interne, a confirmer. */
    public static final String INTERNAL_TRANSFER_TO_CONFIRM = "INTERNAL_TRANSFER_TO_CONFIRM";
    /** Plusieurs jambes possibles pour un meme virement interne : jamais tranche par le systeme. */
    public static final String INTERNAL_TRANSFER_AMBIGUOUS = "INTERNAL_TRANSFER_AMBIGUOUS";
    /** Virement interne lie mais aucune ecriture de transfert : a enregistrer par le comptable (rien n'est genere automatiquement). */
    public static final String INTERNAL_TRANSFER_WITHOUT_ENTRY = "INTERNAL_TRANSFER_WITHOUT_ENTRY";
    // Tiers
    public static final String CLIENT_CREDIT_BALANCE = "CLIENT_CREDIT_BALANCE";
    public static final String SUPPLIER_DEBIT_BALANCE = "SUPPLIER_DEBIT_BALANCE";
    // Stock, immobilisations
    public static final String STOCK_INCONSISTENCY = "STOCK_INCONSISTENCY";
    public static final String FIXED_ASSET_WITHOUT_SOURCE = "FIXED_ASSET_WITHOUT_SOURCE";
    public static final String POSSIBLE_DOUBLE_ACQUISITION = "POSSIBLE_DOUBLE_ACQUISITION";
    // Ecritures / cloture
    public static final String DRAFT_ENTRY_OLD = "DRAFT_ENTRY_OLD";
    public static final String CLOSING_BLOCKER = "CLOSING_BLOCKER";

    private static final Map<String, TypeFinding> TYPES = Map.ofEntries(
            Map.entry(ACCOUNT_NOT_CONFIGURED, TypeFinding.ACCOUNT_REVIEW),
            Map.entry(ACCOUNT_AMBIGUOUS, TypeFinding.ACCOUNT_REVIEW),
            Map.entry(ACCOUNT_INVALID, TypeFinding.ACCOUNT_REVIEW),
            Map.entry(MISSING_DOCUMENT, TypeFinding.MISSING_EVIDENCE),
            Map.entry(DUPLICATE_INVOICE, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(EXACT_DUPLICATE, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(POSSIBLE_DUPLICATE, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(DOCUMENT_UNREADABLE, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(DOCUMENT_AMBIGUOUS, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(DOCUMENT_UNMATCHED, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(DOCUMENT_AMOUNT_MISMATCH, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(DOCUMENT_REQUEST_NO_CONTACT, TypeFinding.MISSING_EVIDENCE),
            Map.entry(DOCUMENT_REQUEST_SEND_FAILED, TypeFinding.MISSING_EVIDENCE),
            Map.entry(DOCUMENT_REQUEST_NO_RESPONSE, TypeFinding.MISSING_EVIDENCE),
            Map.entry(DOCUMENT_MATCH_TO_CONFIRM, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(DOCUMENT_RECEIVED_AMBIGUOUS, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(DOCUMENT_AT_RISK, TypeFinding.DOCUMENT_REVIEW),
            Map.entry(VAT_INCONSISTENCY, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(TAX_DECLARATION_MISSING, TypeFinding.TAX_REVIEW),
            Map.entry(TAX_DECLARATION_LATE, TypeFinding.TAX_REVIEW),
            Map.entry(TAX_TREATMENT_MISSING, TypeFinding.TAX_REVIEW),
            Map.entry(TAX_RATE_UNRESOLVED, TypeFinding.TAX_REVIEW),
            Map.entry(VAT_BASE_MISMATCH, TypeFinding.TAX_REVIEW),
            Map.entry(VAT_AMOUNT_MISMATCH, TypeFinding.TAX_REVIEW),
            Map.entry(DUPLICATE_TAX_DOCUMENT, TypeFinding.TAX_REVIEW),
            Map.entry(TAX_PERIOD_INCOMPLETE, TypeFinding.TAX_REVIEW),
            Map.entry(TAX_FRAMEWORK_NOT_VERIFIED, TypeFinding.TAX_REVIEW),
            Map.entry(UNMATCHED_PAYMENT, TypeFinding.MISSING_EVIDENCE),
            Map.entry(AMBIGUOUS_BANK_MATCH, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(MATCH_TO_CONFIRM, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(BANK_DUPLICATE_TRANSACTION, TypeFinding.BANK_DIFFERENCE),
            Map.entry(PAYMENT_WITHOUT_INVOICE, TypeFinding.MISSING_EVIDENCE),
            Map.entry(OVERPAYMENT, TypeFinding.ANOMALY),
            Map.entry(PAYMENT_ALLOCATION_NEEDS_REVIEW, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(INTERNAL_TRANSFER_TO_CONFIRM, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(INTERNAL_TRANSFER_AMBIGUOUS, TypeFinding.MATCH_AMBIGUOUS),
            Map.entry(INTERNAL_TRANSFER_WITHOUT_ENTRY, TypeFinding.BANK_DIFFERENCE),
            Map.entry(CLIENT_CREDIT_BALANCE, TypeFinding.ANOMALY),
            Map.entry(SUPPLIER_DEBIT_BALANCE, TypeFinding.ANOMALY),
            Map.entry(STOCK_INCONSISTENCY, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(FIXED_ASSET_WITHOUT_SOURCE, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(POSSIBLE_DOUBLE_ACQUISITION, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(DRAFT_ENTRY_OLD, TypeFinding.ECRITURE_A_CONTROLER),
            Map.entry(CLOSING_BLOCKER, TypeFinding.CLOSING_BLOCKER),
            Map.entry(UNMAPPED_ACCOUNT, TypeFinding.ACCOUNT_REVIEW),
            Map.entry(AMBIGUOUS_MAPPING, TypeFinding.ACCOUNT_REVIEW),
            Map.entry(RESULT_MISMATCH, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(CASH_FLOW_MISMATCH, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(BALANCE_SHEET_IMBALANCE, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(UNRESOLVED_CAFG_COMPONENT, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(UNQUALIFIED_CASH_EVENT, TypeFinding.DATA_INCONSISTENCY),
            Map.entry(REQUIRED_NOTE_INCOMPLETE, TypeFinding.DATA_INCONSISTENCY));

    private ControlCodes() {
    }

    /** Regroupement historique d'un code ; un code inconnu (ex. alerte de fraude) reste une anomalie. */
    public static TypeFinding typeFinding(String code) {
        return TYPES.getOrDefault(code, TypeFinding.ANOMALY);
    }
}
