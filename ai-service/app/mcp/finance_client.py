"""Client MCP Financial Intelligence Layer (STEP 8 du plan d'implementation) : point d'entree
synchrone unique par lequel le futur Financial Health Graph appelle les capabilities financieres
exposees comme des outils MCP (app/mcp/finance_server.py), plutot que d'appeler FinancialClient
directement depuis un node - meme discipline que app/mcp/client.py (AI Accounting, STEP 7.9).

Reclassement des erreurs (meme principe que app/mcp/client.py) :
- UnexpectedToolError dont la cause est une FinancialClientError -> relevee telle quelle (meme
  type, memes retryable/human_reviewable).
- Toute autre erreur (ToolError d'entree invalide, UnexpectedToolError sans cause
  FinancialClientError) -> McpFinanceToolCallError, qui HERITE de FinancialClientError pour que
  l'appelant continue a ne connaitre qu'une seule famille d'erreurs.

Synchrone par choix (asyncio.run), meme limite assumee que app/mcp/client.py : ne peut pas etre
appele depuis un contexte ou une boucle asyncio tourne deja."""

import asyncio
from datetime import date
from decimal import Decimal
from typing import Any
from uuid import UUID

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError, UnexpectedToolError

from app.mcp.finance_server import (
    TOOL_GET_CASH_FORECAST,
    TOOL_GET_CASH_POSITION,
    TOOL_GET_FINANCIAL_SNAPSHOT,
    TOOL_GET_PAYABLES,
    TOOL_GET_RECEIVABLES,
    TOOL_GET_WORKING_CAPITAL,
)
from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
    WorkingCapital,
)
from app.services.financial_client import FinancialClientError


class McpFinanceToolCallError(FinancialClientError):
    """Erreur MCP qui n'a pas pu etre reclassee vers la FinancialClientError d'origine - herite de
    FinancialClientError pour que tout appelant continue a ne connaitre qu'une seule famille
    d'erreurs, qu'il passe par MCP ou par FinancialClient directement."""

    def __init__(self, message: str, *, retryable: bool = False, human_reviewable: bool = False):
        super().__init__(message, retryable=retryable, human_reviewable=human_reviewable)


def _appeler_outil_sync(server: MCPServer, nom_outil: str, arguments: dict[str, Any]) -> Any:
    try:
        resultat = asyncio.run(server.call_tool(nom_outil, arguments))
    except UnexpectedToolError as exc:
        cause = exc.__cause__
        if isinstance(cause, FinancialClientError):
            raise cause from exc
        raise McpFinanceToolCallError(f"Erreur inattendue de l'outil MCP {nom_outil} : {cause}") from exc
    except ToolError as exc:
        raise McpFinanceToolCallError(
            f"Entree invalide pour l'outil MCP {nom_outil} : {exc}", human_reviewable=True
        ) from exc
    return resultat.structured_content


def _arguments_avec_date(company_id: UUID, as_of: date | None) -> dict[str, Any]:
    arguments: dict[str, Any] = {"company_id": str(company_id)}
    if as_of is not None:
        arguments["as_of"] = as_of.isoformat()
    return arguments


class McpFinanceClient:
    """Point d'entree unique pour le Financial Health Graph appelant une capability financiere via
    MCP - jamais FinancialClient directement depuis un node. Meme forme d'API que FinancialClient
    (memes noms de methode/types de retour) pour que l'injection reste un detail cote graph."""

    def __init__(self, server: MCPServer):
        self._server = server

    def get_cash_position(self, company_id: UUID, as_of: date | None = None) -> CashPosition:
        contenu = _appeler_outil_sync(self._server, TOOL_GET_CASH_POSITION, _arguments_avec_date(company_id, as_of))
        return CashPosition.model_validate(contenu)

    def get_financial_snapshot(self, company_id: UUID, period_start: date, period_end: date) -> FinancialSnapshotWithMargins:
        arguments = {"company_id": str(company_id), "period_start": period_start.isoformat(), "period_end": period_end.isoformat()}
        contenu = _appeler_outil_sync(self._server, TOOL_GET_FINANCIAL_SNAPSHOT, arguments)
        return FinancialSnapshotWithMargins.model_validate(contenu)

    def get_receivables(self, company_id: UUID, as_of: date | None = None) -> Receivables:
        contenu = _appeler_outil_sync(self._server, TOOL_GET_RECEIVABLES, _arguments_avec_date(company_id, as_of))
        return Receivables.model_validate(contenu)

    def get_payables(self, company_id: UUID, as_of: date | None = None) -> Payables:
        contenu = _appeler_outil_sync(self._server, TOOL_GET_PAYABLES, _arguments_avec_date(company_id, as_of))
        return Payables.model_validate(contenu)

    def get_working_capital(self, company_id: UUID, as_of: date | None = None) -> WorkingCapital:
        contenu = _appeler_outil_sync(self._server, TOOL_GET_WORKING_CAPITAL, _arguments_avec_date(company_id, as_of))
        return WorkingCapital.model_validate(contenu)

    def get_cash_forecast(
        self, company_id: UUID, start_date: date | None = None, weeks: int | None = None,
        minimum_cash_threshold: Decimal | None = None,
    ) -> CashForecast:
        arguments: dict[str, Any] = {"company_id": str(company_id)}
        if start_date is not None:
            arguments["start_date"] = start_date.isoformat()
        if weeks is not None:
            arguments["weeks"] = weeks
        if minimum_cash_threshold is not None:
            arguments["minimum_cash_threshold"] = str(minimum_cash_threshold)
        contenu = _appeler_outil_sync(self._server, TOOL_GET_CASH_FORECAST, arguments)
        return CashForecast.model_validate(contenu)
