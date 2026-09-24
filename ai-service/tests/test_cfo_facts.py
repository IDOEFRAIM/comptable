"""STEP 11.8 : FinancialFactExtractor transforme les reponses des tools en facts structures -
jamais le LLM ne construit un fact (section 12 du plan)."""

from datetime import date
from decimal import Decimal
from uuid import uuid4

from app.cfo.fact_extractor import FinancialFactExtractor
from app.schemas.accounting import DocumentExtractionSnapshot, TypeDocument
from app.schemas.cfo import FinancialFactDataType
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

_extractor = FinancialFactExtractor()


def test_cash_position_produit_le_total_et_les_facts_par_canal():
    cash = CashPosition(
        date_reference=date(2026, 8, 27), total=Decimal(12500000),
        par_compte=[
            PositionTresorerie(compte_id=uuid4(), compte_numero="521000", compte_intitule="Banque BOA", canal="BANQUE", solde=Decimal(10000000)),
            PositionTresorerie(compte_id=uuid4(), compte_numero="571000", compte_intitule="Caisse", canal="CAISSE", solde=Decimal(1500000)),
            PositionTresorerie(compte_id=uuid4(), compte_numero="522000", compte_intitule="Orange Money", canal="MOBILE_MONEY", solde=Decimal(1000000)),
        ],
    )

    facts = _extractor.extraire("finance.get_cash_position", cash)

    noms = {f.name: f.value for f in facts}
    assert noms["cash"] == Decimal(12500000)
    assert noms["bank_cash"] == Decimal(10000000)
    assert noms["cash_on_hand"] == Decimal(1500000)
    assert noms["mobile_money_cash"] == Decimal(1000000)
    assert all(f.source_tool == "finance.get_cash_position" for f in facts)
    assert all(f.data_type is FinancialFactDataType.ACTUAL for f in facts)


def test_financial_snapshot_produit_revenue_expenses_profit_et_margin():
    snapshot = FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 31),
            cash=Decimal(12500000), receivables=Decimal(21800000), payables=Decimal(9400000),
            revenue=Decimal(35000000), expenses=Decimal(30800000), profit=Decimal(4200000), taxes=Decimal(0),
        ),
        net_margin=Decimal("0.12"),
    )

    facts = _extractor.extraire("finance.get_financial_snapshot", snapshot)

    noms = {f.name: f.value for f in facts}
    assert noms["revenue"] == Decimal(35000000)
    assert noms["expenses"] == Decimal(30800000)
    assert noms["profit"] == Decimal(4200000)
    assert noms["net_margin"] == Decimal("0.12")
    assert all(f.period == "2026-08-01/2026-08-31" for f in facts)


def test_snapshot_sans_net_margin_nemet_pas_de_fact_net_margin():
    snapshot = FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 31),
            cash=Decimal(0), receivables=Decimal(0), payables=Decimal(0),
            revenue=Decimal(0), expenses=Decimal(0), profit=Decimal(0), taxes=Decimal(0),
        ),
        net_margin=None, net_margin_unavailable_reason="insufficient_data",
    )

    facts = _extractor.extraire("finance.get_financial_snapshot", snapshot)

    assert "net_margin" not in {f.name for f in facts}


def test_receivables_produit_total_current_et_overdue():
    receivables = Receivables(
        as_of=date(2026, 8, 27), total=Decimal(21800000), current=Decimal(15000000), overdue=Decimal(6800000),
        aging_0_30=Decimal(15000000), aging_31_60=Decimal(5000000), aging_61_90=Decimal(1000000), aging_plus_90=Decimal(800000),
    )

    facts = _extractor.extraire("finance.get_receivables", receivables)

    noms = {f.name: f.value for f in facts}
    assert noms["receivables_total"] == Decimal(21800000)
    assert noms["receivables_overdue"] == Decimal(6800000)


def test_payables_produit_total_current_et_overdue():
    payables = Payables(
        as_of=date(2026, 8, 27), total=Decimal(9400000), current=Decimal(9400000), overdue=Decimal(0),
        aging_0_30=Decimal(9400000), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )

    facts = _extractor.extraire("finance.get_payables", payables)

    noms = {f.name: f.value for f in facts}
    assert noms["payables_total"] == Decimal(9400000)


