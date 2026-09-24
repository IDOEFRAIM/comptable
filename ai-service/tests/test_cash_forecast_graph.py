"""STEP 9.14-9.17 : le workflow "Cash Forecast" de bout en bout, avec Java/LLM entierement
mockes (aucun appel reseau reel)."""

from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

from app.agent.checkpoint import creer_checkpointer_cash_forecast
from app.agent.graph.cash_forecast import (
    NODE_ANALYZE_CASH_RISK,
    NODE_GET_CASH_FORECAST,
    build_cash_forecast_graph,
)
from app.agent.state.cash_forecast import nouvel_etat_initial
from app.finance.cash_risk_assessment_service import CashRiskAssessmentService
from app.schemas.cash_risk import CashRiskStatus
from app.schemas.finance import (
    CashForecast,
    CashForecastWeek,
    ForecastConfidence,
    LiquidityEvent,
    LiquidityEventType,
    LiquiditySeverity,
)
from app.services.financial_client import FinancialClient
from app.services.mock_llm_provider import MockLLMProvider


def _semaine(index: int, closing: Decimal) -> CashForecastWeek:
    return CashForecastWeek(
        week_index=index, week_start=date(2026, 8, 1).replace(day=1), week_end=date(2026, 8, 7),
        opening_cash=Decimal(0), inflows=Decimal(0), outflows=Decimal(0), net_cash_flow=Decimal(0),
        closing_cash=closing, known_inflows=Decimal(0), expected_inflows=Decimal(0), predicted_inflows=Decimal(0),
        known_outflows=Decimal(0), expected_outflows=Decimal(0), predicted_outflows=Decimal(0),
    )


def _client_mock(weeks: list[CashForecastWeek], events: list[LiquidityEvent] | None = None,
                  threshold: Decimal | None = None, confidence=ForecastConfidence.MEDIUM) -> MagicMock:
    client = MagicMock(spec=FinancialClient)
    client.get_cash_forecast.return_value = CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=weeks[-1].closing_cash, minimum_cash_threshold=threshold,
        confidence=confidence, weeks=weeks, liquidity_events=events or [],
    )
    return client


def _assessment_service(reponse: dict) -> CashRiskAssessmentService:
    return CashRiskAssessmentService(llm_provider=MockLLMProvider(responses=[reponse]))


def test_le_graph_contient_les_deux_noeuds_attendus():
    client = _client_mock([_semaine(0, Decimal(10000000))])
    service = _assessment_service({"main_drivers": [], "positive_factors": [], "recommended_actions": []})

    graph = build_cash_forecast_graph(financial_client=client, assessment_service=service)

    assert {NODE_GET_CASH_FORECAST, NODE_ANALYZE_CASH_RISK}.issubset(graph.nodes.keys())


def test_scenario_healthy_de_bout_en_bout():
    workflow_id, company_id = uuid4(), uuid4()
    client = _client_mock([_semaine(i, Decimal(10000000) - i * Decimal(100000)) for i in range(13)])
    service = _assessment_service(
        {
            "main_drivers": [],
            "positive_factors": ["La tresorerie reste largement positive sur tout l'horizon."],
            "recommended_actions": [],
            "data_completeness_notes": [],
        }
    )
    graph = build_cash_forecast_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), 13))

    assert resultat["risk_assessment"].status is CashRiskStatus.HEALTHY
    assert resultat["forecast"].opening_cash == Decimal(12500000)
    assert resultat["status"].value == "COMPLETED"
    client.get_cash_forecast.assert_called_once_with(company_id, date(2026, 8, 1), 13, None)


def test_scenario_watch_avec_seuil_de_bout_en_bout():
    workflow_id, company_id = uuid4(), uuid4()
    semaines = [_semaine(i, Decimal(10000000)) for i in range(7)]
    semaines.append(_semaine(7, Decimal(3800000)))
    semaines.extend(_semaine(i, Decimal(9000000)) for i in range(8, 13))
    evenement = LiquidityEvent(
        type=LiquidityEventType.CASH_BELOW_THRESHOLD, severity=LiquiditySeverity.MEDIUM, week_index=7,
        week_start=date(2026, 8, 1), amount=Decimal(3800000), reason="Sous le seuil.", source="test",
    )
    client = _client_mock(semaines, events=[evenement], threshold=Decimal(5000000))
    service = _assessment_service(
        {
            "main_drivers": ["Encaissements clients ralentis avant la semaine 7."],
            "positive_factors": [],
            "recommended_actions": ["Relancer les creances avant la semaine 7."],
            "data_completeness_notes": [],
        }
    )
    graph = build_cash_forecast_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(
        workflow_id, company_id, date(2026, 8, 1), 13, minimum_cash_threshold=Decimal(5000000)
    ))

    assessment = resultat["risk_assessment"]
    assert assessment.status is CashRiskStatus.WATCH
    assert assessment.critical_week == 7
    assert assessment.minimum_projected_cash == Decimal(3800000)
    client.get_cash_forecast.assert_called_once_with(company_id, date(2026, 8, 1), 13, Decimal(5000000))


def test_scenario_critical_solde_negatif_de_bout_en_bout():
    workflow_id, company_id = uuid4(), uuid4()
    semaines = [_semaine(i, Decimal(5000000)) for i in range(5)]
    semaines.append(_semaine(5, Decimal(-200000)))
    semaines.extend(_semaine(i, Decimal(1000000)) for i in range(6, 13))
    evenement = LiquidityEvent(
        type=LiquidityEventType.NEGATIVE_CASH, severity=LiquiditySeverity.HIGH, week_index=5,
        week_start=date(2026, 8, 1), amount=Decimal(-200000), reason="Solde negatif.", source="test",
    )
    client = _client_mock(semaines, events=[evenement])
    service = _assessment_service(
        {
            "main_drivers": ["Sortie exceptionnelle en semaine 5."],
            "positive_factors": [],
            "recommended_actions": ["Reporter les depenses non essentielles avant la semaine 5."],
            "data_completeness_notes": [],
        }
    )
    graph = build_cash_forecast_graph(financial_client=client, assessment_service=service)

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), 13))

    assessment = resultat["risk_assessment"]
    assert assessment.status is CashRiskStatus.CRITICAL
    assert assessment.critical_week == 5
    assert assessment.minimum_projected_cash == Decimal(-200000)


def test_le_checkpointer_durci_fonctionne_pour_ce_graph():
    workflow_id, company_id = uuid4(), uuid4()
    client = _client_mock([_semaine(i, Decimal(10000000)) for i in range(13)])
    service = _assessment_service({"main_drivers": [], "positive_factors": [], "recommended_actions": []})
    graph = build_cash_forecast_graph(
        financial_client=client, assessment_service=service, checkpointer=creer_checkpointer_cash_forecast()
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, date(2026, 8, 1), 13), config=config)

    assert resultat["risk_assessment"] is not None
    etat_final = graph.get_state(config)
    assert etat_final.next == ()
