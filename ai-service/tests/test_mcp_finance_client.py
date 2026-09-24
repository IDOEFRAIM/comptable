"""McpFinanceClient est le seul point par lequel le Financial Health Graph doit appeler une
capability financiere via MCP. Verifie le reclassement d'erreurs (le coeur de ce module)."""

from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from app.mcp.finance_client import McpFinanceClient, McpFinanceToolCallError
from app.mcp.finance_server import creer_finance_mcp_server
from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    ForecastConfidence,
    Payables,
    Receivables,
    WorkingCapital,
    WorkingCapitalStatus,
)
from app.services.financial_client import (
    FinancialClient,
    FinancialServiceUnavailableError,
    FinancialValidationError,
)


def _mcp_client(financial_client: MagicMock) -> McpFinanceClient:
    return McpFinanceClient(creer_finance_mcp_server(financial_client))


def test_get_cash_position_retourne_le_schema_type():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 27), total=Decimal(12500000), par_compte=[])

    resultat = _mcp_client(client).get_cash_position(company_id)

    assert isinstance(resultat, CashPosition)
    assert resultat.total == Decimal(12500000)


def test_get_financial_snapshot_retourne_le_schema_type():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_financial_snapshot.return_value = FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 31),
            cash=Decimal(12500000), receivables=Decimal(21800000), payables=Decimal(9400000),
            revenue=Decimal(35000000), expenses=Decimal(30800000), profit=Decimal(4200000), taxes=Decimal(0),
        ),
        net_margin=Decimal("0.12"),
    )

    resultat = _mcp_client(client).get_financial_snapshot(company_id, date(2026, 8, 1), date(2026, 8, 31))

    assert isinstance(resultat, FinancialSnapshotWithMargins)
    assert resultat.snapshot.profit == Decimal(4200000)
    client.get_financial_snapshot.assert_called_once_with(company_id, date(2026, 8, 1), date(2026, 8, 31))


def test_get_receivables_retourne_le_schema_type():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_receivables.return_value = Receivables(
        as_of=date(2026, 8, 27), total=Decimal(21800000), current=Decimal(15000000), overdue=Decimal(6800000),
        aging_0_30=Decimal(15000000), aging_31_60=Decimal(5000000), aging_61_90=Decimal(1000000), aging_plus_90=Decimal(800000),
    )

    resultat = _mcp_client(client).get_receivables(company_id)

    assert isinstance(resultat, Receivables)
    assert resultat.total == Decimal(21800000)


def test_get_payables_retourne_le_schema_type():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_payables.return_value = Payables(
        as_of=date(2026, 8, 27), total=Decimal(9400000), current=Decimal(9400000), overdue=Decimal(0),
        aging_0_30=Decimal(9400000), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )

    resultat = _mcp_client(client).get_payables(company_id)

    assert isinstance(resultat, Payables)
    assert resultat.total == Decimal(9400000)


def test_get_working_capital_retourne_le_schema_type():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_working_capital.return_value = WorkingCapital(
        as_of=date(2026, 8, 27), status=WorkingCapitalStatus.AVAILABLE, working_capital=Decimal(17400000),
    )

    resultat = _mcp_client(client).get_working_capital(company_id)

    assert isinstance(resultat, WorkingCapital)
    assert resultat.status is WorkingCapitalStatus.AVAILABLE


def test_get_cash_forecast_retourne_le_schema_type():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_forecast.return_value = CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=Decimal(3800000), confidence=ForecastConfidence.MEDIUM,
    )

    resultat = _mcp_client(client).get_cash_forecast(company_id, date(2026, 8, 1), 13, Decimal(5000000))

    assert isinstance(resultat, CashForecast)
    assert resultat.closing_cash == Decimal(3800000)
    client.get_cash_forecast.assert_called_once_with(company_id, date(2026, 8, 1), 13, Decimal(5000000))


def test_une_financial_client_error_traverse_mcp_inchangee():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_receivables.side_effect = FinancialValidationError("Entreprise introuvable", 404, "detail")

    with pytest.raises(FinancialValidationError) as exc_info:
        _mcp_client(client).get_receivables(company_id)

    assert exc_info.value.status_code == 404
    assert exc_info.value.human_reviewable is True


def test_une_financial_client_error_retryable_traverse_mcp_avec_son_flag_intact():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_position.side_effect = FinancialServiceUnavailableError("indisponible", 503)

    with pytest.raises(FinancialServiceUnavailableError) as exc_info:
        _mcp_client(client).get_cash_position(company_id)

    assert exc_info.value.retryable is True


def test_une_erreur_non_financial_client_devient_mcp_finance_tool_call_error():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_working_capital.side_effect = RuntimeError("bug inattendu")

    with pytest.raises(McpFinanceToolCallError) as exc_info:
        _mcp_client(client).get_working_capital(company_id)

    assert exc_info.value.retryable is False
    assert exc_info.value.human_reviewable is False
