"""STEP 7.2-7.4 : le serveur MCP AI Accounting expose des outils qui delegent integralement a
AccountingClient - aucune logique metier ici, aucune regle comptable, aucun try/except (les
erreurs remontent telles quelles, reclassees uniquement par app/mcp/client.py)."""

import asyncio
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from app.mcp.server import (
    TOOL_GET_DOCUMENT,
    TOOL_POST_ENTRY,
    TOOL_VALIDATE_PROPOSAL,
    creer_mcp_server,
)
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
from app.services.accounting_client import AccountingClient, AccountingValidationError


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


def test_le_serveur_expose_exactement_les_trois_outils_reels():
    server = creer_mcp_server(MagicMock(spec=AccountingClient))
    noms = asyncio.run(server.list_tools())
    noms_outils = {t.name for t in noms}
    assert noms_outils == {TOOL_GET_DOCUMENT, TOOL_VALIDATE_PROPOSAL, TOOL_POST_ENTRY}
    # Jamais fabrique pour remplir la liste suggeree (section 7.3 du plan) : aucune capability
    # Java reelle n'existe pour ces lectures.
    assert "accounting.get_accounts" not in noms_outils
    assert "accounting.get_ledger" not in noms_outils
    assert "accounting.get_trial_balance" not in noms_outils
    assert "accounting.get_profit_loss" not in noms_outils


def test_get_document_delegue_integralement_a_accounting_client():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.return_value = DocumentExtractionSnapshot(
        document_id=document_id, company_id=company_id, type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000),
        fournisseur="Station Total", confiance=0.95,
    )
    server = creer_mcp_server(client)

    resultat = asyncio.run(server.call_tool(TOOL_GET_DOCUMENT, {"document_id": str(document_id)}))

    assert resultat.structured_content["montantTtc"] == "118000"
    client.get_document_extraction.assert_called_once_with(document_id)


def test_validate_proposal_delegue_integralement_a_accounting_client():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.validate_proposal.return_value = ValidationResult(valid=True, requires_review=False)
    server = creer_mcp_server(client)
    proposal = _proposal(document_id, company_id)

    resultat = asyncio.run(
        server.call_tool(TOOL_VALIDATE_PROPOSAL, {"proposal": proposal.model_dump(mode="json", by_alias=True)})
    )

    assert resultat.structured_content["valid"] is True
    client.validate_proposal.assert_called_once()
    proposal_recue = client.validate_proposal.call_args[0][0]
    assert proposal_recue.document_id == document_id


def test_post_entry_delegue_integralement_a_accounting_client():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")
    server = creer_mcp_server(client)
    proposal = _proposal(document_id, company_id)

    resultat = asyncio.run(
        server.call_tool(TOOL_POST_ENTRY, {"proposal": proposal.model_dump(mode="json", by_alias=True)})
    )

    assert resultat.structured_content["posted"] is True
    client.post_entry.assert_called_once()


def test_une_erreur_accounting_client_traverse_le_tool_sans_etre_avalee():
    document_id, company_id = uuid4(), uuid4()
    client = MagicMock(spec=AccountingClient)
    client.validate_proposal.side_effect = AccountingValidationError("rejete", 422, "detail")
    server = creer_mcp_server(client)
    proposal = _proposal(document_id, company_id)

    from mcp.server.mcpserver.exceptions import UnexpectedToolError

    with pytest.raises(UnexpectedToolError) as exc_info:
        asyncio.run(
            server.call_tool(TOOL_VALIDATE_PROPOSAL, {"proposal": proposal.model_dump(mode="json", by_alias=True)})
        )

    assert isinstance(exc_info.value.__cause__, AccountingValidationError)
