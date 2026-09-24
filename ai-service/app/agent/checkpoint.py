"""Durcissement du checkpointing LangGraph - AI Accounting (STEP 7.1), Financial Health Check
(STEP 8.8), Cash Forecast (STEP 9.14) et AI CFO (STEP 11), les quatre workflows LangGraph de ce
service, partagent ce module plutot que d'en dupliquer un par graph.

Constat (reproduit et confirme empiriquement depuis STEP 6) : le JsonPlusSerializer par defaut de
langgraph-checkpoint accepte en deserialisation n'importe quel type Python non enregistre, avec un
simple avertissement une fois par type ("Deserializing unregistered type ... This will be blocked
in a future version" - LANGGRAPH_STRICT_MSGPACK). Chaque enum/modele Pydantic de
app/schemas/accounting.py transporte par AccountingState declenche cet avertissement des qu'un
InMemorySaver "nu" (InMemorySaver()) checkpointe le state.

Solution retenue - une primitive officielle LangGraph, jamais un serializer maison (interdit par le
plan, priorite "aucun nouveau moteur/framework") : construire un JsonPlusSerializer avec un
allowlist explicite (`allowed_msgpack_modules`) couvrant exactement les types reellement transportes
par AccountingState. Deliberement PAS `LANGGRAPH_STRICT_MSGPACK=true` (variable d'environnement
globale au processus, hors du controle de ce module, qui bloquerait aussi tout type legitime non
encore ajoute ici) ni `allowed_msgpack_modules=True` (revient au comportement permissif actuel).

Tout appelant (tests, futur endpoint API qui reprendrait ce workflow) doit obtenir son checkpointer
via `creer_checkpointer_accounting()` ci-dessous plutot que d'instancier `InMemorySaver()` nu - seul
moyen de ne jamais reintroduire silencieusement l'avertissement.
"""

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer

from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountingTreatment,
    AccountPurpose,
    AccountResolutionSource,
    AccountResolutionStatus,
    ApprovalResult,
    DocumentExtractionSnapshot,
    NormalizedAccountingDocument,
    PostingResult,
    ProposedAccountingLine,
    ResolvedAccountInfo,
    StatutOperationProposal,
    SuggestedAccount,
    TaxTreatment,
    TypeDocument,
    ValidationResult,
    WorkflowStatus,
)
from app.schemas.cash_risk import CashRiskAssessment, CashRiskStatus
from app.schemas.cfo import (
    CfoIntent,
    CfoRecommendation,
    CfoResponse,
    FinancialFact,
    FinancialFactDataType,
    ToolResultRecord,
)
from app.schemas.finance import (
    CashForecast,
    CashForecastWeek,
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    ForecastConfidence,
    LiquidityEvent,
    LiquidityEventType,
    LiquiditySeverity,
    Payables,
    PositionTresorerie,
    Receivables,
    TopTiers,
    WorkingCapital,
    WorkingCapitalStatus,
)
from app.schemas.financial_health import FinancialHealthAssessment, FinancialHealthStatus

_TYPES_ACCOUNTING_STATE: tuple[type, ...] = (
    # Enums transportes directement ou imbriques dans un modele ci-dessous.
    AccountPurpose,
    TypeDocument,
    AccountingOperationType,
    TaxTreatment,
    AccountResolutionStatus,
    AccountResolutionSource,
    StatutOperationProposal,
    WorkflowStatus,
    AccountingNatureLabel,
    # Modeles Pydantic transportes directement ou imbriques par un champ d'AccountingState -
    # ne jamais en oublier un nouveau sans l'ajouter ici (sinon l'avertissement reapparait).
    ApprovalResult,
    DocumentExtractionSnapshot,
    NormalizedAccountingDocument,
    SuggestedAccount,
    AccountingClassification,
    AccountingTreatment,
    ProposedAccountingLine,
    AccountingProposal,
    ResolvedAccountInfo,
    ValidationResult,
    PostingResult,
)

