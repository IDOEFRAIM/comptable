import hashlib
import hmac

from fastapi import Header, HTTPException, status

from app.core.config import get_settings


def require_internal_api_key(x_internal_api_key: str = Header(...)) -> None:
    settings = get_settings()
    if not hmac.compare_digest(x_internal_api_key, settings.internal_api_key):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Cle API interne invalide")


def verify_whatsapp_signature(payload: bytes, signature_header: str | None) -> bool:
    """Verifie X-Hub-Signature-256 envoye par Meta sur le webhook WhatsApp."""
    settings = get_settings()
    if not signature_header or not settings.whatsapp_app_secret:
        return False
    expected = "sha256=" + hmac.new(
        settings.whatsapp_app_secret.encode(), payload, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature_header)
