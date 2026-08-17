import httpx

from app.core.config import get_settings
from app.schemas.ingestion import WhatsAppDocumentPayload


async def notifier_document_whatsapp(
    *,
    payload: WhatsAppDocumentPayload,
    nom_fichier: str,
    content_type: str,
    contenu: bytes,
) -> None:
    """Pousse une piece ingeree par WhatsApp vers le coeur Spring Boot, extraction VLM et
    analyse de fraude visuelle deja calculees.

    C'est le coeur (pas ce microservice) qui possede la base de donnees : resolution du
    telephone -> entreprise, idempotence sur message_id (contrainte unique), creation du
    Document, fusion avec les alertes de fraude deterministes et demarrage du pipeline
    agentique. Ce microservice ne fait que l'integration Meta + l'IA, jamais de persistance.

    Envoie `metadata` comme une partie multipart a part entiere avec Content-Type:
    application/json (via `files=`, pas `data=`) : c'est ce qui permet au controleur Spring de
    la binder directement en `@RequestPart @Valid WhatsAppDocumentPayloadDTO metadata` (Jackson
    ne convertit une part que si son Content-Type est application/json ; une part `data=`
    classique arrive en text/plain et ne serait pas convertie automatiquement).
    """
    settings = get_settings()
    url = f"{settings.core_service_base_url}/api/v1/internal/whatsapp/documents"
    headers = {"X-Internal-Api-Key": settings.core_service_api_key}
    files = {
        "fichier": (nom_fichier, contenu, content_type),
        "metadata": (None, payload.model_dump_json(by_alias=True), "application/json"),
    }

    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(url, headers=headers, files=files)
        response.raise_for_status()
