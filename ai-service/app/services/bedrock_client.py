import base64
import logging
import time
from typing import Any

import boto3
from botocore.config import Config

from app.core.config import get_settings

_client: Any = None

logger = logging.getLogger(__name__)

# media types vision acceptes par l'API Converse de Bedrock
_FORMATS_IMAGE = {"image/jpeg": "jpeg", "image/png": "png", "image/gif": "gif", "image/webp": "webp"}


def get_client() -> Any:
    """Client bedrock-runtime. Aucune cle dans le code ni dans .env : boto3 prend les identifiants du
    role IAM attache a l'instance EC2 (ou, en local, des variables AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY /
    AWS_PROFILE)."""
    global _client
    if _client is None:
        reglages = get_settings()
        _client = boto3.client(
            "bedrock-runtime",
            config=Config(
                region_name=reglages.bedrock_region,
                retries={"max_attempts": 3, "mode": "standard"},
                connect_timeout=10,
                read_timeout=60,
            ),
        )
    return _client


def call_structured(
    *,
    system_prompt: str,
    user_text: str,
    tool_name: str,
    tool_description: str,
    input_schema: dict[str, Any],
    image_b64: str | None = None,
    image_media_type: str | None = None,
) -> dict[str, Any]:
    """Meme contrat que groq_client.call_structured : force l'usage d'un tool unique (API Converse de
    Amazon Bedrock) pour obtenir une reponse structuree fiable, jamais de parsing de texte libre."""
    settings = get_settings()
    if not settings.bedrock_model_id:
        raise RuntimeError("BEDROCK_MODEL_ID n'est pas defini")

    content: list[dict[str, Any]] = [{"text": user_text}]
    if image_b64 and image_media_type:
        format_image = _FORMATS_IMAGE.get(image_media_type)
        if format_image is None:
            raise ValueError(f"Format d'image non supporte par Bedrock : {image_media_type}")
        content.append({"image": {"format": format_image, "source": {"bytes": base64.b64decode(image_b64)}}})

    debut = time.perf_counter()
    try:
        reponse = get_client().converse(
            modelId=settings.bedrock_model_id,
            system=[{"text": system_prompt}],
            messages=[{"role": "user", "content": content}],
            inferenceConfig={"maxTokens": 3000, "temperature": 0},
            toolConfig={
                "tools": [{"toolSpec": {"name": tool_name, "description": tool_description, "inputSchema": {"json": input_schema}}}],
                "toolChoice": {"tool": {"name": tool_name}},
            },
        )
    except Exception as exc:
        _tracer_appel_ia(tool_name, debut, succes=False, erreur=exc)
        raise

    for bloc in reponse.get("output", {}).get("message", {}).get("content", []):
        appel = bloc.get("toolUse")
        if appel and appel.get("name") == tool_name:
            _tracer_appel_ia(tool_name, debut, succes=True)
            return appel["input"]

    erreur = RuntimeError("Le modele n'a pas retourne l'outil attendu")
    _tracer_appel_ia(tool_name, debut, succes=False, erreur=erreur)
    raise erreur


def _tracer_appel_ia(operation: str, debut: float, *, succes: bool, erreur: Exception | None = None) -> None:
    """Meme telemetrie que groq_client : latence + succes/echec, jamais le prompt ni le document."""
    latence_ms = round((time.perf_counter() - debut) * 1000)
    if succes:
        logger.info("ai_call provider=bedrock operation=%s success=true latency_ms=%d", operation, latence_ms)
    else:
        logger.warning(
            "ai_call provider=bedrock operation=%s success=false latency_ms=%d error=%s",
            operation,
            latence_ms,
            type(erreur).__name__ if erreur is not None else "unknown",
        )
