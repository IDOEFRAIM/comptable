"""Client HTTP Python -> Java pour la Financial Intelligence Layer (STEP 8 du plan
d'implementation, "AI FINANCE").

Frontiere stricte, identique a accounting_client.py (STEP 4) : ce client ne contient AUCUN calcul
financier. Il appelle Java (/api/v1/internal/ai-finance/**, com.example.comptable.agent.finance.
AiFinanceQueryService) et renvoie exactement ce que Java a deja calcule - jamais une addition de
comptes, jamais un revenu/une marge recalcules cote Python. Toutes les capabilities exposees ici
sont en LECTURE SEULE (section 19 du plan) - aucune methode mutative dans ce client.

Hierarchie d'erreurs et politique de retry dupliquees intentionnellement depuis
accounting_client.py plutot que partagees : STEP 4-7 (AI Accounting) sont deja termines et
valides, ce module ne les modifie pas (meme discipline que AiFinanceController/
AiAccountingProposalValidationController cote Java, qui dupliquent chacun leur propre verification
de cle API plutot que de partager un helper)."""

import logging
import time
from datetime import date as Date
from decimal import Decimal
from typing import Self
from uuid import UUID, uuid4

import httpx
import tenacity
from pydantic import ValidationError

from app.core.config import get_settings
from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
    WorkingCapital,
)

logger = logging.getLogger(__name__)

_TIMEOUT_SECONDS = 15.0
_MAX_ATTEMPTS = 3
_BASE_PATH = "/api/v1/internal/ai-finance"


class FinancialClientError(Exception):
    """Base de toute erreur du client Java Finance - jamais une Exception generique remontee
    telle quelle. `retryable`/`human_reviewable` : meme convention que AccountingClientError."""

    def __init__(self, message: str, *, retryable: bool, human_reviewable: bool):
        super().__init__(message)
        self.retryable = retryable
        self.human_reviewable = human_reviewable


class FinancialTransportError(FinancialClientError):
    """Erreur reseau - retryable, jamais human-reviewable (toutes les capabilities sont en
    lecture seule, un retry ne peut jamais dupliquer quoi que ce soit)."""

    def __init__(self, message: str):
        super().__init__(message, retryable=True, human_reviewable=False)


class FinancialTimeoutError(FinancialClientError):
    """Timeout de l'appel - retryable."""

    def __init__(self, message: str):
        super().__init__(message, retryable=True, human_reviewable=False)


class FinancialServiceUnavailableError(FinancialClientError):
    """502/503/504 - retryable."""

    def __init__(self, message: str, status_code: int):
        super().__init__(message, retryable=True, human_reviewable=False)
        self.status_code = status_code


class FinancialAuthenticationError(FinancialClientError):
    """401 - jamais retryable (cle interne absente/invalide) - panne de configuration
    operationnelle, pas une revue metier."""

    def __init__(self, message: str):
        super().__init__(message, retryable=False, human_reviewable=False)


class FinancialAuthorizationError(FinancialClientError):
    """403 - jamais retryable, jamais human-reviewable."""

    def __init__(self, message: str):
        super().__init__(message, retryable=False, human_reviewable=False)


class FinancialValidationError(FinancialClientError):
    """400/404/422 - la requete est rejetee par Java (entreprise introuvable, periode invalide) -
    jamais retryable, human-reviewable (un comptable peut corriger les parametres et reessayer)."""

    def __init__(self, message: str, status_code: int, detail: str | None = None):
        super().__init__(message, retryable=False, human_reviewable=True)
        self.status_code = status_code
        self.detail = detail


class FinancialServiceError(FinancialClientError):
    """Toute autre erreur HTTP cote Java non classee ci-dessus - jamais retryable par defaut."""

    def __init__(self, message: str, status_code: int):
        super().__init__(message, retryable=False, human_reviewable=False)
        self.status_code = status_code


class FinancialResponseMalformedError(FinancialClientError):
    """Reponse 2xx mais dont le corps ne respecte pas le schema attendu - jamais retryable, jamais
    human-reviewable (bug technique, pas une decision comptable)."""

    def __init__(self, message: str):
        super().__init__(message, retryable=False, human_reviewable=False)


