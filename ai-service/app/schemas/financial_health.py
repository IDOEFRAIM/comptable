"""Schemas Pydantic pour le workflow "Financial Health Check" (STEP 8, sections 22-26 du plan
d'implementation) - le premier vrai "agent financier", construit au-dessus de la Financial
Intelligence Layer (app/schemas/finance.py, app/mcp/finance_*.py).

Principe directeur (identique a accounting.py) : le Java calcule les faits (cash/revenue/expenses/
profit/receivables/payables, deja recus via MCP), une politique deterministe Python
(app/finance/health_policy.py) calcule `status`, et SEUL le contenu narratif (signals/risks/
positive_factors/priority_actions) est produit par le LLM, contraint a citer uniquement les
chiffres recus - jamais un ratio invente (DSO, cash runway, croissance) qui n'a pas ete fourni
(section 28 du plan, "test anti-hallucination")."""

from datetime import date
from enum import Enum

from pydantic import BaseModel, Field

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class FinancialHealthStatus(str, Enum):
    """Volontairement 3 valeurs seulement (section 24 du plan : "je prefere initialement WATCH/
    AT_RISK/CRITICAL") - jamais un score numerique 0-100 arbitraire. Calculee par
    app/finance/health_policy.py (deterministe), jamais par le LLM."""

    WATCH = "WATCH"
    AT_RISK = "AT_RISK"
    CRITICAL = "CRITICAL"


class FinancialHealthAssessment(BaseModel):
    """Sortie structuree du node analyze_financial_health (STEP 8.9). `status` est deja decide
    avant l'appel LLM (policy deterministe) - le LLM ne fait que justifier/detailler avec du texte
    grounde dans les chiffres recus, jamais recalculer ou contredire `status`."""

    status: FinancialHealthStatus
    signals: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    positive_factors: list[str] = Field(default_factory=list)
    priority_actions: list[str] = Field(default_factory=list)
    data_completeness_notes: list[str] = Field(default_factory=list, alias="dataCompletenessNotes")
    as_of: date = Field(alias="asOf")
    period: str
    currency: str

    model_config = _CAMEL_CASE_CONFIG
