from datetime import date
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest

from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
    WorkingCapital,
)
from app.services.financial_client import (
    FinancialAuthenticationError,
    FinancialAuthorizationError,
    FinancialClient,
    FinancialResponseMalformedError,
    FinancialTimeoutError,
    FinancialTransportError,
    FinancialValidationError,
)

_REPONSE_CASH = {
    "dateReference": "2026-08-27",
    "total": "12500000",
    "parCompte": [
        {"compteId": str(uuid4()), "compteNumero": "521000", "compteIntitule": "Banque BOA", "canal": "BANQUE", "solde": "12500000"}
    ],
}

_REPONSE_SNAPSHOT = {
    "snapshot": {
        "periodeDebut": "2026-08-01",
        "periodeFin": "2026-08-31",
        "cash": "12500000",
        "receivables": "21800000",
        "payables": "9400000",
        "revenue": "35000000",
        "expenses": "30800000",
        "profit": "4200000",
        "taxes": "0",
    },
    "netMargin": "0.1200",
    "netMarginUnavailableReason": None,
    "grossMargin": None,
    "grossMarginUnavailableReason": "insufficient_data",
}

_REPONSE_RECEIVABLES = {
    "asOf": "2026-08-27",
    "total": "21800000",
    "current": "15000000",
    "overdue": "6800000",
    "aging0a30": "15000000",
    "aging31a60": "5000000",
    "aging61a90": "1000000",
    "agingPlus90": "800000",
    "topDebtors": [{"tiersId": str(uuid4()), "nom": "Client Alpha", "montant": "15000000"}],
}

_REPONSE_PAYABLES = {
    "asOf": "2026-08-27",
    "total": "9400000",
    "current": "9400000",
    "overdue": "0",
    "aging0a30": "9400000",
    "aging31a60": "0",
    "aging61a90": "0",
    "agingPlus90": "0",
    "topCreditors": [{"tiersId": str(uuid4()), "nom": "Fournisseur Beta", "montant": "9400000"}],
}

_REPONSE_WORKING_CAPITAL = {
    "asOf": "2026-08-27",
    "status": "AVAILABLE",
    "workingCapital": "17400000",
    "receivables": "21800000",
    "payables": "9400000",
    "inventory": "5000000",
    "missingComponents": [],
}

_REPONSE_CASH_FORECAST = {
    "asOf": "2026-08-01",
    "periodStart": "2026-08-01",
    "periodEnd": "2026-10-30",
    "currency": "XOF",
    "openingCash": "12500000",
    "closingCash": "3800000",
    "minimumCashThreshold": "5000000",
    "confidence": "MEDIUM",
    "dataQualityWarnings": [],
    "limitations": ["supplier_due_dates_unavailable: ..."],
    "weeks": [
        {
            "weekIndex": 0, "weekStart": "2026-08-01", "weekEnd": "2026-08-07",
            "openingCash": "12500000", "inflows": "1000000", "outflows": "2000000",
            "netCashFlow": "-1000000", "closingCash": "11500000",
            "knownInflows": "1000000", "expectedInflows": "0", "predictedInflows": "0",
            "knownOutflows": "500000", "expectedOutflows": "0", "predictedOutflows": "1500000",
        }
    ],
    "liquidityEvents": [
        {
            "type": "CASH_BELOW_THRESHOLD", "severity": "MEDIUM", "weekIndex": 7,
            "weekStart": "2026-09-19", "amount": "3800000",
            "reason": "Solde projete sous le seuil minimum.", "source": "CashForecastService",
        }
    ],
}


def _client_avec_handler(handler) -> FinancialClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.Client(transport=transport, base_url="http://java-test", headers={"X-Internal-Api-Key": "test-key"})
    return FinancialClient(http_client=http_client)


def test_get_cash_position_reussi_retourne_le_schema_type():
    company_id = uuid4()

    def handler(request):
        assert request.url.path == f"/api/v1/internal/ai-finance/{company_id}/cash"
        return httpx.Response(200, json=_REPONSE_CASH)

    client = _client_avec_handler(handler)
    resultat = client.get_cash_position(company_id)

    assert isinstance(resultat, CashPosition)
    assert resultat.total == Decimal(12500000)
    assert resultat.par_compte[0].canal == "BANQUE"


