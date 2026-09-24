"""STEP 9.16 : CashRiskAssessmentService combine un statut deja decide (deterministe) avec une
interpretation narrative produite par le LLM - jamais l'inverse. Inclut le test anti-hallucination
mandate par le plan (section 30) : verifie qu'un solde de cloture donne (2.4M semaine 8) n'est
jamais deforme, et que le systeme ne pretend jamais une transaction absente des faits."""

from datetime import date
from decimal import Decimal

import pytest

from app.finance.cash_risk_assessment_service import CashRiskAssessmentService, CashRiskLlmError
from app.schemas.cash_risk import CashRiskAssessment, CashRiskStatus
from app.schemas.finance import (
    CashForecast,
    CashForecastWeek,
    ForecastConfidence,
    LiquidityEvent,
    LiquidityEventType,
    LiquiditySeverity,
)
from app.services.mock_llm_provider import MockLLMProvider


def _semaine(index: int, closing: Decimal) -> CashForecastWeek:
    return CashForecastWeek(
        week_index=index, week_start=date(2026, 8, 1), week_end=date(2026, 8, 7),
        opening_cash=Decimal(0), inflows=Decimal(0), outflows=Decimal(0), net_cash_flow=Decimal(0),
        closing_cash=closing, known_inflows=Decimal(0), expected_inflows=Decimal(0), predicted_inflows=Decimal(0),
        known_outflows=Decimal(0), expected_outflows=Decimal(0), predicted_outflows=Decimal(0),
    )


def _forecast_semaine_8_a_2_4m() -> CashForecast:
    semaines = [_semaine(i, Decimal(10000000)) for i in range(8)]
    semaines.append(_semaine(8, Decimal(2400000)))
    semaines.extend(_semaine(i, Decimal(6000000)) for i in range(9, 13))
    return CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=Decimal(6000000), minimum_cash_threshold=Decimal(5000000),
        confidence=ForecastConfidence.MEDIUM, weeks=semaines,
        liquidity_events=[LiquidityEvent(
            type=LiquidityEventType.CASH_BELOW_THRESHOLD, severity=LiquiditySeverity.MEDIUM, week_index=8,
            week_start=date(2026, 8, 1), amount=Decimal(2400000), reason="Solde sous le seuil.", source="test",
        )],
    )


def test_evaluer_combine_le_statut_deterministe_avec_linterpretation_llm():
    llm = MockLLMProvider(
        responses=[
            {
                "main_drivers": ["Encaissements clients ralentis en semaine 8."],
                "positive_factors": ["Le solde reste positif sur tout l'horizon."],
                "recommended_actions": ["Prioriser le recouvrement des creances avant la semaine 8."],
                "data_completeness_notes": [],
            }
        ]
    )
    service = CashRiskAssessmentService(llm_provider=llm)
    forecast = _forecast_semaine_8_a_2_4m()

    resultat = service.evaluer(
        status=CashRiskStatus.WATCH, critical_week=8, minimum_projected_cash=Decimal(2400000),
        forecast=forecast,
    )

    assert isinstance(resultat, CashRiskAssessment)
    assert resultat.status is CashRiskStatus.WATCH
    assert resultat.critical_week == 8
    assert resultat.minimum_projected_cash == Decimal(2400000)
    assert resultat.period == "2026-08-01/2026-10-30"
    assert resultat.currency == "XOF"


def test_le_llm_ne_peut_jamais_changer_le_statut_ou_la_semaine_critique():
    llm = MockLLMProvider(
        responses=[
            {
                "status": "HEALTHY", "critical_week": 99, "minimum_projected_cash": "999999999",
                "main_drivers": [], "positive_factors": [], "recommended_actions": [],
            }
        ]
    )
    service = CashRiskAssessmentService(llm_provider=llm)
    forecast = _forecast_semaine_8_a_2_4m()

    resultat = service.evaluer(
        status=CashRiskStatus.CRITICAL, critical_week=8, minimum_projected_cash=Decimal(2400000),
        forecast=forecast,
    )

    assert resultat.status is CashRiskStatus.CRITICAL
    assert resultat.critical_week == 8
    assert resultat.minimum_projected_cash == Decimal(2400000)


def test_reponse_llm_non_conforme_leve_cash_risk_llm_error():
    llm = MockLLMProvider(responses=[{"main_drivers": "pas une liste"}])
    service = CashRiskAssessmentService(llm_provider=llm)

    with pytest.raises(CashRiskLlmError):
        service.evaluer(
            status=CashRiskStatus.WATCH, critical_week=None, minimum_projected_cash=Decimal(0),
            forecast=_forecast_semaine_8_a_2_4m(),
        )


def test_panne_llm_leve_cash_risk_llm_error():
    llm = MockLLMProvider(responses=[TimeoutError("panne simulee")])
    service = CashRiskAssessmentService(llm_provider=llm)

    with pytest.raises(CashRiskLlmError):
        service.evaluer(
            status=CashRiskStatus.AT_RISK, critical_week=3, minimum_projected_cash=Decimal(0),
            forecast=_forecast_semaine_8_a_2_4m(),
        )


def test_anti_hallucination_le_solde_de_cloture_semaine_8_nest_jamais_deforme():
    # STEP 9, section 30 du plan : closing_cash_week_8 = 2.4M - verifie que ce chiffre exact (et
    # aucun autre) apparait dans le texte transmis au LLM, jamais un solde arrondi/invente.
    llm = MockLLMProvider(responses=[{"main_drivers": [], "positive_factors": [], "recommended_actions": []}])
    service = CashRiskAssessmentService(llm_provider=llm)
    forecast = _forecast_semaine_8_a_2_4m()

    service.evaluer(
        status=CashRiskStatus.WATCH, critical_week=8, minimum_projected_cash=Decimal(2400000),
        forecast=forecast,
    )

    texte = llm.calls[0]["user_text"]
    assert "2400000" in texte
    assert "3100000" not in texte  # jamais un chiffre invente a la place du vrai solde


def test_anti_hallucination_aucun_evenement_absent_nest_mentionne_dans_le_prompt():
    # Le forecast n'a qu'un seul evenement (CASH_BELOW_THRESHOLD) - le texte transmis au LLM ne
    # doit jamais suggerer qu'un NEGATIVE_CASH ou LIQUIDITY_GAP a ete detecte.
    llm = MockLLMProvider(responses=[{"main_drivers": [], "positive_factors": [], "recommended_actions": []}])
    service = CashRiskAssessmentService(llm_provider=llm)
    forecast = _forecast_semaine_8_a_2_4m()

    service.evaluer(
        status=CashRiskStatus.WATCH, critical_week=8, minimum_projected_cash=Decimal(2400000),
        forecast=forecast,
    )

    texte = llm.calls[0]["user_text"]
    assert "NEGATIVE_CASH" not in texte
    assert "LIQUIDITY_GAP" not in texte
    assert "CASH_BELOW_THRESHOLD" in texte
