import pytest

from app.accounting.services.treatment import suggerer_traitement
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountPurpose,
    SuggestedAccount,
    TaxTreatment,
)


def test_traitement_reprend_le_premier_purpose_suggere_comme_charge():
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        suggested_accounts=[SuggestedAccount(purpose=AccountPurpose.ACHAT_CONSOMMABLE, confidence=0.95)],
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.95,
        requires_review=False,
    )

    treatment = suggerer_traitement(classification)

    assert treatment.expense_purpose is AccountPurpose.ACHAT_CONSOMMABLE
    assert treatment.tax_purpose is AccountPurpose.TVA_RECUPERABLE
    assert treatment.counterparty_purpose is AccountPurpose.FOURNISSEUR
    assert treatment.confidence == 0.95


def test_tax_purpose_est_none_quand_tva_non_applicable():
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        suggested_accounts=[SuggestedAccount(purpose=AccountPurpose.ACHAT_CONSOMMABLE, confidence=0.9)],
        tax_treatment=TaxTreatment.NON_APPLICABLE,
        confidence=0.9,
        requires_review=False,
    )

    treatment = suggerer_traitement(classification)

    assert treatment.tax_purpose is None


def test_classification_sans_suggested_accounts_est_refusee():
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.OTHER,
        suggested_accounts=[],
        tax_treatment=TaxTreatment.NON_APPLICABLE,
        confidence=0.5,
        requires_review=True,
    )

    with pytest.raises(ValueError, match="suggested_accounts"):
        suggerer_traitement(classification)
