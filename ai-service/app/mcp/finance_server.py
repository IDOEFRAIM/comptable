"""Serveur MCP Financial Intelligence Layer (STEP 8, section 19-20 du plan d'implementation).

Meme discipline que app/mcp/server.py (AI Accounting, STEP 7) : chaque outil ci-dessous est un
pass-through trivial vers FinancialClient - aucune logique metier, aucun calcul financier, aucun
try/except (les erreurs remontent telles quelles, reclassees uniquement par
app/mcp/finance_client.py). SDK MCP officiel (mcp.server.mcpserver.MCPServer), jamais une
abstraction maison.

Tous les outils sont en LECTURE SEULE (section 19 du plan : "pas d'operation mutative ici") -
contrairement au serveur AI Accounting (app/mcp/server.py) qui expose accounting.post_entry, ce
serveur n'expose jamais d'ecriture."""

from datetime import date
from decimal import Decimal
from uuid import UUID

from mcp.server.mcpserver import MCPServer

from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
    WorkingCapital,
)
from app.services.financial_client import FinancialClient

MCP_SERVER_NAME = "ai-finance"

TOOL_GET_CASH_POSITION = "finance.get_cash_position"
TOOL_GET_FINANCIAL_SNAPSHOT = "finance.get_financial_snapshot"
TOOL_GET_RECEIVABLES = "finance.get_receivables"
TOOL_GET_PAYABLES = "finance.get_payables"
TOOL_GET_WORKING_CAPITAL = "finance.get_working_capital"
TOOL_GET_CASH_FORECAST = "finance.get_cash_forecast"


def creer_finance_mcp_server(financial_client: FinancialClient) -> MCPServer:
    """Factory explicite (jamais un singleton global) - meme discipline d'injection par fermeture
    que creer_mcp_server (AI Accounting, STEP 7) : chaque appelant fournit son propre
    FinancialClient (reel ou mock), ce qui garde le serveur MCP testable sans reseau reel."""

    server = MCPServer(name=MCP_SERVER_NAME)

    @server.tool(name=TOOL_GET_CASH_POSITION)
    def get_cash_position(company_id: UUID, as_of: date | None = None) -> CashPosition:
        """Position de tresorerie consolidee (comptes classe 5) a une date donnee - deja calculee
        par Java (CashPositionService), jamais additionnee ici."""
        return financial_client.get_cash_position(company_id, as_of)

    @server.tool(name=TOOL_GET_FINANCIAL_SNAPSHOT)
    def get_financial_snapshot(company_id: UUID, period_start: date, period_end: date) -> FinancialSnapshotWithMargins:
        """Synthese revenue/expenses/profit/margin/cash/receivables/payables pour une periode -
        deja calculee par Java (FinancialSnapshotService), jamais recalculee ici."""
        return financial_client.get_financial_snapshot(company_id, period_start, period_end)

    @server.tool(name=TOOL_GET_RECEIVABLES)
    def get_receivables(company_id: UUID, as_of: date | None = None) -> Receivables:
        """Creances clients : total/current/overdue/aging/top debiteurs - deja calcule par Java
        (BalanceAgeeService), jamais recalcule ici."""
        return financial_client.get_receivables(company_id, as_of)

    @server.tool(name=TOOL_GET_PAYABLES)
    def get_payables(company_id: UUID, as_of: date | None = None) -> Payables:
        """Dettes fournisseurs : total/current/overdue/aging/top crediteurs - deja calcule par
        Java (BalanceAgeeService), jamais recalcule ici."""
        return financial_client.get_payables(company_id, as_of)

    @server.tool(name=TOOL_GET_WORKING_CAPITAL)
    def get_working_capital(company_id: UUID, as_of: date | None = None) -> WorkingCapital:
        """Besoin en fonds de roulement (receivables + inventory - payables) - deja calcule par
        Java (AiFinanceQueryService), jamais recalcule ici. `status=NOT_AVAILABLE` si un composant
        manque - jamais un chiffre invente."""
        return financial_client.get_working_capital(company_id, as_of)

    @server.tool(name=TOOL_GET_CASH_FORECAST)
    def get_cash_forecast(
        company_id: UUID, start_date: date | None = None, weeks: int | None = None,
        minimum_cash_threshold: Decimal | None = None,
    ) -> CashForecast:
        """Previsions de tresorerie sur plusieurs semaines (KNOWN/EXPECTED/PREDICTED), confiance et
        evenements de liquidite - deja calcules par Java (CashForecastService), jamais recalcules
        ici. Lecture seule, aucune ecriture."""
        return financial_client.get_cash_forecast(company_id, start_date, weeks, minimum_cash_threshold)

    return server