_TYPES_FINANCIAL_HEALTH_STATE: tuple[type, ...] = (
    # Enums.
    WorkingCapitalStatus,
    FinancialHealthStatus,
    # Modeles Pydantic transportes directement ou imbriques par un champ de FinancialHealthState
    # (STEP 8.8) - ne jamais en oublier un nouveau sans l'ajouter ici.
    PositionTresorerie,
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    TopTiers,
    Receivables,
    Payables,
    WorkingCapital,
    FinancialHealthAssessment,
)


def creer_serializer_accounting() -> JsonPlusSerializer:
    """JsonPlusSerializer (primitive officielle langgraph-checkpoint) avec l'allowlist explicite
    ci-dessus - jamais le mode permissif par defaut, jamais un serializer maison."""

    return JsonPlusSerializer(allowed_msgpack_modules=list(_TYPES_ACCOUNTING_STATE))


def creer_checkpointer_accounting() -> InMemorySaver:
    """Point d'entree unique pour un checkpointer AI Accounting correctement durci. `InMemorySaver`
    reste approprie ici (tests/dev, comme documente par LangGraph lui-meme) - un checkpointer
    persistant (Postgres) est hors perimetre de STEP 7, non introduit ici."""

    return InMemorySaver(serde=creer_serializer_accounting())


def creer_serializer_financial_health() -> JsonPlusSerializer:
    """Meme principe que creer_serializer_accounting, pour FinancialHealthState (STEP 8.8)."""

    return JsonPlusSerializer(allowed_msgpack_modules=list(_TYPES_FINANCIAL_HEALTH_STATE))


def creer_checkpointer_financial_health() -> InMemorySaver:
    """Point d'entree unique pour un checkpointer Financial Health correctement durci."""

    return InMemorySaver(serde=creer_serializer_financial_health())


_TYPES_CASH_FORECAST_STATE: tuple[type, ...] = (
    # Enums.
    ForecastConfidence,
    LiquidityEventType,
    LiquiditySeverity,
    CashRiskStatus,
    # Modeles Pydantic transportes directement ou imbriques par un champ de CashForecastState
    # (STEP 9.14) - ne jamais en oublier un nouveau sans l'ajouter ici.
    CashForecastWeek,
    LiquidityEvent,
    CashForecast,
    CashRiskAssessment,
)


def creer_serializer_cash_forecast() -> JsonPlusSerializer:
    """Meme principe que creer_serializer_accounting, pour CashForecastState (STEP 9.14)."""

    return JsonPlusSerializer(allowed_msgpack_modules=list(_TYPES_CASH_FORECAST_STATE))


def creer_checkpointer_cash_forecast() -> InMemorySaver:
    """Point d'entree unique pour un checkpointer Cash Forecast correctement durci."""

    return InMemorySaver(serde=creer_serializer_cash_forecast())


_TYPES_CFO_STATE: tuple[type, ...] = (
    # Enums propres au CFO.
    CfoIntent,
    FinancialFactDataType,
    # CfoState peut transporter une AccountingProposal complete (pending_proposal) et son resultat
    # de posting/validation (STEP 7) - memes types que _TYPES_ACCOUNTING_STATE, repetes ici
    # volontairement : ce module reste un allowlist par graph, jamais une union implicite qui
    # romprait si un graph evolue independamment d'un autre.
    AccountPurpose,
    TaxTreatment,
    AccountingOperationType,
    AccountingNatureLabel,
    WorkflowStatus,
    SuggestedAccount,
    AccountingClassification,
    AccountingTreatment,
    ProposedAccountingLine,
    AccountingProposal,
    ApprovalResult,
    ValidationResult,
    PostingResult,
    # Modeles Pydantic propres au CFO (STEP 11) - ne jamais en oublier un nouveau sans l'ajouter ici.
    FinancialFact,
    ToolResultRecord,
    CfoRecommendation,
    CfoResponse,
)


def creer_serializer_cfo() -> JsonPlusSerializer:
    """Meme principe que creer_serializer_accounting, pour CfoState (STEP 11)."""

    return JsonPlusSerializer(allowed_msgpack_modules=list(_TYPES_CFO_STATE))


def creer_checkpointer_cfo() -> InMemorySaver:
    """Point d'entree unique pour un checkpointer AI CFO correctement durci."""

    return InMemorySaver(serde=creer_serializer_cfo())
