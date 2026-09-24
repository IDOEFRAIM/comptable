"""Graph LangGraph Python "Financial Health Check" (STEP 8.8 du plan d'implementation) - le
premier agent construit au-dessus de la Financial Intelligence Layer.

Graph (sequence exacte du plan, section 22) :
    START -> initialize -> get_financial_snapshot -> get_cash_position -> get_receivables
        -> get_payables -> analyze_financial_health -> finalize -> END

`get_working_capital` (outil MCP existant, STEP 8.6) n'est volontairement pas inclus dans cette
sequence : le plan (section 22) fixe explicitement cette liste de 4 lectures avant l'analyse,
jamais 5 - working_capital reste disponible comme capability independante pour un futur usage.

Tous les appels de donnees passent par McpFinanceClient (jamais FinancialClient directement depuis
un node) - meme discipline que le graph AI Accounting (STEP 7.9) : MCP est la frontiere agentique,
la logique metier (calcul des faits) reste entierement cote Java."""

import logging
from collections.abc import Callable

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.state.financial_health import FinancialHealthState
from app.finance.health_assessment_service import FinancialHealthAssessmentService
from app.finance.health_policy import determiner_statut
from app.mcp.finance_client import McpFinanceClient
from app.mcp.finance_server import creer_finance_mcp_server
from app.schemas.accounting import WorkflowStatus
from app.services.financial_client import FinancialClient

logger = logging.getLogger(__name__)

NODE_INITIALIZE = "initialize"
NODE_GET_FINANCIAL_SNAPSHOT = "get_financial_snapshot"
NODE_GET_CASH_POSITION = "get_cash_position"
NODE_GET_RECEIVABLES = "get_receivables"
NODE_GET_PAYABLES = "get_payables"
NODE_ANALYZE_FINANCIAL_HEALTH = "analyze_financial_health"
NODE_FINALIZE = "finalize"


class MissingFinancialHealthInputError(ValueError):
    """Leve par analyze_financial_health quand une entree requise est absente du state - meme
    discipline que MissingAccountingInputError (accounting.py)."""


def initialize_node(state: FinancialHealthState) -> FinancialHealthState:
    champs_requis = ("workflow_id", "company_id", "period_start", "period_end")
    manquants = [champ for champ in champs_requis if champ not in state]
    if manquants:
        raise ValueError(f"FinancialHealthState incomplet avant initialize : champ(s) manquant(s) {manquants}")

    workflow_id = state["workflow_id"]
    logger.info(
        "financial_health_workflow workflow_id=%s node=%s status=%s",
        workflow_id, NODE_INITIALIZE, WorkflowStatus.RUNNING.value,
    )
    mise_a_jour: FinancialHealthState = {"status": WorkflowStatus.RUNNING}
    if "errors" not in state:
        mise_a_jour["errors"] = []
    return mise_a_jour


def finalize_node(state: FinancialHealthState) -> FinancialHealthState:
    logger.info(
        "financial_health_workflow workflow_id=%s node=%s status=%s",
        state.get("workflow_id"), NODE_FINALIZE, WorkflowStatus.COMPLETED.value,
    )
    return {"status": WorkflowStatus.COMPLETED}


def creer_get_financial_snapshot_node(mcp_client: McpFinanceClient) -> Callable[[FinancialHealthState], FinancialHealthState]:
    def get_financial_snapshot_node(state: FinancialHealthState) -> FinancialHealthState:
        snapshot = mcp_client.get_financial_snapshot(state["company_id"], state["period_start"], state["period_end"])
        logger.info(
            "financial_health_workflow workflow_id=%s node=%s revenue=%s profit=%s",
            state.get("workflow_id"), NODE_GET_FINANCIAL_SNAPSHOT, snapshot.snapshot.revenue, snapshot.snapshot.profit,
        )
        return {"financial_snapshot": snapshot}

    return get_financial_snapshot_node


def creer_get_cash_position_node(mcp_client: McpFinanceClient) -> Callable[[FinancialHealthState], FinancialHealthState]:
    def get_cash_position_node(state: FinancialHealthState) -> FinancialHealthState:
        cash_position = mcp_client.get_cash_position(state["company_id"], state["period_end"])
        logger.info(
            "financial_health_workflow workflow_id=%s node=%s total=%s",
            state.get("workflow_id"), NODE_GET_CASH_POSITION, cash_position.total,
        )
        return {"cash_position": cash_position}

    return get_cash_position_node


def creer_get_receivables_node(mcp_client: McpFinanceClient) -> Callable[[FinancialHealthState], FinancialHealthState]:
    def get_receivables_node(state: FinancialHealthState) -> FinancialHealthState:
        receivables = mcp_client.get_receivables(state["company_id"], state["period_end"])
        logger.info(
            "financial_health_workflow workflow_id=%s node=%s total=%s",
            state.get("workflow_id"), NODE_GET_RECEIVABLES, receivables.total,
        )
        return {"receivables": receivables}

    return get_receivables_node


