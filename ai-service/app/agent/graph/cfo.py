"""Graph LangGraph Python "AI CFO" (STEP 11 du plan d'implementation) - le premier agent
conversationnel construit au-dessus de TOUTES les capabilities existantes. N'introduit AUCUNE
nouvelle infrastructure financiere (section "IMPORTANT" du plan) : chaque node orchestre, jamais
ne calcule.

Graph (section 9 du plan) :
    classify_intent -> select_tools -> execute_tool -+-(more_tools)-+
                                                        +-(done)-> collect_facts -> analyze -> finalize -> END

Tous les appels de donnees passent par McpAccountingClient/McpFinanceClient (jamais
AccountingClient/FinancialClient directement depuis un node) - meme discipline que tous les
graphs precedents (STEP 7.9/8.8/9.14) : MCP est la frontiere agentique.

WRITE (accounting.post_entry) est gate par une approbation humaine (interrupt/resume via
app/agent/hitl.py, section 13/19/38 du plan - "aucune exception") : jamais execute sans decision
explicite. SCENARIO degrade explicitement vers "capability not available" (Scenario Engine
n'existe pas dans ce repository, choix valide explicitement par l'utilisateur pour STEP 11)."""

import logging
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.hitl import demander_revue_humaine, est_acquittement_sans_correction
from app.agent.policies.cfo_tool_policy import approbation_requise
from app.agent.state.cfo import CfoState
from app.agent.tools.catalog import CFO_TOOL_CATALOG
from app.cfo.analysis import CfoAnalysisService
from app.cfo.fact_extractor import FinancialFactExtractor
from app.cfo.intent import IntentClassificationError, classify_intent
from app.cfo.planner import ToolPlanningError, select_tools
from app.finance.health_policy import determiner_statut
from app.mcp.client import McpAccountingClient
from app.mcp.finance_client import McpFinanceClient
from app.mcp.finance_server import (
    TOOL_GET_CASH_FORECAST,
    TOOL_GET_CASH_POSITION,
    TOOL_GET_FINANCIAL_SNAPSHOT,
    TOOL_GET_PAYABLES,
    TOOL_GET_RECEIVABLES,
    TOOL_GET_WORKING_CAPITAL,
    creer_finance_mcp_server,
)
from app.mcp.server import (
    TOOL_GET_DOCUMENT,
    TOOL_POST_ENTRY,
    TOOL_VALIDATE_PROPOSAL,
    creer_mcp_server,
)
from app.schemas.accounting import (
    ApprovalResult,
    DocumentExtractionSnapshot,
    PostingResult,
    ValidationResult,
    WorkflowStatus,
)
from app.schemas.cfo import CfoIntent, FinancialFact, ToolResultRecord
from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
    WorkingCapital,
)
from app.services.accounting_client import AccountingClient, AccountingClientError
from app.services.financial_client import FinancialClient, FinancialClientError
from app.services.llm_provider import LLMProvider

logger = logging.getLogger(__name__)

NODE_CLASSIFY_INTENT = "classify_intent"
NODE_SELECT_TOOLS = "select_tools"
NODE_EXECUTE_TOOL = "execute_tool"
NODE_COLLECT_FACTS = "collect_facts"
NODE_ANALYZE = "analyze"
NODE_FINALIZE = "finalize"

MAX_TOOL_CALLS = 8
"""Guardrail (section 26 du plan) - configurable via le parametre du meme nom de
build_cfo_graph, jamais hardcode ailleurs que cette constante par defaut."""

_MODELE_PAR_TOOL: dict[str, type] = {
    TOOL_GET_DOCUMENT: DocumentExtractionSnapshot,
    TOOL_VALIDATE_PROPOSAL: ValidationResult,
    TOOL_POST_ENTRY: PostingResult,
    TOOL_GET_CASH_POSITION: CashPosition,
    TOOL_GET_FINANCIAL_SNAPSHOT: FinancialSnapshotWithMargins,
    TOOL_GET_RECEIVABLES: Receivables,
    TOOL_GET_PAYABLES: Payables,
    TOOL_GET_WORKING_CAPITAL: WorkingCapital,
    TOOL_GET_CASH_FORECAST: CashForecast,
}

