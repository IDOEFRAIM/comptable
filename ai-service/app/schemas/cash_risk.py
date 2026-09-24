"""Schemas Pydantic pour le workflow "Cash Forecast" (STEP 9 du plan d'implementation, "Cash Flow
Intelligence"). Meme principe que financial_health.py (STEP 8) : Java calcule les faits (le
CashForecast complet, deja recu via MCP), une politique deterministe Python
(app/finance/cash_risk_policy.py) calcule `status`/`critical_week`/`minimum_projected_cash`, et
SEUL le contenu narratif (main_drivers/positive_factors/recommended_actions) est produit par le
LLM - jamais un chiffre recalcule ou une semaine/un statut invente (section 25 du plan : "le LLM
ne peut pas modifier closing_cash, forecast, confidence, severity")."""

from datetime import date
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class CashRiskStatus(str, Enum):
    """4 valeurs (section 26 du plan) - "comme pour Financial Health" mais avec un niveau
    supplementaire HEALTHY (le plan cite explicitement HEALTHY/WATCH/AT_RISK/CRITICAL pour ce
    workflow, contrairement a FinancialHealthStatus - STEP 8 - qui n'en a que 3). Calculee par
    app/finance/cash_risk_policy.py (deterministe), jamais par le LLM."""

    HEALTHY = "HEALTHY"
    WATCH = "WATCH"
    AT_RISK = "AT_RISK"
    CRITICAL = "CRITICAL"


class CashRiskAssessment(BaseModel):
    """Sortie structuree du node analyze_cash_risk (STEP 9.15-9.16). `status`, `critical_week` et
    `minimum_projected_cash` sont deja decides avant l'appel LLM (policy deterministe sur le
    CashForecast deja recu) - le LLM ne fait que justifier/detailler avec du texte grounde dans
    les chiffres et evenements de liquidite recus, jamais recalculer ou contredire ces valeurs."""

    status: CashRiskStatus
    critical_week: int | None = Field(default=None, alias="criticalWeek")
    minimum_projected_cash: Decimal = Field(alias="minimumProjectedCash")
    main_drivers: list[str] = Field(default_factory=list, alias="mainDrivers")
    positive_factors: list[str] = Field(default_factory=list, alias="positiveFactors")
    recommended_actions: list[str] = Field(default_factory=list, alias="recommendedActions")
    data_completeness_notes: list[str] = Field(default_factory=list, alias="dataCompletenessNotes")
    as_of: date = Field(alias="asOf")
    period: str
    currency: str

    model_config = _CAMEL_CASE_CONFIG
