"""Schemas Pydantic pour le workflow "AI CFO" (STEP 11 du plan d'implementation) - le premier
agent conversationnel construit au-dessus de TOUTES les capabilities deja existantes (AI
Accounting, Financial Intelligence Layer, Cash Forecast). Ce module ne recalcule jamais rien :
chaque schema ici transporte soit une provenance explicite (FinancialFact.source_tool), soit une
interpretation strictement produite par le LLM a partir de facts deja recus (jamais l'inverse).

Principe directeur (section 2 du plan STEP 11) : le LLM comprend la question, decide quels tools
appeler, interprete les resultats, genere la reponse - il n'invente jamais un chiffre, ne calcule
jamais une metrique deja disponible via un tool, n'accede jamais a PostgreSQL, ne poste jamais
directement."""

from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class CfoIntent(str, Enum):
    """Intentions reconnues (STEP 11, section 7 du plan) - liste fermee, jamais une categorie
    inventee par le LLM (contrainte via structured output, voir app/cfo/prompts/intent.py)."""

    FINANCIAL_HEALTH = "FINANCIAL_HEALTH"
    CASH_POSITION = "CASH_POSITION"
    CASH_FORECAST = "CASH_FORECAST"
    RECEIVABLES = "RECEIVABLES"
    PAYABLES = "PAYABLES"
    PROFITABILITY = "PROFITABILITY"
    WORKING_CAPITAL = "WORKING_CAPITAL"
    SCENARIO = "SCENARIO"
    ACCOUNTING = "ACCOUNTING"
    GENERAL_FINANCIAL = "GENERAL_FINANCIAL"


class ToolAccessClass(str, Enum):
    """Classification d'acces d'un tool MCP (STEP 11, section 6 du plan) - determine la policy
    (app/agent/policies/cfo_tool_policy.py), jamais laissee au LLM."""

    READ = "READ"
    SIMULATION = "SIMULATION"
    WRITE = "WRITE"


class FinancialFactDataType(str, Enum):
    """Provenance temporelle d'un fait financier (STEP 11, section 23 du plan) - chaque fact doit
    distinguer explicitement s'il est reel, attendu, previsionnel ou hypothetique (scenario)."""

    ACTUAL = "ACTUAL"
    EXPECTED = "EXPECTED"
    PREDICTED = "PREDICTED"
    SCENARIO = "SCENARIO"


class FinancialFact(BaseModel):
    """Un fait financier unique, toujours trace jusqu'a son tool d'origine (STEP 11, section 2 du
    plan). Jamais construit par le LLM (app/cfo/fact_extractor.py uniquement) - jamais un fact
    sans source_tool."""

    name: str
    value: Decimal
    currency: str
    period: str | None = None
    as_of: date | None = None
    source_tool: str = Field(alias="sourceTool")
    data_type: FinancialFactDataType = Field(alias="dataType")

    model_config = _CAMEL_CASE_CONFIG


class ToolResultRecord(BaseModel):
    """Un appel de tool deja execute dans ce workflow (STEP 11, section 11 du plan -
    "tool result registry") : permet au CFO de construire ses facts sans relancer le meme tool
    inutilement dans le meme workflow. `result` est le contenu structure deja serialise (dict),
    jamais du texte libre."""

    tool_name: str = Field(alias="toolName")
    arguments: dict[str, Any] = Field(default_factory=dict)
    result: dict[str, Any] | None = None
    error: str | None = None
    timestamp: datetime

    model_config = _CAMEL_CASE_CONFIG


class CfoRecommendation(BaseModel):
    """Une recommandation, toujours groundee dans des facts deja recus (STEP 11, section 21 du
    plan) - `supporting_facts` reference les noms de FinancialFact utilises, jamais un contenu
    invente sans justification tracable."""

    priority: str
    action: str
    reason: str
    expected_impact: str | None = Field(default=None, alias="expectedImpact")
    confidence: float | None = None
    supporting_facts: list[str] = Field(default_factory=list, alias="supportingFacts")

    model_config = _CAMEL_CASE_CONFIG


class CfoResponse(BaseModel):
    """Sortie structuree finale du CFO (STEP 11, section 3 du plan) - contrat interne obligatoire,
    jamais un texte libre non structure. `facts`/`tools_used` sont deja connus avant le dernier
    appel LLM (jamais produits par lui) ; seuls summary/analysis/risks/recommendations/
    missing_data sont narratifs (LLM), toujours grounded dans `facts`."""

    summary: str
    facts: list[FinancialFact] = Field(default_factory=list)
    analysis: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    recommendations: list[CfoRecommendation] = Field(default_factory=list)
    missing_data: list[str] = Field(default_factory=list, alias="missingData")
    tools_used: list[str] = Field(default_factory=list, alias="toolsUsed")

    model_config = _CAMEL_CASE_CONFIG
