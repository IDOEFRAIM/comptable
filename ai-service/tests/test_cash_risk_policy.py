from datetime import date
from decimal import Decimal

from app.finance.cash_risk_policy import (
    calculer_minimum_projete,
    determiner_statut,
    trouver_semaine_critique,
)
from app.schemas.cash_risk import CashRiskStatus
from app.schemas.finance import (
    CashForecast,
    CashForecastWeek,
    ForecastConfidence,
    LiquidityEvent,
    LiquidityEventType,
    LiquiditySeverity,
)


def _semaine(index: int, closing: Decimal) -> CashForecastWeek:
    return CashForecastWeek(
        week_index=index, week_start=date(2026, 8, 1), week_end=date(2026, 8, 7),
        opening_cash=Decimal(0), inflows=Decimal(0), outflows=Decimal(0), net_cash_flow=Decimal(0),
        closing_cash=closing, known_inflows=Decimal(0), expected_inflows=Decimal(0), predicted_inflows=Decimal(0),
        known_outflows=Decimal(0), expected_outflows=Decimal(0), predicted_outflows=Decimal(0),
    )


def _forecast(weeks: list[CashForecastWeek], events: list[LiquidityEvent] | None = None,
              threshold: Decimal | None = None) -> CashForecast:
    return CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=weeks[-1].closing_cash if weeks else Decimal(0),
        minimum_cash_threshold=threshold, confidence=ForecastConfidence.MEDIUM,
        weeks=weeks, liquidity_events=events or [],
    )


def _event(type_: LiquidityEventType, week_index: int = 0, severity: LiquiditySeverity = LiquiditySeverity.MEDIUM) -> LiquidityEvent:
    return LiquidityEvent(
        type=type_, severity=severity, week_index=week_index, week_start=date(2026, 8, 1),
        amount=Decimal(1000000), reason="test", source="test",
    )


def test_aucun_evenement_est_healthy():
    forecast = _forecast([_semaine(0, Decimal(10000000)), _semaine(1, Decimal(9000000))])
    assert determiner_statut(forecast) is CashRiskStatus.HEALTHY


def test_un_creux_ponctuel_sous_le_seuil_est_watch():
    forecast = _forecast(
        [_semaine(0, Decimal(3000000))],
        events=[_event(LiquidityEventType.CASH_BELOW_THRESHOLD)],
        threshold=Decimal(5000000),
    )
    assert determiner_statut(forecast) is CashRiskStatus.WATCH


def test_une_tension_prolongee_est_at_risk():
    forecast = _forecast(
        [_semaine(0, Decimal(3000000)), _semaine(1, Decimal(2000000))],
        events=[_event(LiquidityEventType.CASH_BELOW_THRESHOLD), _event(LiquidityEventType.LIQUIDITY_GAP)],
        threshold=Decimal(5000000),
    )
    assert determiner_statut(forecast) is CashRiskStatus.AT_RISK


def test_un_solde_negatif_est_toujours_critical():
    forecast = _forecast(
        [_semaine(0, Decimal(1000000)), _semaine(1, Decimal(-500000))],
        events=[_event(LiquidityEventType.NEGATIVE_CASH, week_index=1, severity=LiquiditySeverity.HIGH)],
    )
    assert determiner_statut(forecast) is CashRiskStatus.CRITICAL


def test_trouver_semaine_critique_retourne_la_premiere_semaine_sous_le_seuil():
    forecast = _forecast(
        [_semaine(0, Decimal(10000000)), _semaine(1, Decimal(3000000)), _semaine(2, Decimal(2000000))],
        threshold=Decimal(5000000),
    )
    assert trouver_semaine_critique(forecast) == 1


def test_trouver_semaine_critique_retourne_none_sans_semaine_concernee():
    forecast = _forecast([_semaine(0, Decimal(10000000))], threshold=Decimal(5000000))
    assert trouver_semaine_critique(forecast) is None


def test_calculer_minimum_projete_retourne_le_plus_petit_solde_de_cloture():
    forecast = _forecast([_semaine(0, Decimal(10000000)), _semaine(1, Decimal(3800000)), _semaine(2, Decimal(9000000))])
    assert calculer_minimum_projete(forecast) == Decimal(3800000)