def test_get_cash_position_transmet_la_date_en_parametre_iso():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_CASH)

    client = _client_avec_handler(handler)
    client.get_cash_position(uuid4(), as_of=date(2026, 8, 27))

    assert requetes_recues[0].url.params["date"] == "2026-08-27"


def test_get_financial_snapshot_reussi_retourne_revenue_expenses_profit_margin():
    company_id = uuid4()

    def handler(request):
        assert request.url.path == f"/api/v1/internal/ai-finance/{company_id}/snapshot"
        assert request.url.params["periodStart"] == "2026-08-01"
        assert request.url.params["periodEnd"] == "2026-08-31"
        return httpx.Response(200, json=_REPONSE_SNAPSHOT)

    client = _client_avec_handler(handler)
    resultat = client.get_financial_snapshot(company_id, date(2026, 8, 1), date(2026, 8, 31))

    assert isinstance(resultat, FinancialSnapshotWithMargins)
    assert resultat.snapshot.revenue == Decimal(35000000)
    assert resultat.snapshot.expenses == Decimal(30800000)
    assert resultat.snapshot.profit == Decimal(4200000)
    assert resultat.net_margin == Decimal("0.1200")
    assert resultat.gross_margin is None
    assert resultat.gross_margin_unavailable_reason == "insufficient_data"


def test_get_receivables_reussi_retourne_total_current_overdue_et_top_debtors():
    def handler(request):
        return httpx.Response(200, json=_REPONSE_RECEIVABLES)

    client = _client_avec_handler(handler)
    resultat = client.get_receivables(uuid4())

    assert isinstance(resultat, Receivables)
    assert resultat.total == Decimal(21800000)
    assert resultat.overdue == Decimal(6800000)
    assert resultat.top_debtors[0].nom == "Client Alpha"


def test_get_payables_reussi_retourne_des_montants_positifs():
    def handler(request):
        return httpx.Response(200, json=_REPONSE_PAYABLES)

    client = _client_avec_handler(handler)
    resultat = client.get_payables(uuid4())

    assert isinstance(resultat, Payables)
    assert resultat.total == Decimal(9400000)
    assert resultat.top_creditors[0].montant == Decimal(9400000)


def test_get_working_capital_reussi_retourne_le_statut_disponible():
    def handler(request):
        return httpx.Response(200, json=_REPONSE_WORKING_CAPITAL)

    client = _client_avec_handler(handler)
    resultat = client.get_working_capital(uuid4())

    assert isinstance(resultat, WorkingCapital)
    assert resultat.status.value == "AVAILABLE"
    assert resultat.working_capital == Decimal(17400000)
    assert resultat.missing_components == []


def test_get_cash_forecast_reussi_retourne_le_schema_type():
    company_id = uuid4()

    def handler(request):
        assert request.url.path == f"/api/v1/internal/ai-finance/{company_id}/cash-forecast"
        return httpx.Response(200, json=_REPONSE_CASH_FORECAST)

    client = _client_avec_handler(handler)
    resultat = client.get_cash_forecast(company_id)

    assert isinstance(resultat, CashForecast)
    assert resultat.opening_cash == Decimal(12500000)
    assert resultat.closing_cash == Decimal(3800000)
    assert resultat.confidence.value == "MEDIUM"
    assert len(resultat.weeks) == 1
    assert resultat.weeks[0].known_inflows == Decimal(1000000)
    assert len(resultat.liquidity_events) == 1
    assert resultat.liquidity_events[0].type.value == "CASH_BELOW_THRESHOLD"
    assert resultat.liquidity_events[0].severity.value == "MEDIUM"


def test_get_cash_forecast_transmet_les_parametres_optionnels():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_CASH_FORECAST)

    client = _client_avec_handler(handler)
    client.get_cash_forecast(uuid4(), start_date=date(2026, 8, 1), weeks=13, minimum_cash_threshold=Decimal(5000000))

    params = requetes_recues[0].url.params
    assert params["startDate"] == "2026-08-01"
    assert params["weeks"] == "13"
    assert params["minimumCashThreshold"] == "5000000"


