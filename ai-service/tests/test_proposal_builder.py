from decimal import Decimal
from uuid import uuid4

from app.accounting.services.proposal_builder import construire_proposal
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingTreatment,
    AccountPurpose,
    NormalizedAccountingDocument,
    SuggestedAccount,
    TaxTreatment,
)


def _classification() -> AccountingClassification:
    return AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        suggested_accounts=[SuggestedAccount(purpose=AccountPurpose.ACHAT_CONSOMMABLE, confidence=0.95)],
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.95,
        requires_review=False,
    )


def _treatment() -> AccountingTreatment:
    return AccountingTreatment(
        expense_purpose=AccountPurpose.ACHAT_CONSOMMABLE,
        tax_purpose=AccountPurpose.TVA_RECUPERABLE,
        counterparty_purpose=AccountPurpose.FOURNISSEUR,
        confidence=0.95,
    )


def test_cas8_document_complet_produit_une_proposition_complete():
    workflow_id = uuid4()
    document = NormalizedAccountingDocument(
        document_id=uuid4(), company_id=uuid4(), fournisseur="Station Total", libelle="carburant",
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000), extraction_confidence=0.92,
    )

    proposal = construire_proposal(workflow_id=workflow_id, document=document, classification=_classification(), treatment=_treatment())

    assert proposal.workflow_id == workflow_id
    assert proposal.document_id == document.document_id
    assert proposal.company_id == document.company_id
    assert len(proposal.lines) == 1
    assert proposal.lines[0].purpose is AccountPurpose.ACHAT_CONSOMMABLE
    assert proposal.lines[0].montant_ht == Decimal(100000)
    assert proposal.lines[0].designation == "carburant"
    assert proposal.montant_tva == Decimal(18000)
    assert proposal.montant_ttc == Decimal(118000)
    assert proposal.idempotency_key == f"{document.document_id}:ACHAT"


def test_montant_ht_absent_est_derive_de_ttc_moins_tva_jamais_devine_par_un_llm():
    document = NormalizedAccountingDocument(
        document_id=uuid4(), company_id=uuid4(), fournisseur="Station Total", libelle="carburant",
        montant_ht=None, montant_tva=Decimal(18000), montant_ttc=Decimal(118000), extraction_confidence=0.92,
    )

    proposal = construire_proposal(workflow_id=uuid4(), document=document, classification=_classification(), treatment=_treatment())

    assert proposal.lines[0].montant_ht == Decimal(100000)


def test_designation_retombe_sur_le_fournisseur_si_aucun_libelle():
    document = NormalizedAccountingDocument(
        document_id=uuid4(), company_id=uuid4(), fournisseur="Station Total", libelle=None,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000), extraction_confidence=0.92,
    )

    proposal = construire_proposal(workflow_id=uuid4(), document=document, classification=_classification(), treatment=_treatment())

    assert proposal.lines[0].designation == "Station Total"
