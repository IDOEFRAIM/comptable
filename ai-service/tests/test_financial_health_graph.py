"""STEP 8.8-8.10 : le workflow "Financial Health Check" de bout en bout, avec Java/LLM entierement
mockes (aucun appel reseau reel) - fixtures realistes XOF (section 27 du plan)."""

from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

from app.agent.checkpoint import creer_checkpointer_financial_health
from app.agent.graph.financial_health import (
    NODE_ANALYZE_FINANCIAL_HEALTH,
    NODE_FINALIZE,
    NODE_GET_CASH_POSITION,
    NODE_GET_FINANCIAL_SNAPSHOT,
    NODE_GET_PAYABLES,
    NODE_GET_RECEIVABLES,
    NODE_INITIALIZE,
    build_financial_health_graph,
)
from app.agent.state.financial_health import nouvel_etat_initial
from app.finance.health_assessment_service import FinancialHealthAssessmentService
from app.schemas.finance import (
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
)
from app.schemas.financial_health import FinancialHealthStatus
from app.services.financial_client import FinancialClient
from app.services.mock_llm_provider import MockLLMProvider


def _client_mock(*, cash: Decimal, receivables_total: Decimal, payables_total: Decimal, profit: Decimal,
                  revenue: Decimal = Decimal(35000000), expenses: Decimal = Decimal(30800000)) -> MagicMock:
    client = MagicMock(spec=FinancialClient)
    client.get_financial_snapshot.return_value = FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 31),
            cash=cash, receivables=receivables_total, payables=payables_total,
            revenue=revenue, expenses=expenses, profit=profit, taxes=Decimal(0),
        ),
        net_margin=(profit / revenue) if revenue != 0 else None,
        net_margin_unavailable_reason=None if revenue != 0 else "insufficient_data",
    )
    client.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 31), total=cash, par_compte=[])
    client.get_receivables.return_value = Receivables(
        as_of=date(2026, 8, 31), total=receivables_total, current=receivables_total, overdue=Decimal(0),
        aging_0_30=receivables_total, aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    client.get_payables.return_value = Payables(
        as_of=date(2026, 8, 31), total=payables_total, current=payables_total, overdue=Decimal(0),
        aging_0_30=payables_total, aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    return client


def _assessment_service(reponse: dict) -> FinancialHealthAssessmentService:
    return FinancialHealthAssessmentService(llm_provider=MockLLMProvider(responses=[reponse]))


def test_le_graph_contient_tous_les_noeuds_attendus():
    client = _client_mock(cash=Decimal(1), receivables_total=Decimal(1), payables_total=Decimal(1), profit=Decimal(1))
    service = _assessment_service({"signals": [], "risks": [], "positive_factors": [], "priority_actions": []})

    graph = build_financial_health_graph(financial_client=client, assessment_service=service)

    assert {
        NODE_INITIALIZE, NODE_GET_FINANCIAL_SNAPSHOT, NODE_GET_CASH_POSITION,
        NODE_GET_RECEIVABLES, NODE_GET_PAYABLES, NODE_ANALYZE_FINANCIAL_HEALTH, NODE_FINALIZE,
    }.issubset(graph.nodes.keys())


def test_scenario_positif_watch_de_bout_en_bout():
    workflow_id, company_id = uuid4(), uuid4()
    client = _client_mock(
        cash=Decimal(12500000), receivables_total=Decimal(21800000), payables_total=Decimal(9400000), profit=Decimal(4200000),
    )
    service = _assessment_service(
        {
            "signals": ["Les creances (21.8M) representent 1.7x la tresorerie disponible (12.5M)."],
            "risks": ["Dependance forte au recouvrement."],
            "positive_factors": ["Profit positif (4.2M) sur la periode."],
            "priority_actions": ["Prioriser le recouvrement des creances clients."],
            "data_completeness_notes": [],
        }
    )
    graph = build_financial_health_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), date(2026, 8, 31)))

    assert resultat["assessment"].status is FinancialHealthStatus.WATCH
    assert resultat["financial_snapshot"].snapshot.revenue == Decimal(35000000)
    assert resultat["cash_position"].total == Decimal(12500000)
    assert resultat["receivables"].total == Decimal(21800000)
    assert resultat["payables"].total == Decimal(9400000)
    assert resultat["assessment"].positive_factors
    client.get_financial_snapshot.assert_called_once_with(company_id, date(2026, 8, 1), date(2026, 8, 31))