def _detail_sans_donnee_sensible(response: httpx.Response) -> str:
    try:
        corps = response.json()
    except ValueError:
        return f"HTTP {response.status_code}"
    if isinstance(corps, dict) and "message" in corps:
        return str(corps["message"])[:300]
    return f"HTTP {response.status_code}"


def _classifier_erreur_http(exc: httpx.HTTPStatusError) -> FinancialClientError:
    status = exc.response.status_code
    detail = _detail_sans_donnee_sensible(exc.response)
    if status == 401:
        return FinancialAuthenticationError(f"Authentification refusee par Java (401) : {detail}")
    if status == 403:
        return FinancialAuthorizationError(f"Acces refuse par Java (403) : {detail}")
    if status in (400, 404, 422):
        return FinancialValidationError(f"Requete rejetee par Java ({status}) : {detail}", status, detail)
    if status in (502, 503, 504):
        return FinancialServiceUnavailableError(f"Service Java indisponible ({status}) : {detail}", status)
    return FinancialServiceError(f"Erreur Java non classee ({status}) : {detail}", status)


def _est_retryable(exc: BaseException) -> bool:
    return isinstance(exc, FinancialClientError) and exc.retryable


_retry_policy = tenacity.retry(
    retry=tenacity.retry_if_exception(_est_retryable),
    wait=tenacity.wait_fixed(0.1),
    stop=tenacity.stop_after_attempt(_MAX_ATTEMPTS),
    reraise=True,
)


def _logger_appel(operation: str, company_id: UUID, request_id: UUID, statut: str, duree_s: float, erreur: str | None) -> None:
    if erreur:
        logger.warning(
            "financial_client operation=%s company_id=%s request_id=%s status=%s latency_ms=%d error=%s",
            operation, company_id, request_id, statut, int(duree_s * 1000), erreur,
        )
    else:
        logger.info(
            "financial_client operation=%s company_id=%s request_id=%s status=%s latency_ms=%d",
            operation, company_id, request_id, statut, int(duree_s * 1000),
        )


