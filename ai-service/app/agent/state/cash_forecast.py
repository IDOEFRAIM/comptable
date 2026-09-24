"""CashForecastState (STEP 9 du plan d'implementation, "Cash Flow Intelligence") : contrat de
state dedie au workflow "Cash Forecast" - deliberement SEPARE de FinancialHealthState (STEP 8) et
d'AccountingState (interdiction explicite section 39 du plan : "ne pas melanger Financial Health
et Cash Forecast dans un seul state"). Les deux domaines n'ont aucun champ en commun au-dela de
workflow_id/company_id.

Meme discipline que FinancialHealthState/AccountingState : TypedDict(total=False), chaque node
retourne une mise a jour partielle, aucun reducer personnalise, aucune donnee sensible/brute
stockee."""

from datetime import date
from decimal import Decimal
from typing import TypedDict
from uuid import UUID

from pydantic import TypeAdapter

from app.schemas.accounting import WorkflowStatus
from app.schemas.cash_risk import CashRiskAssessment
from app.schemas.finance import CashForecast


class CashForecastState(TypedDict, total=False):
    """State du workflow Cash Forecast. Tous les champs sont optionnels (`total=False`) : un champ
    absent signifie "le node qui le produit n'a pas encore tourne"."""

    # Identite/parametres du workflow - presents des la creation (voir nouvel_etat_initial).
    workflow_id: UUID
    company_id: UUID
    start_date: date
    weeks: int
    minimum_cash_threshold: Decimal

    # Fait deja calcule par Java, recu via MCP - jamais recalcule cote Python.
    forecast: CashForecast

    # Interpretation finale (statut deterministe + narratif LLM, voir
    # app/finance/cash_risk_policy.py et app/finance/cash_risk_assessment_service.py).
    risk_assessment: CashRiskAssessment

    status: WorkflowStatus
    errors: list[str]


CASH_FORECAST_STATE_ADAPTER: TypeAdapter = TypeAdapter(CashForecastState)


def nouvel_etat_initial(
    workflow_id: UUID, company_id: UUID, start_date: date | None = None,
    weeks: int | None = None, minimum_cash_threshold: Decimal | None = None,
) -> CashForecastState:
    """Etat minimal de depart d'un workflow, avant meme le premier appel MCP."""

    etat: CashForecastState = {
        "workflow_id": workflow_id, "company_id": company_id,
        "status": WorkflowStatus.PENDING, "errors": [],
    }
    if start_date is not None:
        etat["start_date"] = start_date
    if weeks is not None:
        etat["weeks"] = weeks
    if minimum_cash_threshold is not None:
        etat["minimum_cash_threshold"] = minimum_cash_threshold
    return etat
