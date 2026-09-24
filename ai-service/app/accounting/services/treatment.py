"""suggest_accounting_treatment (STEP 6.6) : traduit AccountingClassification en un plan de
traitement structure - purement deterministe, ne resout encore aucun compte reel (la resolution
en compte concret reste exclusive a Java, AccountingAccountResolver via validate_with_java,
section 11-12 du plan)."""

from app.schemas.accounting import (
    AccountingClassification,
    AccountingTreatment,
    AccountPurpose,
    TaxTreatment,
)


def suggerer_traitement(classification: AccountingClassification) -> AccountingTreatment:
    if not classification.suggested_accounts:
        raise ValueError("AccountingClassification sans suggested_accounts : impossible de suggerer un traitement")

    expense_purpose = classification.suggested_accounts[0].purpose
    tax_purpose = AccountPurpose.TVA_RECUPERABLE if classification.tax_treatment == TaxTreatment.TVA_RECUPERABLE else None

    return AccountingTreatment(
        expense_purpose=expense_purpose,
        tax_purpose=tax_purpose,
        counterparty_purpose=AccountPurpose.FOURNISSEUR,
        confidence=classification.confidence,
    )
