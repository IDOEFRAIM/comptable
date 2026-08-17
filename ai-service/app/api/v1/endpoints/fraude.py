from fastapi import APIRouter, Depends, Form, UploadFile

from app.core.security import require_internal_api_key
from app.schemas.fraude import AlerteFraude, TypeDocument
from app.services.fraude_service import analyser_visuellement

router = APIRouter(dependencies=[Depends(require_internal_api_key)])


@router.post("/fraude/analyse-visuelle", response_model=list[AlerteFraude])
async def analyser(
    fichier: UploadFile, type_document: TypeDocument | None = Form(default=None)
) -> list[AlerteFraude]:
    contenu = await fichier.read()
    content_type = fichier.content_type or "application/octet-stream"
    return analyser_visuellement(contenu, content_type, type_document)
