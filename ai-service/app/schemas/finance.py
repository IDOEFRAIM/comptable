"""Schemas Pydantic pour la Financial Intelligence Layer (STEP 8 du plan d'implementation,
"AI FINANCE"). Miroirs exacts des DTO Java de com.example.comptable.agent.finance/finance/
comptabilite - jamais un second calcul cote Python, ces schemas ne transportent que ce que Java a
deja calcule (AiFinanceQueryService -> CashPositionService/FinancialSnapshotService/
BalanceAgeeService/ArticleRepository, tous deja existants, aucun duplique ici).

Principe directeur (identique a accounting.py) : Python n'additionne jamais de comptes, ne calcule
jamais un revenu/une marge/un solde de tresorerie a partir d'une copie partielle de la base -
chaque champ ci-dessous est une projection 1:1 d'un champ deja renvoye par Java."""

from datetime import date
from decimal import Decimal
from enum import Enum
from uuid import UUID

from pydantic import BaseModel, Field

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class WorkingCapitalStatus(str, Enum):
    """Miroir exact des constantes com.example.comptable.agent.finance.web.dto.WorkingCapitalDto
    (STATUT_DISPONIBLE/STATUT_NON_DISPONIBLE) - jamais un troisieme statut invente ici."""

    AVAILABLE = "AVAILABLE"
    NOT_AVAILABLE = "NOT_AVAILABLE"


class PositionTresorerie(BaseModel):
    """Miroir exact de com.example.comptable.finance.web.dto.PositionTresorerieDto - un compte de
    tresorerie (classe 5) et son solde, deja calcule par Java."""

    compte_id: UUID = Field(alias="compteId")
    compte_numero: str = Field(alias="compteNumero")
    compte_intitule: str = Field(alias="compteIntitule")
    canal: str
    solde: Decimal

    model_config = _CAMEL_CASE_CONFIG


class CashPosition(BaseModel):
    """Miroir exact de com.example.comptable.finance.web.dto.CashPositionDto (capability
    get_cash_position, STEP 8 section 7 du plan). `total` est deja la somme calculee par Java
    (CashPositionService) - jamais recalculee ici a partir de `par_compte`."""

    date_reference: date = Field(alias="dateReference")
    total: Decimal
    par_compte: list[PositionTresorerie] = Field(default_factory=list, alias="parCompte")

    model_config = _CAMEL_CASE_CONFIG


class FinancialSnapshot(BaseModel):
    """Miroir exact de com.example.comptable.finance.web.dto.FinancialSnapshotDto - synthese
    cash/receivables/payables/revenue/expenses/profit/taxes pour une periode, deja calculee par
    Java (FinancialSnapshotService)."""

    periode_debut: date = Field(alias="periodeDebut")
    periode_fin: date = Field(alias="periodeFin")
    cash: Decimal
    receivables: Decimal
    payables: Decimal
    revenue: Decimal
    expenses: Decimal
    profit: Decimal
    taxes: Decimal

    model_config = _CAMEL_CASE_CONFIG


class FinancialSnapshotWithMargins(BaseModel):
    """Miroir exact de com.example.comptable.agent.finance.web.dto.AiFinancialSnapshotDto
    (capabilities get_revenue/get_expenses/get_profit/get_margin, STEP 8 sections 8-9 du plan) -
    un seul appel Java fournit les quatre "capabilities" (section 17 du plan : un endpoint
    snapshot plutot que quatre endpoints).

    `gross_margin` est TOUJOURS None dans cette version (voir la docstring Java de
    AiFinancialSnapshotDto pour la raison exacte : aucun COGS distinct des achats/mouvements de
    stock n'est actuellement fiable) - jamais une approximation trompeuse presentee comme une
    marge brute reelle."""

    snapshot: FinancialSnapshot
    net_margin: Decimal | None = Field(default=None, alias="netMargin")
    net_margin_unavailable_reason: str | None = Field(default=None, alias="netMarginUnavailableReason")
    gross_margin: Decimal | None = Field(default=None, alias="grossMargin")
    gross_margin_unavailable_reason: str | None = Field(default=None, alias="grossMarginUnavailableReason")

    model_config = _CAMEL_CASE_CONFIG


class TopTiers(BaseModel):
    """Miroir exact de com.example.comptable.agent.finance.web.dto.TopTiersDto - un tiers et le
    montant qu'il represente, toujours positif."""

    tiers_id: UUID = Field(alias="tiersId")
    nom: str
    montant: Decimal

    model_config = _CAMEL_CASE_CONFIG


class Receivables(BaseModel):
    """Miroir exact de com.example.comptable.agent.finance.web.dto.ReceivablesDto (capability
    get_receivables, STEP 8 section 10 du plan). `current` = tranche 0-30 jours, `overdue` = somme
    des tranches 31-60/61-90/plus-90 (meme convention que CollectionRiskService cote Java)."""

    as_of: date = Field(alias="asOf")
    total: Decimal
    current: Decimal
    overdue: Decimal
    aging_0_30: Decimal = Field(alias="aging0a30")
    aging_31_60: Decimal = Field(alias="aging31a60")
    aging_61_90: Decimal = Field(alias="aging61a90")
    aging_plus_90: Decimal = Field(alias="agingPlus90")
    top_debtors: list[TopTiers] = Field(default_factory=list, alias="topDebtors")

    model_config = _CAMEL_CASE_CONFIG


