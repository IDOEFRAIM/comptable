"""Graph LangGraph Python "Cash Forecast" (STEP 9.14 du plan d'implementation, "Cash Flow
Intelligence").

Graph (sequence exacte du plan, section 24) :
    START -> get_cash_forecast -> analyze_cash_risk -> END

Deliberement SANS node initialize/finalize distinct (contrairement a AccountingState/
FinancialHealthState) : le plan (section 24) donne un graph a deux etapes seulement, et il n'y a
ici aucune validation d'entree/sortie supplementaire a faire qui justifierait des noeuds
additionnels - initialize/finalize seraient de la pure ceremonie pour un graph de cette taille.

Tous les appels de donnees passent par McpFinanceClient (jamais FinancialClient directement depuis
un node) - meme discipline que le graph Financial Health (STEP 8.8) et AI Accounting (STEP 7.9) :
MCP est la frontiere agentique, la logique metier (calcul du forecast) reste entierement cote
Java (CashForecastService)."""

import logging
from collections.abc import Callable

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.state.cash_forecast import CashForecastState
from app.finance.cash_risk_assessment_service import CashRiskAssessmentService
from app.finance.cash_risk_policy import (
    calculer_minimum_projete,
    determiner_statut,
    trouver_semaine_critique,
)
from app.mcp.finance_client import McpFinanceClient
from app.mcp.finance_server import creer_finance_mcp_server
from app.schemas.accounting import WorkflowStatus
from app.services.financial_client import FinancialClient

logger = logging.getLogger(__name__)

NODE_GET_CASH_FORECAST = "get_cash_forecast"
NODE_ANALYZE_CASH_RISK = "analyze_cash_risk"


class MissingCashForecastInputError(ValueError):
    """Leve par analyze_cash_risk quand le forecast est absent du state - meme discipline que
    MissingFinancialHealthInputError (financial_health.py)."""


def creer_get_cash_forecast_node(mcp_client: McpFinanceClient) -> Callable[[CashForecastState], CashForecastState]:
    def get_cash_forecast_node(state: CashForecastState) -> CashForecastState:
        forecast = mcp_client.get_cash_forecast(
            state["company_id"], state.get("start_date"), state.get("weeks"), state.get("minimum_cash_threshold"),
        )
        logger.info(
            "cash_forecast_workflow workflow_id=%s node=%s opening_cash=%s closing_cash=%s confidence=%s",
            state.get("workflow_id"), NODE_GET_CASH_FORECAST, forecast.opening_cash, forecast.closing_cash,
            forecast.confidence.value,
        )
        return {"forecast": forecast, "status": WorkflowStatus.RUNNING}

    return get_cash_forecast_node


def creer_analyze_cash_risk_node(
    assessment_service: CashRiskAssessmentService,
) -> Callable[[CashForecastState], CashForecastState]:
    """Le statut/la semaine critique/le solde minimum sont calcules ICI de maniere deterministe
    (app/finance/cash_risk_policy.py) AVANT le moindre appel LLM - le node n'invoque le LLM que
    pour produire le texte narratif grounde dans ces valeurs, jamais pour les decider (section 25
    du plan : "le LLM ne peut pas modifier closing_cash, forecast, confidence, severity")."""

    def analyze_cash_risk_node(state: CashForecastState) -> CashForecastState:
        forecast = state.get("forecast")
        if forecast is None:
            raise MissingCashForecastInputError("analyze_cash_risk necessite forecast dans le state")

        statut = determiner_statut(forecast)
        semaine_critique = trouver_semaine_critique(forecast)
        minimum_projete = calculer_minimum_projete(forecast)

        assessment = assessment_service.evaluer(
            status=statut, critical_week=semaine_critique, minimum_projected_cash=minimum_projete,
            forecast=forecast, workflow_id=state.get("workflow_id"),
        )
        logger.info(
            "cash_forecast_workflow workflow_id=%s node=%s status=%s critical_week=%s",
            state.get("workflow_id"), NODE_ANALYZE_CASH_RISK, assessment.status.value, assessment.critical_week,
        )
        return {"risk_assessment": assessment, "status": WorkflowStatus.COMPLETED}

    return analyze_cash_risk_node


def build_cash_forecast_graph(
    financial_client: FinancialClient,
    assessment_service: CashRiskAssessmentService,
    checkpointer: BaseCheckpointSaver | None = None,
) -> CompiledStateGraph:
    """Factory explicite (jamais compile au moment de l'import) - meme discipline que
    build_financial_health_graph (STEP 8.8) : `financial_client`/`assessment_service` sont les
    seules dependances externes, MCP est construit ici en interne autour de `financial_client`,
    jamais expose comme parametre de cette factory."""

    finance_mcp_server = creer_finance_mcp_server(financial_client)
    mcp_client = McpFinanceClient(finance_mcp_server)

    get_cash_forecast_node = creer_get_cash_forecast_node(mcp_client)
    analyze_cash_risk_node = creer_analyze_cash_risk_node(assessment_service)

    graph = StateGraph(CashForecastState)
    graph.add_node(NODE_GET_CASH_FORECAST, get_cash_forecast_node)
    graph.add_node(NODE_ANALYZE_CASH_RISK, analyze_cash_risk_node)

    graph.add_edge(START, NODE_GET_CASH_FORECAST)
    graph.add_edge(NODE_GET_CASH_FORECAST, NODE_ANALYZE_CASH_RISK)
    graph.add_edge(NODE_ANALYZE_CASH_RISK, END)
    return graph.compile(checkpointer=checkpointer)