def creer_get_payables_node(mcp_client: McpFinanceClient) -> Callable[[FinancialHealthState], FinancialHealthState]:
    def get_payables_node(state: FinancialHealthState) -> FinancialHealthState:
        payables = mcp_client.get_payables(state["company_id"], state["period_end"])
        logger.info(
            "financial_health_workflow workflow_id=%s node=%s total=%s",
            state.get("workflow_id"), NODE_GET_PAYABLES, payables.total,
        )
        return {"payables": payables}

    return get_payables_node


def creer_analyze_financial_health_node(
    assessment_service: FinancialHealthAssessmentService,
) -> Callable[[FinancialHealthState], FinancialHealthState]:
    """Le statut (WATCH/AT_RISK/CRITICAL) est calcule ICI de maniere deterministe
    (app/finance/health_policy.py) AVANT le moindre appel LLM - le node n'invoque le LLM que pour
    produire le texte narratif grounde dans ce statut, jamais pour le decider (section 24 du
    plan)."""

    def analyze_financial_health_node(state: FinancialHealthState) -> FinancialHealthState:
        snapshot = state.get("financial_snapshot")
        cash_position = state.get("cash_position")
        receivables = state.get("receivables")
        payables = state.get("payables")
        if snapshot is None or cash_position is None or receivables is None or payables is None:
            raise MissingFinancialHealthInputError(
                "analyze_financial_health necessite financial_snapshot, cash_position, receivables et payables"
            )

        statut = determiner_statut(
            profit=snapshot.snapshot.profit, cash=cash_position.total,
            receivables=receivables.total, payables=payables.total,
        )
        assessment = assessment_service.evaluer(
            status=statut, snapshot=snapshot, cash_position=cash_position,
            receivables=receivables, payables=payables, workflow_id=state.get("workflow_id"),
        )
        logger.info(
            "financial_health_workflow workflow_id=%s node=%s status=%s",
            state.get("workflow_id"), NODE_ANALYZE_FINANCIAL_HEALTH, assessment.status.value,
        )
        return {"assessment": assessment}

    return analyze_financial_health_node


def build_financial_health_graph(
    financial_client: FinancialClient,
    assessment_service: FinancialHealthAssessmentService,
    checkpointer: BaseCheckpointSaver | None = None,
) -> CompiledStateGraph:
    """Factory explicite (jamais compile au moment de l'import) - meme discipline que
    build_accounting_graph (STEP 6/7) : `financial_client`/`assessment_service` sont les seules
    dependances externes, MCP est construit ici en interne (STEP 7.9-style) autour de
    `financial_client`, jamais expose comme parametre de cette factory."""

    finance_mcp_server = creer_finance_mcp_server(financial_client)
    mcp_client = McpFinanceClient(finance_mcp_server)

    get_financial_snapshot_node = creer_get_financial_snapshot_node(mcp_client)
    get_cash_position_node = creer_get_cash_position_node(mcp_client)
    get_receivables_node = creer_get_receivables_node(mcp_client)
    get_payables_node = creer_get_payables_node(mcp_client)
    analyze_financial_health_node = creer_analyze_financial_health_node(assessment_service)

    graph = StateGraph(FinancialHealthState)
    graph.add_node(NODE_INITIALIZE, initialize_node)
    graph.add_node(NODE_GET_FINANCIAL_SNAPSHOT, get_financial_snapshot_node)
    graph.add_node(NODE_GET_CASH_POSITION, get_cash_position_node)
    graph.add_node(NODE_GET_RECEIVABLES, get_receivables_node)
    graph.add_node(NODE_GET_PAYABLES, get_payables_node)
    graph.add_node(NODE_ANALYZE_FINANCIAL_HEALTH, analyze_financial_health_node)
    graph.add_node(NODE_FINALIZE, finalize_node)

    graph.add_edge(START, NODE_INITIALIZE)
    graph.add_edge(NODE_INITIALIZE, NODE_GET_FINANCIAL_SNAPSHOT)
    graph.add_edge(NODE_GET_FINANCIAL_SNAPSHOT, NODE_GET_CASH_POSITION)
    graph.add_edge(NODE_GET_CASH_POSITION, NODE_GET_RECEIVABLES)
    graph.add_edge(NODE_GET_RECEIVABLES, NODE_GET_PAYABLES)
    graph.add_edge(NODE_GET_PAYABLES, NODE_ANALYZE_FINANCIAL_HEALTH)
    graph.add_edge(NODE_ANALYZE_FINANCIAL_HEALTH, NODE_FINALIZE)
    graph.add_edge(NODE_FINALIZE, END)
    return graph.compile(checkpointer=checkpointer)
