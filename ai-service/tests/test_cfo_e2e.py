"""STEP 11.17 : scenarios de bout en bout du premier AI CFO, sur la fixture realiste du plan
(Revenue=35M/Expenses=30.8M/Profit=4.2M/Cash=12.5M/Receivables=21.8M/Payables=9.4M/Minimum
forecast cash=3.8M/Critical week=7, tous en XOF) - couvre au minimum les 8 questions listees en
Definition of Done. Les chiffres ci-dessus viennent toujours des tools scriptes dans chaque test,
jamais d'un prompt systeme hardcode (section 30 du plan)."""

from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

from app.agent.checkpoint import creer_checkpointer_cfo
from app.agent.graph.cfo import build_cfo_graph
from app.agent.state.cfo import nouvel_etat_initial
from app.cfo.analysis import CfoAnalysisService
from app.schemas.accounting import DocumentExtractionSnapshot, TypeDocument
from app.schemas.cfo import CfoResponse
from app.schemas.finance import (
    CashForecast,
    CashForecastWeek,
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    ForecastConfidence,
    Payables,
    PositionTresorerie,
    Receivables,
    WorkingCapital,
    WorkingCapitalStatus,
)
from app.services.accounting_client import AccountingClient
from app.services.financial_client import FinancialClient
from app.services.mock_llm_provider import MockLLMProvider

REVENUE = Decimal(35000000)
EXPENSES = Decimal(30800000)
PROFIT = Decimal(4200000)
CASH = Decimal(12500000)
RECEIVABLES = Decimal(21800000)
PAYABLES = Decimal(9400000)
FORECAST_MINIMUM = Decimal(3800000)
FORECAST_CRITICAL_WEEK = 7


def _semaine(index: int, closing: Decimal) -> CashForecastWeek:
    return CashForecastWeek(
        week_index=index, week_start=date(2026, 8, 1), week_end=date(2026, 8, 7),
        opening_cash=CASH, inflows=Decimal(0), outflows=Decimal(0), net_cash_flow=Decimal(0),
        closing_cash=closing, known_inflows=Decimal(0), expected_inflows=Decimal(0), predicted_inflows=Decimal(0),
        known_outflows=Decimal(0), expected_outflows=Decimal(0), predicted_outflows=Decimal(0),
    )


def _financial_client_realiste() -> MagicMock:
    client = MagicMock(spec=FinancialClient)
    client.get_financial_snapshot.return_value = FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 27),
            cash=CASH, receivables=RECEIVABLES, payables=PAYABLES,
            revenue=REVENUE, expenses=EXPENSES, profit=PROFIT, taxes=Decimal(0),
        ),
        net_margin=Decimal("0.12"),
    )
    client.get_cash_position.return_value = CashPosition(
        date_reference=date(2026, 8, 27), total=CASH,
        par_compte=[PositionTresorerie(compte_id=uuid4(), compte_numero="521000", compte_intitule="Banque", canal="BANQUE", solde=CASH)],
    )
    client.get_receivables.return_value = Receivables(
        as_of=date(2026, 8, 27), total=RECEIVABLES, current=Decimal(15000000), overdue=Decimal(6800000),
        aging_0_30=Decimal(15000000), aging_31_60=Decimal(5000000), aging_61_90=Decimal(1000000), aging_plus_90=Decimal(800000),
    )
    client.get_payables.return_value = Payables(
        as_of=date(2026, 8, 27), total=PAYABLES, current=PAYABLES, overdue=Decimal(0),
        aging_0_30=PAYABLES, aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    client.get_working_capital.return_value = WorkingCapital(
        as_of=date(2026, 8, 27), status=WorkingCapitalStatus.AVAILABLE, working_capital=Decimal(17400000),
        receivables=RECEIVABLES, payables=PAYABLES, inventory=Decimal(5000000),
    )
    client.get_cash_forecast.return_value = CashForecast(
        as_of=date(2026, 8, 27), period_start=date(2026, 8, 27), period_end=date(2026, 11, 26), currency="XOF",
        opening_cash=CASH, closing_cash=Decimal(6000000), confidence=ForecastConfidence.MEDIUM,
        minimum_cash_threshold=Decimal(5000000),
        weeks=(
            [_semaine(i, Decimal(10000000)) for i in range(FORECAST_CRITICAL_WEEK)]
            + [_semaine(FORECAST_CRITICAL_WEEK, FORECAST_MINIMUM)]
            + [_semaine(i, Decimal(6000000)) for i in range(FORECAST_CRITICAL_WEEK + 1, 13)]
        ),
    )
    return client


def _accounting_client_realiste() -> MagicMock:
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.return_value = DocumentExtractionSnapshot(
        document_id=uuid4(), company_id=uuid4(), type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000),
        fournisseur="Total Energies", confiance=0.95,
    )
    return client