_TOOLS_SANS_ARGUMENT_CONTEXTUEL_MANQUANT = {
    TOOL_GET_CASH_POSITION, TOOL_GET_FINANCIAL_SNAPSHOT, TOOL_GET_RECEIVABLES,
    TOOL_GET_PAYABLES, TOOL_GET_WORKING_CAPITAL, TOOL_GET_CASH_FORECAST,
}
"""Tools financiers qui ne dependent que de company_id (toujours present) - jamais bloques par
une donnee contextuelle manquante, contrairement a accounting.get_document/validate_proposal/
post_entry qui dependent respectivement de document_id/pending_proposal (fournis par l'appelant,
jamais devines - section 27 du plan)."""


def classify_intent_node(state: CfoState, llm_provider: LLMProvider | None = None) -> CfoState:
    try:
        intent, confidence = classify_intent(state["user_message"], llm_provider)
    except IntentClassificationError as exc:
        # Degrade explicitement vers GENERAL_FINANCIAL plutot que de faire echouer tout le tour
        # pour un simple probleme de routage - une decision de graph, pas cachee dans le service
        # (voir docstring de IntentClassificationError).
        logger.warning("cfo_workflow workflow_id=%s node=%s result=degrade error=%s",
                        state.get("workflow_id"), NODE_CLASSIFY_INTENT, type(exc).__name__)
        intent, confidence = CfoIntent.GENERAL_FINANCIAL, 0.0

    logger.info("cfo_workflow workflow_id=%s node=%s intent=%s confidence=%s",
                state.get("workflow_id"), NODE_CLASSIFY_INTENT, intent.value, confidence)
    return {"intent": intent, "intent_confidence": confidence, "status": WorkflowStatus.RUNNING}


def creer_select_tools_node(llm_provider: LLMProvider | None = None) -> Callable[[CfoState], CfoState]:
    def select_tools_node(state: CfoState) -> CfoState:
        intent = state.get("intent", CfoIntent.GENERAL_FINANCIAL)
        try:
            outils = select_tools(state["user_message"], intent, llm_provider, CFO_TOOL_CATALOG)
        except ToolPlanningError as exc:
            logger.warning("cfo_workflow workflow_id=%s node=%s result=degrade error=%s",
                            state.get("workflow_id"), NODE_SELECT_TOOLS, type(exc).__name__)
            outils = []

        logger.info("cfo_workflow workflow_id=%s node=%s intent=%s tools=%s",
                     state.get("workflow_id"), NODE_SELECT_TOOLS, intent.value, outils)
        return {"planned_tools": outils}

    return select_tools_node


def _arguments_pour_tool(tool_name: str, state: CfoState) -> dict[str, Any] | None:
    """Construit les arguments d'un appel de tool EXCLUSIVEMENT a partir du contexte deja present
    dans le state (jamais du texte libre du LLM - section 27 du plan, tenant lock). Retourne None
    si une donnee contextuelle requise manque (document_id/pending_proposal) - le tool est alors
    saute plutot qu'appele avec une donnee devinee."""

    company_id: UUID = state["company_id"]
    if tool_name in _TOOLS_SANS_ARGUMENT_CONTEXTUEL_MANQUANT:
        return {"company_id": company_id}
    if tool_name == TOOL_GET_DOCUMENT:
        document_id = state.get("document_id")
        return {"document_id": document_id} if document_id is not None else None
    if tool_name in (TOOL_VALIDATE_PROPOSAL, TOOL_POST_ENTRY):
        proposal = state.get("pending_proposal")
        return {"proposal": proposal} if proposal is not None else None
    return None


def _decision_vers_approval_result(decision: Any) -> ApprovalResult:
    """Convertit la decision de reprise HITL en ApprovalResult (section 38 du plan : "aucune
    exception" pour WRITE) - fail-closed dans tous les cas ambigus : seul un dict explicite
    `{"approved": true, ...}` produit une approbation, jamais l'acquittement generique ("ack",
    voir app/agent/hitl.py) ni aucune autre valeur non reconnue."""

    if est_acquittement_sans_correction(decision) or not isinstance(decision, dict):
        return ApprovalResult(approved=False)
    return ApprovalResult(
        approved=bool(decision.get("approved", False)),
        reviewer_id=decision.get("reviewer_id"),
        comment=decision.get("comment"),
    )


