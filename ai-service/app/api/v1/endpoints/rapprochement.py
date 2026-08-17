from fastapi import APIRouter, Depends

from app.core.security import require_internal_api_key
from app.schemas.rapprochement import AppariementSemantique, RapprochementSemantiqueRequest
from app.services.rapprochement_service import proposer_appariements

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


@router.post("/rapprochement/semantique", response_model=list[AppariementSemantique])
def rapprocher(requete: RapprochementSemantiqueRequest) -> list[AppariementSemantique]:
    return proposer_appariements(requete.lignes_non_rapprochees, requete.candidats)
