"""Verifie que le serializer/checkpointer durci (STEP 7.1) couvre reellement tous les types
transportes par AccountingState - reproduit le round-trip de checkpoint (dumps_typed/loads_typed)
qui declenchait l'avertissement LANGGRAPH_STRICT_MSGPACK observe depuis STEP 6 avec un
InMemorySaver() nu, et verifie qu'il ne se produit plus."""

import logging
from decimal import Decimal
from uuid import uuid4

from langgraph.checkpoint.memory import InMemorySaver

from app.agent.checkpoint import creer_checkpointer_accounting, creer_serializer_accounting
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountingTreatment,
    AccountPurpose,
    AccountResolutionSource,
    AccountResolutionStatus,
    ApprovalResult,
    DocumentExtractionSnapshot,
    NormalizedAccountingDocument,
    PostingResult,
    ProposedAccountingLine,
    ResolvedAccountInfo,
    TaxTreatment,
    TypeDocument,
    ValidationResult,
    WorkflowStatus,
)


def _etat_pleinement_peuple() -> dict:
    document_id, company_id = uuid4(), uuid4()
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.95,
        requires_review=False,
    )
    return {
        "workflow_id": uuid4(),
        "company_id": company_id,
        "document_id": document_id,
        "status": WorkflowStatus.COMPLETED,
        "errors": [],
        "extraction": DocumentExtractionSnapshot(
            document_id=document_id, company_id=company_id, type_document=TypeDocument.FACTURE_ACHAT,
            montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000),
            fournisseur="Station Total", confiance=0.95,
        ),
        "normalized_document": NormalizedAccountingDocument(
            document_id=document_id, company_id=company_id, montant_ttc=Decimal(118000), extraction_confidence=0.95,
        ),
        "classification": classification,
        "accounting_treatment": AccountingTreatment(
            expense_purpose=AccountPurpose.ACHAT_SERVICE, tax_purpose=AccountPurpose.TVA_RECUPERABLE,
            counterparty_purpose=AccountPurpose.FOURNISSEUR, confidence=0.95,
        ),
        "resolved_accounts": [
            ResolvedAccountInfo(
                status=AccountResolutionStatus.RESOLVED, compte_numero="621",
                source=AccountResolutionSource.MAPPING_GENERIQUE, explication="Resolu.",
            )
        ],
        "proposal": AccountingProposal(
            workflow_id=uuid4(), document_id=document_id, company_id=company_id,
            lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
            montant_ttc=Decimal(118000), classification=classification, idempotency_key="k",
        ),
        "validation_result": ValidationResult(valid=True, requires_review=False),
        "approval_required": False,
        "approval_result": ApprovalResult(approved=True),
        "posting_result": PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH"),
    }


def test_le_serializer_durci_couvre_tous_les_types_du_state_sans_avertissement(caplog):
    serializer = creer_serializer_accounting()
    etat = _etat_pleinement_peuple()

    with caplog.at_level(logging.WARNING, logger="langgraph.checkpoint.serde.jsonplus"):
        encode = serializer.dumps_typed(etat)
        decode = serializer.loads_typed(encode)

    assert decode["status"] is WorkflowStatus.COMPLETED
    assert decode["proposal"].montant_ttc == Decimal(118000)
    messages_msgpack = [m for m in caplog.messages if "Deserializing unregistered type" in m]
    assert messages_msgpack == []


def test_un_inmemorysaver_nu_emettrait_lavertissement_que_le_durcissement_elimine(caplog):
    # Preuve du probleme que STEP 7.1 corrige : sans allowlist explicite, le serializer par defaut
    # avertit pour chaque type non enregistre.
    serializer_nu = InMemorySaver().serde
    etat = _etat_pleinement_peuple()

    with caplog.at_level(logging.WARNING, logger="langgraph.checkpoint.serde.jsonplus"):
        serializer_nu.loads_typed(serializer_nu.dumps_typed(etat))

    messages_msgpack = [m for m in caplog.messages if "Deserializing unregistered type" in m]
    assert messages_msgpack != []


def test_creer_checkpointer_accounting_utilise_le_serializer_durci():
    checkpointer = creer_checkpointer_accounting()
    assert isinstance(checkpointer, InMemorySaver)
    assert checkpointer.serde is not None
    assert checkpointer.serde._allowed_msgpack_modules not in (True, None)