def creer_execute_tool_node(
    accounting_mcp_client: McpAccountingClient, finance_mcp_client: McpFinanceClient,
    max_tool_calls: int = MAX_TOOL_CALLS,
) -> Callable[[CfoState], CfoState]:
    def execute_tool_node(state: CfoState) -> CfoState:
        planned = list(state.get("planned_tools", []))
        tool_calls = list(state.get("tool_calls", []))
        tool_results = list(state.get("tool_results", []))
        errors = list(state.get("errors", []))

        if len(tool_calls) >= max_tool_calls:
            logger.warning("cfo_workflow workflow_id=%s node=%s result=max_tool_calls_reached limite=%d",
                            state.get("workflow_id"), NODE_EXECUTE_TOOL, max_tool_calls)
            errors.append(f"max_tool_calls_reached ({max_tool_calls})")
            return {"planned_tools": [], "errors": errors}

        if not planned:
            return {}

        tool_name, reste = planned[0], planned[1:]

        if approbation_requise(tool_name):
            approval = state.get("approval_result")
            if approval is None:
                decision = demander_revue_humaine(
                    {
                        "type": "CFO_WRITE_APPROVAL",
                        "workflow_id": str(state.get("workflow_id")),
                        "company_id": str(state["company_id"]),
                        "tool_name": tool_name,
                        "reason": "posting_requires_human_approval",
                    }
                )
                approval = _decision_vers_approval_result(decision)
            if not approval.approved:
                logger.info("cfo_workflow workflow_id=%s node=%s tool=%s result=refuse_no_post",
                            state.get("workflow_id"), NODE_EXECUTE_TOOL, tool_name)
                tool_results.append(ToolResultRecord(
                    tool_name=tool_name, arguments={}, result=None, error="not_approved_no_post",
                    timestamp=datetime.now(UTC),
                ))
                return {
                    "planned_tools": reste, "tool_calls": [*tool_calls, tool_name],
                    "tool_results": tool_results, "approval_result": approval,
                }
            state = {**state, "approval_result": approval}  # visible pour un eventuel 2e WRITE tool planifie

        arguments = _arguments_pour_tool(tool_name, state)
        if arguments is None:
            logger.info("cfo_workflow workflow_id=%s node=%s tool=%s result=saute_contexte_manquant",
                        state.get("workflow_id"), NODE_EXECUTE_TOOL, tool_name)
            tool_results.append(ToolResultRecord(
                tool_name=tool_name, arguments={}, result=None, error="missing_context",
                timestamp=datetime.now(UTC),
            ))
            return {"planned_tools": reste, "tool_calls": [*tool_calls, tool_name], "tool_results": tool_results}

        try:
            resultat = _appeler_tool(tool_name, arguments, accounting_mcp_client, finance_mcp_client)
            erreur_texte = None
        except (AccountingClientError, FinancialClientError) as exc:
            logger.warning("cfo_workflow workflow_id=%s node=%s tool=%s result=echec error=%s",
                            state.get("workflow_id"), NODE_EXECUTE_TOOL, tool_name, type(exc).__name__)
            resultat = None
            erreur_texte = type(exc).__name__

        mise_a_jour: CfoState = {
            "planned_tools": reste,
            "tool_calls": [*tool_calls, tool_name],
            "tool_results": [
                *tool_results,
                ToolResultRecord(
                    tool_name=tool_name,
                    arguments={cle: str(valeur) for cle, valeur in arguments.items() if cle != "proposal"},
                    result=resultat.model_dump(mode="json", by_alias=True) if resultat is not None else None,
                    error=erreur_texte, timestamp=datetime.now(UTC),
                ),
            ],
        }
        if tool_name == TOOL_POST_ENTRY and resultat is not None:
            mise_a_jour["posting_result"] = resultat
        return mise_a_jour

    return execute_tool_node


