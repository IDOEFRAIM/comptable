"""FinancialHealthState (STEP 8.8 du plan d'implementation) : contrat de state dedie au workflow
"Financial Health Check" - deliberement SEPARE d'AccountingState (section 25 du plan : "ne pas
surcharger AccountingState avec des dizaines de champs CFO qui n'ont rien a voir avec le workflow
documentaire" - les deux domaines n'ont aucun champ en commun au-dela de workflow_id/company_id).

Meme discipline que AccountingState (STEP 2) : TypedDict(total=False), chaque node retourne une
mise a jour partielle, aucun reducer personnalise, aucune donnee sensible/brute stockee (voir
docstring d'AccountingState pour le detail des exclusions, identiques ici)."""

from datetime import date
from typing import TypedDict
from uuid import UUID

from pydantic import TypeAdapter

from app.schemas.accounting import WorkflowStatus
from app.schemas.finance import CashPosition, FinancialSnapshotWithMargins, Payables, Receivables
from app.schemas.financial_health import FinancialHealthAssessment


class FinancialHealthState(TypedDict, total=False):
    """State du workflow Financial Health Check. Tous les champs sont optionnels (`total=False`) :
    un champ absent signifie "le node qui le produit n'a pas encore tourne"."""

    # Identite/parametres du workflow - presents des la creation (voir nouvel_etat_initial).
    workflow_id: UUID
    company_id: UUID
    period_start: date
    period_end: date

    # Faits deja calcules par Java, recus via MCP - jamais recalcules cote Python.
    financial_snapshot: FinancialSnapshotWithMargins
    cash_position: CashPosition
    receivables: Receivables
    payables: Payables

    # Interpretation finale (statut deterministe + narratif LLM, voir app/finance/health_policy.py
    # et app/finance/health_assessment_service.py).
    assessment: FinancialHealthAssessment

    status: WorkflowStatus
    errors: list[str]


FINANCIAL_HEALTH_STATE_ADAPTER: TypeAdapter = TypeAdapter(FinancialHealthState)


def nouvel_etat_initial(workflow_id: UUID, company_id: UUID, period_start: date, period_end: date) -> FinancialHealthState:
    """Etat minimal de depart d'un workflow, avant meme le premier appel MCP."""

    return FinancialHealthState(
        workflow_id=workflow_id, company_id=company_id, period_start=period_start, period_end=period_end,
        status=WorkflowStatus.PENDING, errors=[],
    )
