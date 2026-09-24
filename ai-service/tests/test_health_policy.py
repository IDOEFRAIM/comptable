from decimal import Decimal

from app.finance.health_policy import determiner_statut
from app.schemas.financial_health import FinancialHealthStatus


def test_scenario_du_plan_section_23_donne_watch():
    # Revenue 35M, expenses 30.8M, profit 4.2M, cash 12.5M, receivables 21.8M, payables 9.4M.
    statut = determiner_statut(
        profit=Decimal(4200000), cash=Decimal(12500000),
        receivables=Decimal(21800000), payables=Decimal(9400000),
    )
    assert statut is FinancialHealthStatus.WATCH


def test_perte_operationnelle_est_toujours_critical():
    statut = determiner_statut(
        profit=Decimal(-1000000), cash=Decimal(50000000),
        receivables=Decimal(0), payables=Decimal(0),
    )
    assert statut is FinancialHealthStatus.CRITICAL


def test_tresorerie_nulle_est_critical_meme_avec_profit_positif():
    statut = determiner_statut(
        profit=Decimal(1000000), cash=Decimal(0),
        receivables=Decimal(5000000), payables=Decimal(1000000),
    )
    assert statut is FinancialHealthStatus.CRITICAL


def test_cash_insuffisant_pour_couvrir_les_dettes_est_at_risk():
    statut = determiner_statut(
        profit=Decimal(500000), cash=Decimal(2000000),
        receivables=Decimal(1000000), payables=Decimal(5000000),
    )
    assert statut is FinancialHealthStatus.AT_RISK


def test_creances_tres_superieures_au_cash_est_at_risk():
    statut = determiner_statut(
        profit=Decimal(500000), cash=Decimal(5000000),
        receivables=Decimal(15000000), payables=Decimal(1000000),
    )
    assert statut is FinancialHealthStatus.AT_RISK


def test_situation_saine_reste_watch():
    statut = determiner_statut(
        profit=Decimal(3000000), cash=Decimal(20000000),
        receivables=Decimal(5000000), payables=Decimal(2000000),
    )
    assert statut is FinancialHealthStatus.WATCH