def _appeler_tool(
    tool_name: str, arguments: dict[str, Any],
    accounting_mcp_client: McpAccountingClient, finance_mcp_client: McpFinanceClient,
):
    if tool_name == TOOL_GET_DOCUMENT:
        return accounting_mcp_client.get_document_extraction(arguments["document_id"])
    if tool_name == TOOL_VALIDATE_PROPOSAL:
        return accounting_mcp_client.validate_proposal(arguments["proposal"])
    if tool_name == TOOL_POST_ENTRY:
        return accounting_mcp_client.post_entry(arguments["proposal"])
    if tool_name == TOOL_GET_CASH_POSITION:
        return finance_mcp_client.get_cash_position(arguments["company_id"])
    if tool_name == TOOL_GET_FINANCIAL_SNAPSHOT:
        aujourdhui = datetime.now(tz=UTC).date()
        debut_mois = aujourdhui.replace(day=1)
        return finance_mcp_client.get_financial_snapshot(arguments["company_id"], debut_mois, aujourdhui)
    if tool_name == TOOL_GET_RECEIVABLES:
        return finance_mcp_client.get_receivables(arguments["company_id"])
    if tool_name == TOOL_GET_PAYABLES:
        return finance_mcp_client.get_payables(arguments["company_id"])
    if tool_name == TOOL_GET_WORKING_CAPITAL:
        return finance_mcp_client.get_working_capital(arguments["company_id"])
    if tool_name == TOOL_GET_CASH_FORECAST:
        return finance_mcp_client.get_cash_forecast(arguments["company_id"])
    raise ValueError(f"Tool sans dispatch connu : {tool_name}")


def _router_more_tools(state: CfoState) -> str:
    return "continue" if state.get("planned_tools") else "collect"


def collect_facts_node(state: CfoState) -> CfoState:
    extractor = FinancialFactExtractor()
    facts = list(state.get("financial_facts", []))
    for record in state.get("tool_results", []):
        if record.result is None:
            continue
        modele = _MODELE_PAR_TOOL.get(record.tool_name)
        if modele is None:
            continue
        instance = modele.model_validate(record.result)
        facts.extend(extractor.extraire(record.tool_name, instance))

    logger.info("cfo_workflow workflow_id=%s node=%s facts=%d", state.get("workflow_id"), NODE_COLLECT_FACTS, len(facts))
    return {"financial_facts": facts}


def _premiere_valeur(facts: list[FinancialFact], *noms: str) -> Any:
    for nom in noms:
        for fait in facts:
            if fait.name == nom:
                return fait.value
    return None


def _deriver_statut_sante_financiere(financial_facts: list[FinancialFact]):
    """Reutilise app/finance/health_policy.py::determiner_statut (deterministe, STEP 8) tel quel -
    "ne jamais recreer health_policy" (section 11.12 du plan). Retourne None si un des quatre faits
    requis manque (jamais un statut devine a partir de donnees partielles)."""

    profit = _premiere_valeur(financial_facts, "profit")
    cash = _premiere_valeur(financial_facts, "cash")
    receivables = _premiere_valeur(financial_facts, "receivables_total", "receivables")
    payables = _premiere_valeur(financial_facts, "payables_total", "payables")
    if profit is None or cash is None or receivables is None or payables is None:
        return None
    return determiner_statut(profit=profit, cash=cash, receivables=receivables, payables=payables)


