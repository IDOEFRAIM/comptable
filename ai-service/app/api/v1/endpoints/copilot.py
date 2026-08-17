from fastapi import APIRouter, Depends

from app.core.security import require_internal_api_key
from app.schemas.copilot import CopilotExplainRequest, CopilotExplainResponse
from app.services.copilot_service import expliquer

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


@router.post("/copilot/explain", response_model=CopilotExplainResponse)
def expliquer_dossier(requete: CopilotExplainRequest) -> CopilotExplainResponse:
    """Copilot Evidence-First (mission P1) : ne calcule jamais rien - reformule en francais des
    faits deja calcules et verifies cote Core Java. Un seul appel LLM, jamais de boucle d'outils
    (voir app/services/copilot_service.py)."""
    return expliquer(requete)
