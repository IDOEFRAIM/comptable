import json
import logging

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Request, status
from fastapi.responses import PlainTextResponse

from app.core.config import get_settings
from app.core.security import require_internal_api_key, verify_whatsapp_signature
from app.schemas.fraude import TypeDocument
from app.schemas.ingestion import DonneesExtraites, WhatsAppDocumentPayload
from app.schemas.whatsapp import SendTemplateRequest, SendTemplateResponse, WhatsAppWebhookPayload
from app.services import core_client, whatsapp_client
from app.services.extraction_service import extraire
from app.services.fraude_service import analyser_visuellement

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/whatsapp/webhook", response_class=PlainTextResponse)
def verifier_webhook(request: Request) -> PlainTextResponse:
    """Etape de verification Meta lors de l'enregistrement du webhook."""
    mode = request.query_params.get("hub.mode")
    token = request.query_params.get("hub.verify_token")
    challenge = request.query_params.get("hub.challenge", "")

    settings = get_settings()
    if mode == "subscribe" and token == settings.whatsapp_verify_token:
        return PlainTextResponse(challenge)
    raise HTTPException(status.HTTP_403_FORBIDDEN, "Token de verification invalide")


@router.post("/whatsapp/webhook", status_code=status.HTTP_200_OK)
async def recevoir_webhook(
    request: Request,
    background_tasks: BackgroundTasks,
    x_hub_signature_256: str | None = Header(default=None),
) -> dict[str, str]:
    """Repond 200 immediatement (delai impose par Meta) et traite en arriere-plan."""
    payload_brut = await request.body()

    if not verify_whatsapp_signature(payload_brut, x_hub_signature_256):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Signature invalide")

    try:
        payload = WhatsAppWebhookPayload.model_validate(json.loads(payload_brut))
    except (json.JSONDecodeError, ValueError):
        logger.warning("Payload webhook WhatsApp illisible, ignore")
        return {"status": "ignored"}

    background_tasks.add_task(_traiter_payload, payload)
    return {"status": "accepted"}


async def _traiter_payload(payload: WhatsAppWebhookPayload) -> None:
    for entry in payload.entry:
        for change in entry.changes:
            for message in change.value.messages:
                if message.type != "image" or message.image is None:
                    continue
                await _traiter_message_image(message)


async def _traiter_message_image(message) -> None:
    try:
        media = await whatsapp_client.telecharger_media(message.image.id)

        # Extraction VLM (montants, fournisseur, mode de paiement) puis analyse de fraude
        # visuelle sur le meme fichier. Les pieces recues par WhatsApp sont toujours des reçus
        # (RECU) dans ce SaaS : c'est le seul canal externe, jamais une facture PDF structuree.
        resultat = extraire(media.contenu, media.content_type)
        alertes = analyser_visuellement(media.contenu, media.content_type, TypeDocument.RECU)

        payload = WhatsAppDocumentPayload(
            client_phone=message.from_,
            message_id=message.id,
            donnees=DonneesExtraites.depuis_ocr_result(resultat),
            alertes_fraude=alertes,
        )

        await core_client.notifier_document_whatsapp(
            payload=payload,
            nom_fichier=f"whatsapp-{message.id}",
            content_type=media.content_type,
            contenu=media.contenu,
        )
    except Exception:
        logger.exception("Echec du traitement du message WhatsApp %s", message.id)


@router.post(
    "/whatsapp/send-template",
    response_model=SendTemplateResponse,
    dependencies=[Depends(require_internal_api_key)],
)
async def envoyer_template(requete: SendTemplateRequest) -> SendTemplateResponse:
    wamid = await whatsapp_client.envoyer_template(
        requete.numero_destinataire,
        requete.nom_template,
        requete.langue,
        requete.parametres_corps,
    )
    return SendTemplateResponse(wamid=wamid)