def creer_analyze_node(assessment_service: CfoAnalysisService) -> Callable[[CfoState], CfoState]:
    def analyze_node(state: CfoState) -> CfoState:
        intent = state.get("intent", CfoIntent.GENERAL_FINANCIAL)
        missing_capabilities = []
        financial_health_status = None
        if intent is CfoIntent.FINANCIAL_HEALTH:
            financial_health_status = _deriver_statut_sante_financiere(state.get("financial_facts", []))
        cash_forecast_confidence = None
        if intent is CfoIntent.CASH_FORECAST:
            for record in state.get("tool_results", []):
                if record.tool_name == TOOL_GET_CASH_FORECAST and record.result is not None:
                    cash_forecast_confidence = record.result.get("confidence")
                    break
        if intent is CfoIntent.SCENARIO:
            # Scenario Engine n'existe pas dans ce repository (audit STEP 11, degradation choisie
            # explicitement) - jamais un scenario invente.
            missing_capabilities.append(
                "scenario simulation not yet available (finance.simulate_scenario does not exist)"
            )
        for record in state.get("tool_results", []):
            if record.error == "not_approved_no_post":
                missing_capabilities.append(f"{record.tool_name} was not executed: posting not approved")
            elif record.error == "missing_context":
                missing_capabilities.append(f"{record.tool_name} was skipped: required context missing")

        tools_used = [record.tool_name for record in state.get("tool_results", []) if record.result is not None]
        response = assessment_service.evaluer(
            user_message=state["user_message"], intent=intent, financial_facts=state.get("financial_facts", []),
            tools_used=tools_used, missing_capabilities=missing_capabilities,
            financial_health_status=financial_health_status,
            cash_forecast_confidence=cash_forecast_confidence,
        )
        logger.info("cfo_workflow workflow_id=%s node=%s facts=%d tools_used=%d",
                    state.get("workflow_id"), NODE_ANALYZE, len(response.facts), len(response.tools_used))
        return {"analysis": response}

    return analyze_node


def finalize_node(state: CfoState) -> CfoState:
    logger.info("cfo_workflow workflow_id=%s node=%s status=%s",
                state.get("workflow_id"), NODE_FINALIZE, WorkflowStatus.COMPLETED.value)
    return {"response": state.get("analysis"), "status": WorkflowStatus.COMPLETED}


def build_cfo_graph(
    accounting_client: AccountingClient,
    financial_client: FinancialClient,
    analysis_service: CfoAnalysisService,
    *, llm_provider: LLMProvider | None = None,
    max_tool_calls: int = MAX_TOOL_CALLS,
    checkpointer: BaseCheckpointSaver | None = None,
) -> CompiledStateGraph:
    """Factory explicite (jamais compile au moment de l'import) - meme discipline que les graphs
    precedents (STEP 7/8/9) : `accounting_client`/`financial_client` sont les seules dependances
    de donnees externes, MCP est construit ici en interne, jamais expose comme parametre. Le
    classifieur d'intention et le planner partagent le meme `llm_provider` optionnel que
    `analysis_service` utilise deja en interne (injection explicite, jamais un singleton)."""

    accounting_mcp_server = creer_mcp_server(accounting_client)
    accounting_mcp_client = McpAccountingClient(accounting_mcp_server)
    finance_mcp_server = creer_finance_mcp_server(financial_client)
    finance_mcp_client = McpFinanceClient(finance_mcp_server)

    def classify_intent_node_lie(state: CfoState) -> CfoState:
        return classify_intent_node(state, llm_provider)

    select_tools_node = creer_select_tools_node(llm_provider)
    execute_tool_node = creer_execute_tool_node(accounting_mcp_client, finance_mcp_client, max_tool_calls)
    analyze_node = creer_analyze_node(analysis_service)

    graph = StateGraph(CfoState)
    graph.add_node(NODE_CLASSIFY_INTENT, classify_intent_node_lie)
    graph.add_node(NODE_SELECT_TOOLS, select_tools_node)
    graph.add_node(NODE_EXECUTE_TOOL, execute_tool_node)
    graph.add_node(NODE_COLLECT_FACTS, collect_facts_node)
    graph.add_node(NODE_ANALYZE, analyze_node)
    graph.add_node(NODE_FINALIZE, finalize_node)

    graph.add_edge(START, NODE_CLASSIFY_INTENT)
    graph.add_edge(NODE_CLASSIFY_INTENT, NODE_SELECT_TOOLS)
    graph.add_edge(NODE_SELECT_TOOLS, NODE_EXECUTE_TOOL)
    graph.add_conditional_edges(
        NODE_EXECUTE_TOOL, _router_more_tools, {"continue": NODE_EXECUTE_TOOL, "collect": NODE_COLLECT_FACTS},
    )
    graph.add_edge(NODE_COLLECT_FACTS, NODE_ANALYZE)
    graph.add_edge(NODE_ANALYZE, NODE_FINALIZE)
    graph.add_edge(NODE_FINALIZE, END)
    return graph.compile(checkpointer=checkpointer)