def test_scenario_a_risque_at_risk_de_bout_en_bout():
    workflow_id, company_id = uuid4(), uuid4()
    # Cash tres insuffisant pour couvrir les dettes fournisseurs en instance.
    client = _client_mock(
        cash=Decimal(2000000), receivables_total=Decimal(1000000), payables_total=Decimal(5000000), profit=Decimal(500000),
    )
    service = _assessment_service(
        {
            "signals": ["La tresorerie (2M) ne couvre pas les dettes fournisseurs en instance (5M)."],
            "risks": ["Risque de tension de tresorerie a court terme."],
            "positive_factors": ["L'entreprise reste beneficiaire (500K)."],
            "priority_actions": ["Negocier des delais de paiement fournisseurs ou accelerer l'encaissement."],
            "data_completeness_notes": [],
        }
    )
    graph = build_financial_health_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), date(2026, 8, 31)))

    assert resultat["assessment"].status is FinancialHealthStatus.AT_RISK
    assert resultat["assessment"].risks


def test_scenario_critique_perte_operationnelle_de_bout_en_bout():
    workflow_id, company_id = uuid4(), uuid4()
    client = _client_mock(
        cash=Decimal(5000000), receivables_total=Decimal(1000000), payables_total=Decimal(1000000),
        profit=Decimal(-2000000), revenue=Decimal(10000000), expenses=Decimal(12000000),
    )
    service = _assessment_service(
        {
            "signals": ["L'entreprise opere a perte sur la periode (-2M)."],
            "risks": ["Perte operationnelle non couverte par le chiffre d'affaires actuel."],
            "positive_factors": [],
            "priority_actions": ["Revoir la structure de couts ou le pricing avant la prochaine periode."],
            "data_completeness_notes": [],
        }
    )
    graph = build_financial_health_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), date(2026, 8, 31)))

    assert resultat["assessment"].status is FinancialHealthStatus.CRITICAL
    assert resultat["assessment"].positive_factors == []


def test_scenario_donnees_manquantes_revenu_nul_ne_fait_pas_echouer_le_workflow():
    # STEP 8, section 27 : "missing data" - le revenue nul rend le net margin indisponible
    # (insufficient_data), mais le workflow doit tout de meme se terminer proprement, jamais
    # planter ni inventer une marge.
    workflow_id, company_id = uuid4(), uuid4()
    client = _client_mock(
        cash=Decimal(3000000), receivables_total=Decimal(500000), payables_total=Decimal(200000),
        profit=Decimal(0), revenue=Decimal(0), expenses=Decimal(0),
    )
    service = _assessment_service(
        {
            "signals": [],
            "risks": [],
            "positive_factors": [],
            "priority_actions": [],
            "data_completeness_notes": ["Marge nette indisponible : aucun revenu enregistre sur la periode."],
        }
    )
    graph = build_financial_health_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), date(2026, 8, 31)))

    assert resultat["financial_snapshot"].net_margin is None
    assert resultat["financial_snapshot"].net_margin_unavailable_reason == "insufficient_data"
    assert resultat["assessment"].data_completeness_notes
    assert resultat["status"].value == "COMPLETED"


def test_le_checkpointer_durci_fonctionne_pour_ce_graph():
    workflow_id, company_id = uuid4(), uuid4()
    client = _client_mock(cash=Decimal(1000000), receivables_total=Decimal(500000), payables_total=Decimal(200000), profit=Decimal(100000))
    service = _assessment_service({"signals": [], "risks": [], "positive_factors": [], "priority_actions": []})
    graph = build_financial_health_graph(
        financial_client=client, assessment_service=service, checkpointer=creer_checkpointer_financial_health()
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), date(2026, 8, 31)), config=config)

    assert resultat["assessment"] is not None
    etat_final = graph.get_state(config)
    assert etat_final.next == ()