class Payables(BaseModel):
    """Miroir exact de com.example.comptable.agent.finance.web.dto.PayablesDto (capability
    get_payables, STEP 8 section 11 du plan). Tous les montants sont positifs (une dette de 9.4M
    s'exprime "9400000", jamais "-9400000")."""

    as_of: date = Field(alias="asOf")
    total: Decimal
    current: Decimal
    overdue: Decimal
    aging_0_30: Decimal = Field(alias="aging0a30")
    aging_31_60: Decimal = Field(alias="aging31a60")
    aging_61_90: Decimal = Field(alias="aging61a90")
    aging_plus_90: Decimal = Field(alias="agingPlus90")
    top_creditors: list[TopTiers] = Field(default_factory=list, alias="topCreditors")

    model_config = _CAMEL_CASE_CONFIG


class WorkingCapital(BaseModel):
    """Miroir exact de com.example.comptable.agent.finance.web.dto.WorkingCapitalDto (capability
    get_working_capital, STEP 8 section 12 du plan) : working_capital = receivables + inventory -
    payables, ou `status=NOT_AVAILABLE` avec `missing_components` rempli si un composant manque -
    jamais un chiffre invente pour combler l'absence."""

    as_of: date = Field(alias="asOf")
    status: WorkingCapitalStatus
    working_capital: Decimal | None = Field(default=None, alias="workingCapital")
    receivables: Decimal | None = None
    payables: Decimal | None = None
    inventory: Decimal | None = None
    missing_components: list[str] = Field(default_factory=list, alias="missingComponents")

    model_config = _CAMEL_CASE_CONFIG


class ForecastConfidence(str, Enum):
    """Miroir exact de com.example.comptable.finance.forecast.domain.ForecastConfidence (STEP 9,
    "13-Week Cash Forecast") - deterministe, calculee par CashForecastService a partir de la
    profondeur d'historique disponible, jamais demandee au LLM."""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class LiquidityEventType(str, Enum):
    """Miroir exact de com.example.comptable.finance.forecast.domain.LiquidityEventType."""

    CASH_BELOW_THRESHOLD = "CASH_BELOW_THRESHOLD"
    NEGATIVE_CASH = "NEGATIVE_CASH"
    LIQUIDITY_GAP = "LIQUIDITY_GAP"
    LARGE_UPCOMING_OUTFLOW = "LARGE_UPCOMING_OUTFLOW"
    RECEIVABLE_RISK = "RECEIVABLE_RISK"


class LiquiditySeverity(str, Enum):
    """Miroir exact de com.example.comptable.finance.forecast.domain.LiquiditySeverity."""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class CashForecastWeek(BaseModel):
    """Miroir exact de com.example.comptable.finance.forecast.web.dto.CashForecastWeekDto (STEP 9,
    section 5 du plan). Invariants garantis par Java, jamais recalcules cote Python :
    closing_cash == opening_cash + net_cash_flow, et opening_cash(semaine N+1) ==
    closing_cash(semaine N)."""

    week_index: int = Field(alias="weekIndex")
    week_start: date = Field(alias="weekStart")
    week_end: date = Field(alias="weekEnd")
    opening_cash: Decimal = Field(alias="openingCash")
    inflows: Decimal
    outflows: Decimal
    net_cash_flow: Decimal = Field(alias="netCashFlow")
    closing_cash: Decimal = Field(alias="closingCash")
    known_inflows: Decimal = Field(alias="knownInflows")
    expected_inflows: Decimal = Field(alias="expectedInflows")
    predicted_inflows: Decimal = Field(alias="predictedInflows")
    known_outflows: Decimal = Field(alias="knownOutflows")
    expected_outflows: Decimal = Field(alias="expectedOutflows")
    predicted_outflows: Decimal = Field(alias="predictedOutflows")

    model_config = _CAMEL_CASE_CONFIG


class LiquidityEvent(BaseModel):
    """Miroir exact de com.example.comptable.finance.forecast.web.dto.LiquidityEventDto (STEP 9,
    section 20 du plan) - toujours calcule deterministiquement cote Java, jamais par le LLM."""

    type: LiquidityEventType
    severity: LiquiditySeverity
    week_index: int = Field(alias="weekIndex")
    week_start: date = Field(alias="weekStart")
    amount: Decimal
    reason: str
    source: str

    model_config = _CAMEL_CASE_CONFIG


class CashForecast(BaseModel):
    """Miroir exact de com.example.comptable.finance.forecast.web.dto.CashForecastDto (capability
    get_cash_forecast, STEP 9 du plan - "13-Week Cash Forecast"). Racine du contrat que Python/MCP/
    LangGraph consomment sans jamais recalculer quoi que ce soit (section 6 du plan : "Python ne
    doit pas relire la base, recalculer les historiques, calculer le cash closing")."""

    as_of: date = Field(alias="asOf")
    period_start: date = Field(alias="periodStart")
    period_end: date = Field(alias="periodEnd")
    currency: str
    opening_cash: Decimal = Field(alias="openingCash")
    closing_cash: Decimal = Field(alias="closingCash")
    minimum_cash_threshold: Decimal | None = Field(default=None, alias="minimumCashThreshold")
    confidence: ForecastConfidence
    data_quality_warnings: list[str] = Field(default_factory=list, alias="dataQualityWarnings")
    limitations: list[str] = Field(default_factory=list)
    weeks: list[CashForecastWeek] = Field(default_factory=list)
    liquidity_events: list[LiquidityEvent] = Field(default_factory=list, alias="liquidityEvents")

    model_config = _CAMEL_CASE_CONFIG
