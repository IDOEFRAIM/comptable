from fastapi import APIRouter, Depends, UploadFile

from app.core.security import require_internal_api_key
from app.schemas.extraction import OcrResult
from app.services.extraction_service import extraire

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


@router.post("/extraction/document", response_model=OcrResult)
async def extraire_document(fichier: UploadFile) -> OcrResult:
    contenu = await fichier.read()
    content_type = fichier.content_type or "application/octet-stream"
    return extraire(contenu, content_type)
