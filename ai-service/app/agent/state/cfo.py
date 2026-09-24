"""CfoState (STEP 11 du plan d'implementation, "AI CFO") : contrat de state du premier agent
conversationnel construit au-dessus de toutes les capabilities existantes - deliberement SEPARE
d'AccountingState/FinancialHealthState/CashForecastState (meme discipline etablie en STEP 8/9 :
"ne pas melanger" des domaines de state differents).

Meme discipline que les states precedents : TypedDict(total=False), chaque node retourne une mise
a jour partielle, aucun reducer personnalise, aucune donnee sensible/brute stockee (pas de secret,
pas de token, pas de document brut inutile, pas de chain-of-thought, pas de donnee financiere
inventee - section 1 du plan STEP 11)."""

from typing import Any, TypedDict
from uuid import UUID

from pydantic import TypeAdapter

from app.schemas.accounting import AccountingProposal, ApprovalResult, PostingResult, WorkflowStatus
from app.schemas.cfo import CfoIntent, CfoResponse, FinancialFact, ToolResultRecord


class CfoState(TypedDict, total=False):
    """State du workflow AI CFO. Tous les champs sont optionnels (`total=False`) : un champ
    absent signifie "le node qui le produit n'a pas encore tourne".

    `company_id` est le verrou de tenant (section 27 du plan) : toujours fourni par le contexte
    applicatif a la creation du state, jamais modifiable par le LLM (le planner ne retourne que
    des noms de tools, jamais des arguments - voir app/cfo/planner.py)."""

    conversation_id: UUID
    workflow_id: UUID
    company_id: UUID

    # Contexte optionnel fourni par l'appelant (jamais devine par le LLM, section 27 - tenant
    # lock : tout argument de tool vient du contexte applicatif, jamais du modele) : un document
    # precis dont parle la conversation (section 18, "pourquoi cette facture...").
    document_id: UUID

    user_message: str

    intent: CfoIntent
    intent_confidence: float

    # Tools selectionnes par le planner mais pas encore tous executes (file d'attente interne au
    # tool execution loop) - distinct de tool_calls (noms deja executes, pour MAX_TOOL_CALLS).
    planned_tools: list[str]
    tool_calls: list[str]
    tool_results: list[ToolResultRecord]

    financial_facts: list[FinancialFact]

    # Reserve pour une future integration Scenario Engine (non construit a ce stade - le CFO
    # degrade explicitement une question SCENARIO en "capability not yet available", jamais un
    # calcul invente) - le champ existe pour ne pas rendre l'ajout futur impossible.
    scenario_result: dict[str, Any] | None

    # Ecriture proposee (STEP 11, section 19, "write action") : jamais construite par le CFO
    # lui-meme (aucune logique de classification/proposition ici) - uniquement transmise par
    # l'appelant (le pipeline AI Accounting existant, STEP 4-7) quand une proposition existe deja
    # et attend une decision de posting.
    pending_proposal: AccountingProposal
    approval_result: ApprovalResult
    posting_result: PostingResult

    analysis: CfoResponse
    response: CfoResponse

    pending_clarification: dict[str, Any]

    status: WorkflowStatus
    errors: list[str]


CFO_STATE_ADAPTER: TypeAdapter = TypeAdapter(CfoState)


def nouvel_etat_initial(
    conversation_id: UUID, workflow_id: UUID, company_id: UUID, user_message: str,
    *, pending_proposal: AccountingProposal | None = None, document_id: UUID | None = None,
) -> CfoState:
    """Etat minimal de depart d'un tour de conversation, avant meme la classification d'intention.
    `pending_proposal`/`document_id` sont optionnels et toujours fournis par l'appelant - le CFO
    ne construit jamais une proposition ni ne devine un document lui-meme."""

    etat: CfoState = {
        "conversation_id": conversation_id, "workflow_id": workflow_id, "company_id": company_id,
        "user_message": user_message, "status": WorkflowStatus.PENDING, "errors": [],
        "tool_calls": [], "tool_results": [], "financial_facts": [],
    }
    if pending_proposal is not None:
        etat["pending_proposal"] = pending_proposal
    if document_id is not None:
        etat["document_id"] = document_id
    return etat
