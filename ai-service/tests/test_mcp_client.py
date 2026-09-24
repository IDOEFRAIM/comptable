"""STEP 7.9 : McpAccountingClient est le seul point par lequel un node LangGraph doit appeler une
capability comptable via MCP. Verifie le reclassement d'erreurs (le coeur de ce module) : une
AccountingClientError d'origine doit traverser inchangee, tout le reste devient McpToolCallError."""

from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from app.mcp.client import McpAccountingClient, McpToolCallError
from app.mcp.server import creer_mcp_server
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    DocumentExtractionSnapshot,
    PostingResult,
    ProposedAccountingLine,
    TaxTreatment,
    TypeDocument,
    ValidationResult,
)
from app.services.accounting_client import (
    AccountingClient,
    AccountingServiceUnavailableError,
    AccountingValidationError,
)


def _proposal(document_id, company_id) -> AccountingProposal:
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.9,
        requires_review=False,
    )
    return AccountingProposal(
        workflow_id=uuid4(),
        document_id=document_id,
        company_id=company_id,
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
        montant_tva=Decimal(18000),
        montant_ttc=Decimal(118000),
        classification=classification,
        idempotency_key="k",
    )


def _mcp_client(accounting_client: MagicMock) -> McpAccountingClient:
    return McpAccountingClient(creer_mcp_server(accounting_client))


def test_get_document_retourne_le_snapshot_type():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.return_value = DocumentExtractionSnapshot(
        document_id=document_id, company_id=company_id, type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000),
        fournisseur="Station Total", confiance=0.95,
    )

    resultat = _mcp_client(client).get_document_extraction(document_id)

    assert isinstance(resultat, DocumentExtractionSnapshot)
    assert resultat.document_id == document_id
    assert resultat.montant_ttc == Decimal(118000)


def test_validate_proposal_retourne_le_validation_result_type():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.validate_proposal.return_value = ValidationResult(valid=True, requires_review=False)

    resultat = _mcp_client(client).validate_proposal(_proposal(document_id, company_id))

    assert isinstance(resultat, ValidationResult)
    assert resultat.valid is True


def test_post_entry_retourne_le_posting_result_type():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")

    resultat = _mcp_client(client).post_entry(_proposal(document_id, company_id))

    assert isinstance(resultat, PostingResult)
    assert resultat.posted is True
    assert resultat.entry_number == "ACH-2026-0001"


def test_une_accounting_client_error_traverse_mcp_inchangee():
    # Le coeur du reclassement : un node existant qui catch AccountingValidationError doit
    # continuer a fonctionner sans modification, que l'appel passe par MCP ou non.
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.validate_proposal.side_effect = AccountingValidationError("Compte non configure", 422, "detail")

    with pytest.raises(AccountingValidationError) as exc_info:
        _mcp_client(client).validate_proposal(_proposal(document_id, company_id))

    assert exc_info.value.status_code == 422
    assert exc_info.value.retryable is False
    assert exc_info.value.human_reviewable is True


def test_une_accounting_client_error_retryable_traverse_mcp_avec_son_flag_intact():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.post_entry.side_effect = AccountingServiceUnavailableError("indisponible", 503)

    with pytest.raises(AccountingServiceUnavailableError) as exc_info:
        _mcp_client(client).post_entry(_proposal(document_id, company_id))

    assert exc_info.value.retryable is True


def test_une_erreur_non_accounting_client_devient_mcp_tool_call_error():
    document_id = uuid4()
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.side_effect = RuntimeError("bug inattendu")

    with pytest.raises(McpToolCallError) as exc_info:
        _mcp_client(client).get_document_extraction(document_id)

    assert exc_info.value.retryable is False
    assert exc_info.value.human_reviewable is False


def test_une_entree_mcp_invalide_devient_mcp_tool_call_error_human_reviewable():
    server = creer_mcp_server(MagicMock(spec=AccountingClient))

    from app.mcp.client import _appeler_outil_sync
    from app.mcp.server import TOOL_GET_DOCUMENT

    with pytest.raises(McpToolCallError) as exc_info:
        _appeler_outil_sync(server, TOOL_GET_DOCUMENT, {"document_id": "not-a-uuid"})

    assert exc_info.value.human_reviewable is True
    assert exc_info.value.retryable is False
