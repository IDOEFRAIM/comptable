package com.example.comptable.agent.matching.domain;

/** Miroir de ai-service/app/schemas/matching.py::SourceType. Sert a la fois de type de source et
 * de type de candidat (une transaction peut etre comparee a une AUTRE transaction pour detecter
 * un virement interne). */
public enum SourceType {
    BANK_TRANSACTION,
    MOBILE_MONEY_TRANSACTION,
    ACCOUNTING_ENTRY,
    CUSTOMER_INVOICE,
    SUPPLIER_INVOICE,
    DOCUMENT
}