def test_working_capital_not_available_ne_produit_aucun_fact_invente():
    wc = WorkingCapital(as_of=date(2026, 8, 27), status=WorkingCapitalStatus.NOT_AVAILABLE, missing_components=["inventory"])

    facts = _extractor.extraire("finance.get_working_capital", wc)

    assert facts == []


def test_working_capital_disponible_produit_working_capital_et_inventory():
    wc = WorkingCapital(
        as_of=date(2026, 8, 27), status=WorkingCapitalStatus.AVAILABLE, working_capital=Decimal(17400000),
        receivables=Decimal(21800000), payables=Decimal(9400000), inventory=Decimal(5000000),
    )

    facts = _extractor.extraire("finance.get_working_capital", wc)

    noms = {f.name: f.value for f in facts}
    assert noms["working_capital"] == Decimal(17400000)
    assert noms["inventory"] == Decimal(5000000)


def _semaine(index: int, closing: Decimal) -> CashForecastWeek:
    return CashForecastWeek(
        week_index=index, week_start=date(2026, 8, 1), week_end=date(2026, 8, 7),
        opening_cash=Decimal(0), inflows=Decimal(0), outflows=Decimal(0), net_cash_flow=Decimal(0),
        closing_cash=closing, known_inflows=Decimal(0), expected_inflows=Decimal(0), predicted_inflows=Decimal(0),
        known_outflows=Decimal(0), expected_outflows=Decimal(0), predicted_outflows=Decimal(0),
    )


def test_cash_forecast_produit_current_closing_minimum_et_semaine_critique_predicted():
    forecast = CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 10, 30), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=Decimal(6000000), confidence=ForecastConfidence.MEDIUM,
        minimum_cash_threshold=Decimal(5000000),
        weeks=[_semaine(i, Decimal(10000000)) for i in range(7)] + [_semaine(7, Decimal(3800000))] + [_semaine(i, Decimal(6000000)) for i in range(8, 13)],
    )

    facts = _extractor.extraire("finance.get_cash_forecast", forecast)

    noms = {f.name: (f.value, f.data_type) for f in facts}
    assert noms["cash_current"] == (Decimal(12500000), FinancialFactDataType.ACTUAL)
    assert noms["cash_forecast_closing"][1] is FinancialFactDataType.PREDICTED
    assert noms["cash_forecast_minimum"] == (Decimal(3800000), FinancialFactDataType.PREDICTED)
    assert noms["cash_forecast_critical_week"] == (Decimal(7), FinancialFactDataType.PREDICTED)


def test_cash_forecast_sans_semaine_sous_le_seuil_nemet_pas_de_fact_semaine_critique():
    forecast = CashForecast(
        as_of=date(2026, 8, 1), period_start=date(2026, 8, 1), period_end=date(2026, 8, 21), currency="XOF",
        opening_cash=Decimal(12500000), closing_cash=Decimal(12000000), confidence=ForecastConfidence.HIGH,
        weeks=[_semaine(i, Decimal(12000000)) for i in range(3)],
    )

    facts = _extractor.extraire("finance.get_cash_forecast", forecast)

    assert "cash_forecast_critical_week" not in {f.name for f in facts}


def test_document_produit_les_montants_ttc_ht_tva():
    document = DocumentExtractionSnapshot(
        document_id=uuid4(), company_id=uuid4(), type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000), confiance=0.95,
    )

    facts = _extractor.extraire("accounting.get_document", document)

    noms = {f.name: f.value for f in facts}
    assert noms["document_amount_ttc"] == Decimal(118000)
    assert noms["document_amount_ht"] == Decimal(100000)
    assert noms["document_amount_tva"] == Decimal(18000)


def test_un_type_de_resultat_inconnu_ne_produit_aucun_fact():
    assert _extractor.extraire("outil.inconnu", {"cle": "valeur"}) == []
