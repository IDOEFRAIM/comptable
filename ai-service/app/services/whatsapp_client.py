import httpx

from app.core.config import get_settings


class MediaTelecharge:
    def __init__(self, contenu: bytes, content_type: str) -> None:
        self.contenu = contenu
        self.content_type = content_type


async def telecharger_media(media_id: str) -> MediaTelecharge:
    """Reproduit WhatsAppMediaDownloader.telecharger : 2 appels Graph API,
    (1) metadonnees (url ephemere signee + mime_type), (2) telechargement du binaire."""
    settings = get_settings()
    headers = {"Authorization": f"Bearer {settings.whatsapp_access_token}"}
    base = f"{settings.whatsapp_graph_api_base_url}/{settings.whatsapp_graph_api_version}"

    async with httpx.AsyncClient(timeout=30) as client:
        meta_response = await client.get(f"{base}/{media_id}", headers=headers)
        meta_response.raise_for_status()
        metadata = meta_response.json()

        media_response = await client.get(metadata["url"], headers=headers)
        media_response.raise_for_status()

    return MediaTelecharge(contenu=media_response.content, content_type=metadata["mime_type"])


async def envoyer_template(
    numero_destinataire: str, nom_template: str, langue: str, parametres_corps: list[str]
) -> str:
    """Reproduit WhatsAppCloudApiClient.envoyerTemplate. Retourne le wamid Meta."""
    settings = get_settings()
    base = f"{settings.whatsapp_graph_api_base_url}/{settings.whatsapp_graph_api_version}"
    url = f"{base}/{settings.whatsapp_phone_number_id}/messages"
    headers = {"Authorization": f"Bearer {settings.whatsapp_access_token}"}
    body = {
        "messaging_product": "whatsapp",
        "to": numero_destinataire,
        "type": "template",
        "template": {
            "name": nom_template,
            "language": {"code": langue},
            "components": [
                {
                    "type": "body",
                    "parameters": [{"type": "text", "text": p} for p in parametres_corps],
                }
            ],
        },
    }

    async with httpx.AsyncClient(timeout=15) as client:
        response = await client.post(url, headers=headers, json=body)
        response.raise_for_status()
        return response.json()["messages"][0]["id"]
