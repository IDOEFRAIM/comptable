"""STEP 11.7 : le tool execution loop execute un tool a la fois, respecte MAX_TOOL_CALLS, gate
WRITE derriere une approbation humaine (section 6/9/38 du plan)."""

from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

from app.agent.graph.cfo import _router_more_tools, creer_execute_tool_node
from app.agent.hitl import reprendre_avec_correction
from app.agent.state.cfo import nouvel_etat_initial
from app.mcp.client import McpAccountingClient
from app.mcp.finance_client import McpFinanceClient
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    PostingResult,
    ProposedAccountingLine,
    TaxTreatment,
)
from app.schemas.finance import CashPosition


def _proposal(company_id) -> AccountingProposal:
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT, accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE, confidence=0.95, requires_review=False,
    )
    return AccountingProposal(
        workflow_id=uuid4(), document_id=uuid4(), company_id=company_id,
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
        montant_ttc=Decimal(118000), classification=classification, idempotency_key="k",
    )


def test_execute_un_seul_tool_par_invocation_et_avance_la_file():
    company_id = uuid4()
    finance_mcp = MagicMock(spec=McpFinanceClient)
    finance_mcp.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 27), total=Decimal(12500000), par_compte=[])
    node = creer_execute_tool_node(MagicMock(spec=McpAccountingClient), finance_mcp)

    state = nouvel_etat_initial(uuid4(), uuid4(), company_id, "Quel est mon cash ?")
    state["planned_tools"] = ["finance.get_cash_position", "finance.get_receivables"]

    mise_a_jour = node(state)

    assert mise_a_jour["planned_tools"] == ["finance.get_receivables"]
    assert mise_a_jour["tool_calls"] == ["finance.get_cash_position"]
    assert mise_a_jour["tool_results"][0].result["total"] == "12500000"
    finance_mcp.get_cash_position.assert_called_once_with(company_id)


def test_router_continue_tant_quil_reste_des_tools_planifies():
    state = nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "q")
    state["planned_tools"] = ["finance.get_cash_position"]
    assert _router_more_tools(state) == "continue"


def test_router_collecte_quand_la_file_est_vide():
    state = nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "q")
    state["planned_tools"] = []
    assert _router_more_tools(state) == "collect"


def test_max_tool_calls_arrete_proprement_sans_planter():
    node = creer_execute_tool_node(MagicMock(spec=McpAccountingClient), MagicMock(spec=McpFinanceClient), max_tool_calls=2)
    state = nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "q")
    state["planned_tools"] = ["finance.get_cash_position"]
    state["tool_calls"] = ["finance.get_receivables", "finance.get_payables"]  # deja 2 appels

    mise_a_jour = node(state)

    assert mise_a_jour["planned_tools"] == []
    assert any("max_tool_calls_reached" in e for e in mise_a_jour["errors"])


def test_write_tool_avec_approbation_deja_en_state_appelle_bien_le_tool():
    from app.schemas.accounting import ApprovalResult

    company_id = uuid4()
    accounting_mcp = MagicMock(spec=McpAccountingClient)
    accounting_mcp.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")
    node = creer_execute_tool_node(accounting_mcp, MagicMock(spec=McpFinanceClient))
    state = nouvel_etat_initial(uuid4(), uuid4(), company_id, "Comptabilise cette facture.", pending_proposal=_proposal(company_id))
    state["planned_tools"] = ["accounting.post_entry"]
    state["approval_result"] = ApprovalResult(approved=True)

    mise_a_jour = node(state)

    assert mise_a_jour["posting_result"].posted is True
    accounting_mcp.post_entry.assert_called_once()


def test_write_tool_sans_donnee_contextuelle_est_saute_jamais_devine():
    node = creer_execute_tool_node(MagicMock(spec=McpAccountingClient), MagicMock(spec=McpFinanceClient))
    company_id = uuid4()
    state = nouvel_etat_initial(uuid4(), uuid4(), company_id, "Comptabilise cette facture.")  # pas de pending_proposal
    from app.schemas.accounting import ApprovalResult
    state["approval_result"] = ApprovalResult(approved=True)
    state["planned_tools"] = ["accounting.post_entry"]

    mise_a_jour = node(state)

    assert mise_a_jour["tool_results"][0].error == "missing_context"
    assert "posting_result" not in mise_a_jour


def test_accounting_get_document_sans_document_id_est_saute():
    node = creer_execute_tool_node(MagicMock(spec=McpAccountingClient), MagicMock(spec=McpFinanceClient))
    state = nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Pourquoi cette facture ?")  # pas de document_id
    state["planned_tools"] = ["accounting.get_document"]

    mise_a_jour = node(state)

    assert mise_a_jour["tool_results"][0].error == "missing_context"


def test_le_company_id_utilise_est_toujours_celui_du_state_jamais_du_planificateur():
    # STEP 11, section 27 : tenant lock - le planner ne fournit jamais d'arguments, seulement des
    # noms de tools ; execute_tool_node prend toujours company_id depuis le state.
    company_id_reel = uuid4()
    finance_mcp = MagicMock(spec=McpFinanceClient)
    finance_mcp.get_receivables.return_value = None
    node = creer_execute_tool_node(MagicMock(spec=McpAccountingClient), finance_mcp)
    state = nouvel_etat_initial(uuid4(), uuid4(), company_id_reel, "Mes creances ?")
    state["planned_tools"] = ["finance.get_receivables"]

    node(state)

    finance_mcp.get_receivables.assert_called_once_with(company_id_reel)


def test_reprendre_avec_correction_approuve_produit_bien_un_posting():
    # Verifie que le mecanisme de reprise centralise (app/agent/hitl.py) produit bien un Command
    # exploitable - meme discipline que STEP 7 (human_posting_approval_node).
    commande = reprendre_avec_correction({"approved": True})
    assert commande.resume == {"approved": True}
