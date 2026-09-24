"""STEP 8.6-8.7 : le serveur MCP Financial Intelligence Layer expose 5 outils en lecture seule qui
delegent integralement a FinancialClient - aucune logique metier, aucun calcul, jamais une
operation mutative."""

import asyncio
from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from app.mcp.finance_server import (
    TOOL_GET_CASH_FORECAST,
    TOOL_GET_CASH_POSITION,
    TOOL_GET_FINANCIAL_SNAPSHOT,
    TOOL_GET_PAYABLES,
    TOOL_GET_RECEIVABLES,
    TOOL_GET_WORKING_CAPITAL,
    creer_finance_mcp_server,
)
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
from app.services.financial_client import FinancialClient, FinancialValidationError


def _snapshot_35m_revenue() -> FinancialSnapshotWithMargins:
    return FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 31),
            cash=Decimal(12500000), receivables=Decimal(21800000), payables=Decimal(9400000),
            revenue=Decimal(35000000), expenses=Decimal(30800000), profit=Decimal(4200000), taxes=Decimal(0),
        ),
        net_margin=Decimal("0.1200"), gross_margin=None, gross_margin_unavailable_reason="insufficient_data",
    )


def test_le_serveur_expose_exactement_les_six_outils_finance():
    server = creer_finance_mcp_server(MagicMock(spec=FinancialClient))
    noms = {t.name for t in asyncio.run(server.list_tools())}
    assert noms == {
        TOOL_GET_CASH_POSITION, TOOL_GET_FINANCIAL_SNAPSHOT, TOOL_GET_RECEIVABLES,
        TOOL_GET_PAYABLES, TOOL_GET_WORKING_CAPITAL, TOOL_GET_CASH_FORECAST,
    }


def test_get_cash_position_delegue_integralement_a_financial_client():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 27), total=Decimal(12500000), par_compte=[])
    server = creer_finance_mcp_server(client)

    resultat = asyncio.run(server.call_tool(TOOL_GET_CASH_POSITION, {"company_id": str(company_id)}))

    assert resultat.structured_content["total"] == "12500000"
    client.get_cash_position.assert_called_once_with(company_id, None)


def test_get_financial_snapshot_delegue_integralement_a_financial_client():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_financial_snapshot.return_value = _snapshot_35m_revenue()
    server = creer_finance_mcp_server(client)

    resultat = asyncio.run(server.call_tool(
        TOOL_GET_FINANCIAL_SNAPSHOT,
        {"company_id": str(company_id), "period_start": "2026-08-01", "period_end": "2026-08-31"},
    ))

    assert resultat.structured_content["snapshot"]["revenue"] == "35000000"
    assert resultat.structured_content["netMargin"] == "0.1200"
    client.get_financial_snapshot.assert_called_once_with(company_id, date(2026, 8, 1), date(2026, 8, 31))


def test_get_receivables_delegue_integralement_a_financial_client():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_receivables.return_value = Receivables(
        as_of=date(2026, 8, 27), total=Decimal(21800000), current=Decimal(15000000), overdue=Decimal(6800000),
        aging_0_30=Decimal(15000000), aging_31_60=Decimal(5000000), aging_61_90=Decimal(1000000), aging_plus_90=Decimal(800000),
    )
    server = creer_finance_mcp_server(client)

    resultat = asyncio.run(server.call_tool(TOOL_GET_RECEIVABLES, {"company_id": str(company_id)}))

    assert resultat.structured_content["total"] == "21800000"
    assert resultat.structured_content["overdue"] == "6800000"


def test_get_payables_delegue_integralement_a_financial_client():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_payables.return_value = Payables(
        as_of=date(2026, 8, 27), total=Decimal(9400000), current=Decimal(9400000), overdue=Decimal(0),
        aging_0_30=Decimal(9400000), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    server = creer_finance_mcp_server(client)

    resultat = asyncio.run(server.call_tool(TOOL_GET_PAYABLES, {"company_id": str(company_id)}))

    assert resultat.structured_content["total"] == "9400000"


def test_get_working_capital_delegue_integralement_a_financial_client():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_working_capital.return_value = WorkingCapital(
        as_of=date(2026, 8, 27), status=WorkingCapitalStatus.AVAILABLE, working_capital=Decimal(17400000),
        receivables=Decimal(21800000), payables=Decimal(9400000), inventory=Decimal(5000000),
    )
    server = creer_finance_mcp_server(client)

    resultat = asyncio.run(server.call_tool(TOOL_GET_WORKING_CAPITAL, {"company_id": str(company_id)}))

    assert resultat.structured_content["status"] == "AVAILABLE"
    assert resultat.structured_content["workingCapital"] == "17400000"


def test_les_outils_optionnels_acceptent_une_date_explicite():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_receivables.return_value = Receivables(
        as_of=date(2026, 8, 27), total=Decimal(0), current=Decimal(0), overdue=Decimal(0),
        aging_0_30=Decimal(0), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    server = creer_finance_mcp_server(client)

    asyncio.run(server.call_tool(TOOL_GET_RECEIVABLES, {"company_id": str(company_id), "as_of": "2026-08-27"}))

    client.get_receivables.assert_called_once_with(company_id, date(2026, 8, 27))


def test_get_cash_forecast_delegue_integralement_a_financial_client():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_forecast.return_value = CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=Decimal(3800000), confidence=ForecastConfidence.MEDIUM,
    )
    server = creer_finance_mcp_server(client)

    resultat = asyncio.run(server.call_tool(
        TOOL_GET_CASH_FORECAST,
        {"company_id": str(company_id), "start_date": "2026-08-01", "weeks": 13, "minimum_cash_threshold": "5000000"},
    ))

    assert resultat.structured_content["openingCash"] == "12500000"
    assert resultat.structured_content["confidence"] == "MEDIUM"
    client.get_cash_forecast.assert_called_once_with(company_id, date(2026, 8, 1), 13, Decimal(5000000))


def test_get_cash_forecast_sans_parametres_optionnels():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_forecast.return_value = CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=Decimal(12500000), confidence=ForecastConfidence.LOW,
    )
    server = creer_finance_mcp_server(client)

    asyncio.run(server.call_tool(TOOL_GET_CASH_FORECAST, {"company_id": str(company_id)}))

    client.get_cash_forecast.assert_called_once_with(company_id, None, None, None)


def test_une_erreur_financial_client_traverse_le_tool_sans_etre_avalee():
    company_id = uuid4()
    client = MagicMock(spec=FinancialClient)
    client.get_cash_position.side_effect = FinancialValidationError("Entreprise introuvable", 404, "detail")
    server = creer_finance_mcp_server(client)

    from mcp.server.mcpserver.exceptions import UnexpectedToolError

    with pytest.raises(UnexpectedToolError) as exc_info:
        asyncio.run(server.call_tool(TOOL_GET_CASH_POSITION, {"company_id": str(company_id)}))

    assert isinstance(exc_info.value.__cause__, FinancialValidationError)