class FinancialClient:
    """Point d'entree unique pour un futur node LangGraph/outil MCP appelant les capabilities
    Financial Intelligence Layer cote Java. Toutes les methodes sont en lecture seule (STEP 8,
    section 19 du plan) : aucune n'ecrit quoi que ce soit cote Java.

    `http_client` est injectable pour les tests (httpx.MockTransport) ; par defaut construit un
    httpx.Client a partir de la configuration existante (meme base_url/cle que
    accounting_client.py/java_tools_client.py)."""

    def __init__(self, http_client: httpx.Client | None = None):
        if http_client is not None:
            self._client = http_client
            self._proprietaire_du_client = False
        else:
            settings = get_settings()
            self._client = httpx.Client(
                base_url=settings.core_service_base_url,
                headers={"X-Internal-Api-Key": settings.core_service_api_key},
                timeout=_TIMEOUT_SECONDS,
            )
            self._proprietaire_du_client = True

    def close(self) -> None:
        if self._proprietaire_du_client:
            self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_exc_info: object) -> None:
        self.close()

    @_retry_policy
    def _get(self, operation: str, company_id: UUID, path: str, params: dict[str, str], schema: type):
        """Cœur commun aux 5 capabilities ci-dessous : toutes sont un simple GET Java suivi d'une
        validation Pydantic - factoriser evite de repeter 5 fois le meme bloc try/except (aucune
        des 5 methodes publiques n'a de comportement divergent a ce niveau)."""

        request_id = uuid4()
        debut = time.perf_counter()
        headers = {"X-Request-Id": str(request_id)}

        try:
            response = self._client.get(path, params=params, headers=headers)
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            erreur = _classifier_erreur_http(exc)
            _logger_appel(operation, company_id, request_id, "echec", time.perf_counter() - debut, type(erreur).__name__)
            raise erreur from exc
        except httpx.TimeoutException as exc:
            _logger_appel(operation, company_id, request_id, "echec", time.perf_counter() - debut, "timeout")
            raise FinancialTimeoutError(f"Timeout apres {_TIMEOUT_SECONDS}s vers Java") from exc
        except httpx.HTTPError as exc:
            _logger_appel(operation, company_id, request_id, "echec", time.perf_counter() - debut, "transport")
            raise FinancialTransportError(f"Erreur de transport vers Java : {exc.__class__.__name__}") from exc

        try:
            resultat = schema.model_validate(response.json())
        except (ValueError, ValidationError) as exc:
            _logger_appel(operation, company_id, request_id, "echec", time.perf_counter() - debut, "reponse_malformee")
            raise FinancialResponseMalformedError(
                f"Reponse Java ne respecte pas {schema.__name__} : {exc.__class__.__name__}"
            ) from exc

        _logger_appel(operation, company_id, request_id, "succes", time.perf_counter() - debut, None)
        return resultat

    def get_cash_position(self, company_id: UUID, as_of: Date | None = None) -> CashPosition:
        """GET /api/v1/internal/ai-finance/{companyId}/cash - capability get_cash_position."""
        params = {"date": as_of.isoformat()} if as_of else {}
        return self._get("get_cash_position", company_id, f"{_BASE_PATH}/{company_id}/cash", params, CashPosition)

    def get_financial_snapshot(self, company_id: UUID, period_start: Date, period_end: Date) -> FinancialSnapshotWithMargins:
        """GET /api/v1/internal/ai-finance/{companyId}/snapshot - capabilities
        get_revenue/get_expenses/get_profit/get_margin (un seul appel Java, section 17 du plan)."""
        params = {"periodStart": period_start.isoformat(), "periodEnd": period_end.isoformat()}
        return self._get(
            "get_financial_snapshot", company_id, f"{_BASE_PATH}/{company_id}/snapshot", params, FinancialSnapshotWithMargins
        )

    def get_receivables(self, company_id: UUID, as_of: Date | None = None) -> Receivables:
        """GET /api/v1/internal/ai-finance/{companyId}/receivables - capability get_receivables."""
        params = {"date": as_of.isoformat()} if as_of else {}
        return self._get("get_receivables", company_id, f"{_BASE_PATH}/{company_id}/receivables", params, Receivables)

    def get_payables(self, company_id: UUID, as_of: Date | None = None) -> Payables:
        """GET /api/v1/internal/ai-finance/{companyId}/payables - capability get_payables."""
        params = {"date": as_of.isoformat()} if as_of else {}
        return self._get("get_payables", company_id, f"{_BASE_PATH}/{company_id}/payables", params, Payables)

    def get_working_capital(self, company_id: UUID, as_of: Date | None = None) -> WorkingCapital:
        """GET /api/v1/internal/ai-finance/{companyId}/working-capital - capability
        get_working_capital."""
        params = {"date": as_of.isoformat()} if as_of else {}
        return self._get(
            "get_working_capital", company_id, f"{_BASE_PATH}/{company_id}/working-capital", params, WorkingCapital
        )

    def get_cash_forecast(
        self, company_id: UUID, start_date: Date | None = None, weeks: int | None = None,
        minimum_cash_threshold: Decimal | None = None,
    ) -> CashForecast:
        """GET /api/v1/internal/ai-finance/{companyId}/cash-forecast - capability
        get_cash_forecast (STEP 9, "13-Week Cash Forecast"). Aucun calcul local : renvoie
        exactement le CashForecast deja construit par Java (CashForecastService)."""
        params: dict[str, str] = {}
        if start_date is not None:
            params["startDate"] = start_date.isoformat()
        if weeks is not None:
            params["weeks"] = str(weeks)
        if minimum_cash_threshold is not None:
            params["minimumCashThreshold"] = str(minimum_cash_threshold)
        return self._get(
            "get_cash_forecast", company_id, f"{_BASE_PATH}/{company_id}/cash-forecast", params, CashForecast
        )
