from fastapi import APIRouter, Depends

from app.core.security import require_internal_api_key
from app.schemas.matching import MatchingRequest, MatchingResponse
from app.services.matching_engine import analyze

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


@router.post("/matching/analyze", response_model=MatchingResponse)
def analyser_correspondance(requete: MatchingRequest) -> MatchingResponse:
    """Universal Matching Engine : determine les correspondances probables entre un evenement
    source (transaction bancaire/Mobile Money, ecriture...) et une liste de candidats
    multi-source (ecritures, factures, documents, tiers, paiements, autres transactions).
    Une seule passe deterministe + LLM (jamais de boucle d'outils) - voir
    app/services/matching_engine.py pour le pipeline complet."""
    return analyze(requete)
