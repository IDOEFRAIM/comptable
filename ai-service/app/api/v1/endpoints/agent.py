from fastapi import APIRouter, Depends, Form, UploadFile

from app.core.security import require_internal_api_key
from app.schemas.agent import AnalyseAgentResponse
from app.services.agent_orchestrator import analyser_piece_autonome

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


@router.post("/agent/analyser-piece", response_model=AnalyseAgentResponse)
async def analyser_piece(fichier: UploadFile, entreprise_id: str = Form(...)) -> AnalyseAgentResponse:
    """Agent Comptable Autonome : boucle de raisonnement reelle avec appels d'outils
    (verifier_doublon_montant, consulter_historique_fournisseur, demander_clarification_client)
    plutot qu'un simple appel structure. Necessite entreprise_id (contrairement a
    extraction/document et fraude/analyse-visuelle, stateless) car les outils interrogent la
    base de donnees de cette entreprise cote Spring Boot."""
    contenu = await fichier.read()
    content_type = fichier.content_type or "application/octet-stream"
    return analyser_piece_autonome(entreprise_id, contenu, content_type)