def test_get_cash_forecast_sans_parametres_optionnels_nenvoie_aucun_parametre():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_CASH_FORECAST)

    client = _client_avec_handler(handler)
    client.get_cash_forecast(uuid4())

    assert dict(requetes_recues[0].url.params) == {}


def test_401_devient_une_erreur_authentification_non_retryable():
    appels = []

    def handler(request):
        appels.append(request)
        return httpx.Response(401)

    client = _client_avec_handler(handler)
    with pytest.raises(FinancialAuthenticationError) as exc_info:
        client.get_cash_position(uuid4())

    assert exc_info.value.retryable is False
    assert len(appels) == 1


def test_403_devient_une_erreur_autorisation_non_retryable():
    def handler(request):
        return httpx.Response(403)

    client = _client_avec_handler(handler)
    with pytest.raises(FinancialAuthorizationError):
        client.get_receivables(uuid4())


def test_404_entreprise_introuvable_est_une_erreur_de_validation_human_reviewable():
    def handler(request):
        return httpx.Response(404, json={"message": "Entreprise introuvable"})

    client = _client_avec_handler(handler)
    with pytest.raises(FinancialValidationError) as exc_info:
        client.get_payables(uuid4())

    assert exc_info.value.status_code == 404
    assert exc_info.value.human_reviewable is True


def test_timeout_est_retryable_et_reessaie_trois_fois():
    appels = []

    def handler(request):
        appels.append(request)
        raise httpx.ReadTimeout("delai depasse", request=request)

    client = _client_avec_handler(handler)
    with pytest.raises(FinancialTimeoutError):
        client.get_working_capital(uuid4())

    assert len(appels) == 3


def test_503_est_retryable_et_reussit_apres_un_premier_echec():
    appels = []

    def handler(request):
        appels.append(request)
        if len(appels) == 1:
            return httpx.Response(503)
        return httpx.Response(200, json=_REPONSE_CASH)

    client = _client_avec_handler(handler)
    resultat = client.get_cash_position(uuid4())

    assert resultat.total == Decimal(12500000)
    assert len(appels) == 2


def test_erreur_de_connexion_est_une_erreur_de_transport_retryable():
    def handler(request):
        raise httpx.ConnectError("connexion refusee", request=request)

    client = _client_avec_handler(handler)
    with pytest.raises(FinancialTransportError) as exc_info:
        client.get_cash_position(uuid4())

    assert exc_info.value.retryable is True


def test_reponse_malformee_est_rejetee_jamais_retryable():
    def handler(request):
        return httpx.Response(200, json={"ceci": "ne ressemble pas au schema attendu"})

    client = _client_avec_handler(handler)
    with pytest.raises(FinancialResponseMalformedError) as exc_info:
        client.get_working_capital(uuid4())

    assert exc_info.value.retryable is False


def test_les_headers_requis_sont_transmis_a_java():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_CASH)

    client = _client_avec_handler(handler)
    client.get_cash_position(uuid4())

    requete = requetes_recues[0]
    assert requete.headers["X-Internal-Api-Key"] == "test-key"
    assert "X-Request-Id" in requete.headers


def test_aucune_methode_nest_mutative_toutes_utilisent_get():
    # STEP 8, section 19 du plan : "les tools doivent etre READ ONLY" - verifie que chaque
    # capability n'emet qu'un GET, jamais un POST/PUT/DELETE.
    methodes_verbes = []

    def handler(request, reponse):
        methodes_verbes.append(request.method)
        return httpx.Response(200, json=reponse)

    _client_avec_handler(lambda r: handler(r, _REPONSE_CASH)).get_cash_position(uuid4())
    _client_avec_handler(lambda r: handler(r, _REPONSE_RECEIVABLES)).get_receivables(uuid4())
    _client_avec_handler(lambda r: handler(r, _REPONSE_PAYABLES)).get_payables(uuid4())
    _client_avec_handler(lambda r: handler(r, _REPONSE_WORKING_CAPITAL)).get_working_capital(uuid4())
    _client_avec_handler(lambda r: handler(r, _REPONSE_SNAPSHOT)).get_financial_snapshot(
        uuid4(), date(2026, 8, 1), date(2026, 8, 31)
    )

    assert methodes_verbes == ["GET"] * 5