def _graph(financial_client, accounting_client, llm_responses, analysis_response):
    llm = MockLLMProvider(responses=list(llm_responses))
    analysis_llm = MockLLMProvider(responses=[analysis_response])
    service = CfoAnalysisService(llm_provider=analysis_llm)
    graph = build_cfo_graph(
        accounting_client=accounting_client, financial_client=financial_client,
        analysis_service=service, llm_provider=llm,
    )
    return graph, analysis_llm


def test_1_comment_va_mon_entreprise():
    financial_client = _financial_client_realiste()
    graph, analysis_llm = _graph(
        financial_client, MagicMock(spec=AccountingClient),
        [
            {"intent": "FINANCIAL_HEALTH", "confidence": 0.95},
            {"tools": ["finance.get_financial_snapshot", "finance.get_cash_position", "finance.get_receivables", "finance.get_payables"]},
        ],
        {"summary": "Situation WATCH : rentable et solvable, mais creances a surveiller.", "analysis": [], "risks": [], "recommendations": [], "missing_data": []},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Comment va mon entreprise ?"))

    response: CfoResponse = resultat["response"]
    assert "WATCH" in analysis_llm.calls[0]["user_text"]
    assert {"revenue", "profit", "cash"}.issubset({f.name for f in response.facts})
    assert response.summary


def test_2_pourquoi_ma_tresorerie_baisse():
    financial_client = _financial_client_realiste()
    graph, _analysis_llm = _graph(
        financial_client, MagicMock(spec=AccountingClient),
        [
            {"intent": "CASH_POSITION", "confidence": 0.9},
            {"tools": ["finance.get_cash_position", "finance.get_cash_forecast", "finance.get_receivables", "finance.get_payables"]},
        ],
        {"summary": "La tresorerie baisse car les creances en retard pesent sur le recouvrement.",
         "analysis": ["6.8M de creances sont en retard."], "risks": ["Dependance au recouvrement client."],
         "recommendations": [], "missing_data": []},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Pourquoi ma tresorerie baisse ?"))

    response: CfoResponse = resultat["response"]
    noms_facts = {f.name for f in response.facts}
    assert {"cash", "receivables_overdue", "payables_total", "cash_forecast_minimum"}.issubset(noms_facts)
    assert response.risks


def test_3_combien_aurai_je_dans_13_semaines():
    financial_client = _financial_client_realiste()
    graph, analysis_llm = _graph(
        financial_client, MagicMock(spec=AccountingClient),
        [
            {"intent": "CASH_FORECAST", "confidence": 0.95},
            {"tools": ["finance.get_cash_forecast"]},
        ],
        {"summary": f"Le solde minimum projete est de {FORECAST_MINIMUM} XOF en semaine {FORECAST_CRITICAL_WEEK}.",
         "analysis": [], "risks": [], "recommendations": [], "missing_data": []},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Combien aurai-je dans 13 semaines ?"))

    response: CfoResponse = resultat["response"]
    faits = {f.name: f.value for f in response.facts}
    assert faits["cash_forecast_minimum"] == FORECAST_MINIMUM
    assert faits["cash_forecast_critical_week"] == Decimal(FORECAST_CRITICAL_WEEK)
    assert "MEDIUM" in analysis_llm.calls[0]["user_text"]


def test_4_quel_est_mon_principal_risque():
    financial_client = _financial_client_realiste()
    graph, _analysis_llm = _graph(
        financial_client, MagicMock(spec=AccountingClient),
        [
            {"intent": "GENERAL_FINANCIAL", "confidence": 0.7},
            {"tools": ["finance.get_cash_forecast", "finance.get_receivables", "finance.get_payables"]},
        ],
        {"summary": "Le principal risque est un creux de tresorerie en semaine 7.",
         "analysis": [], "risks": [f"Creux de tresorerie projete en semaine {FORECAST_CRITICAL_WEEK} (minimum {FORECAST_MINIMUM} XOF)."],
         "recommendations": [], "missing_data": []},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Quel est mon principal risque ?"))

    response: CfoResponse = resultat["response"]
    assert response.risks
    assert str(FORECAST_CRITICAL_WEEK) in response.risks[0]


def test_5_scenario_avec_cout_connu_degrade_honnetement():
    graph, _analysis_llm = _graph(
        _financial_client_realiste(), MagicMock(spec=AccountingClient),
        [
            {"intent": "SCENARIO", "confidence": 0.85},
            {"tools": ["finance.get_cash_position"]},
        ],
        {"summary": "La simulation de recrutement n'est pas encore disponible ; voici la tresorerie actuelle.",
         "analysis": [], "risks": [], "recommendations": [],
         "missing_data": ["scenario simulation not yet available (finance.simulate_scenario does not exist)"]},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Puis-je recruter deux personnes a 400 000 XOF/mois chacune ?"))

    response: CfoResponse = resultat["response"]
    assert any("scenario" in m.lower() for m in response.missing_data)
    assert "cash" in {f.name for f in response.facts}  # les faits reels restants sont quand meme fournis


def test_6_scenario_baisse_des_ventes_degrade_honnetement():
    graph, _analysis_llm = _graph(
        _financial_client_realiste(), MagicMock(spec=AccountingClient),
        [
            {"intent": "SCENARIO", "confidence": 0.8},
            {"tools": []},
        ],
        {"summary": "La simulation d'une baisse de ventes de 10% n'est pas encore disponible.",
         "analysis": [], "risks": [], "recommendations": [],
         "missing_data": ["scenario simulation not yet available (finance.simulate_scenario does not exist)"]},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Que se passe-t-il si mes ventes baissent de 10 % ?"))

    response: CfoResponse = resultat["response"]
    assert response.tools_used == []
    assert response.missing_data


def test_7_pourquoi_cette_facture_a_ete_comptabilisee_sur_ce_compte():
    accounting_client = _accounting_client_realiste()
    document_id = uuid4()
    llm = MockLLMProvider(
        responses=[{"intent": "ACCOUNTING", "confidence": 0.9}, {"tools": ["accounting.get_document"]}]
    )
    analysis_llm = MockLLMProvider(
        responses=[{"summary": "Facture Total Energies de 118 000 XOF TTC (100 000 HT + 18 000 TVA).",
                    "analysis": [], "risks": [], "recommendations": [], "missing_data": []}]
    )
    service = CfoAnalysisService(llm_provider=analysis_llm)
    graph = build_cfo_graph(
        accounting_client=accounting_client, financial_client=MagicMock(spec=FinancialClient),
        analysis_service=service, llm_provider=llm,
    )
    state = nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Pourquoi cette facture a ete comptabilisee sur ce compte ?", document_id=document_id)

    resultat = graph.invoke(state)

    response: CfoResponse = resultat["response"]
    faits = {f.name: f.value for f in response.facts}
    assert faits["document_amount_ttc"] == Decimal(118000)
    accounting_client.get_document_extraction.assert_called_once_with(document_id)


def test_8_donnees_insuffisantes_ne_fabrique_rien():
    financial_client = MagicMock(spec=FinancialClient)
    financial_client.get_working_capital.return_value = WorkingCapital(
        as_of=date(2026, 8, 27), status=WorkingCapitalStatus.NOT_AVAILABLE, missing_components=["inventory"],
    )
    graph, _analysis_llm = _graph(
        financial_client, MagicMock(spec=AccountingClient),
        [{"intent": "WORKING_CAPITAL", "confidence": 0.9}, {"tools": ["finance.get_working_capital"]}],
        {"summary": "Le besoin en fonds de roulement n'est pas calculable : inventaire manquant.",
         "analysis": [], "risks": [], "recommendations": [], "missing_data": ["inventory"]},
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Quel est mon besoin en fonds de roulement ?"))

    response: CfoResponse = resultat["response"]
    assert response.facts == []  # WorkingCapital NOT_AVAILABLE ne produit aucun fact invente
    assert "inventory" in response.missing_data


def test_conversation_multi_tours_reutilise_le_meme_thread_et_refetch_les_chiffres():
    # STEP 11, section 28 : cash -> forecast -> scenario sur le MEME thread_id - le contexte de
    # conversation est conserve (checkpointer) mais chaque tour rappelle reellement les tools,
    # jamais une reponse mise en cache/rejouee du tour precedent.
    financial_client = _financial_client_realiste()
    accounting_client = MagicMock(spec=AccountingClient)
    llm = MockLLMProvider(
        responses=[
            {"intent": "CASH_POSITION", "confidence": 0.9}, {"tools": ["finance.get_cash_position"]},
            {"intent": "CASH_FORECAST", "confidence": 0.9}, {"tools": ["finance.get_cash_forecast"]},
            {"intent": "SCENARIO", "confidence": 0.8}, {"tools": []},
        ]
    )
    analysis_llm = MockLLMProvider(
        responses=[
            {"summary": "Cash actuel : 12.5M XOF.", "analysis": [], "risks": [], "recommendations": [], "missing_data": []},
            {"summary": f"Minimum projete : {FORECAST_MINIMUM} XOF en semaine {FORECAST_CRITICAL_WEEK}.", "analysis": [], "risks": [], "recommendations": [], "missing_data": []},
            {"summary": "Simulation non disponible.", "analysis": [], "risks": [], "recommendations": [],
             "missing_data": ["scenario simulation not yet available"]},
        ]
    )
    service = CfoAnalysisService(llm_provider=analysis_llm)
    graph = build_cfo_graph(
        accounting_client=accounting_client, financial_client=financial_client, analysis_service=service,
        llm_provider=llm, checkpointer=creer_checkpointer_cfo(),
    )
    conversation_id = uuid4()
    company_id = uuid4()

    for question in ("Quel est mon cash ?", "Et dans 13 semaines ?", "Puis-je recruter deux commerciaux ?"):
        config = {"configurable": {"thread_id": str(conversation_id)}}
        resultat = graph.invoke(nouvel_etat_initial(conversation_id, uuid4(), company_id, question), config=config)
        assert resultat["response"] is not None

    assert financial_client.get_cash_position.call_count == 1
    assert financial_client.get_cash_forecast.call_count == 1
    assert len(analysis_llm.calls) == 3
