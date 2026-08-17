import logging
import time
from typing import Any

import anthropic

from app.core.config import get_settings

_client: anthropic.Anthropic | None = None

logger = logging.getLogger(__name__)


def get_client() -> anthropic.Anthropic:
    global _client
    if _client is None:
        _client = anthropic.Anthropic(api_key=get_settings().anthropic_api_key)
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
    """Appelle Claude en forcant l'usage d'un tool unique pour obtenir une reponse structuree
    fiable (jamais de parsing de texte libre / JSON en best-effort)."""
    content: list[dict[str, Any]] = []
    if image_b64 and image_media_type:
        content.append(
            {
                "type": "image",
                "source": {"type": "base64", "media_type": image_media_type, "data": image_b64},
            }
        )
    content.append({"type": "text", "text": user_text})

    settings = get_settings()
    debut = time.perf_counter()
    try:
        response = get_client().messages.create(
            model=settings.anthropic_model,
            max_tokens=1024,
            system=system_prompt,
            tools=[
                {
                    "name": tool_name,
                    "description": tool_description,
                    "input_schema": input_schema,
                }
            ],
            tool_choice={"type": "tool", "name": tool_name},
            messages=[{"role": "user", "content": content}],
        )
    except Exception as exc:
        _tracer_appel_ia(tool_name, debut, succes=False, erreur=exc)
        raise

    for block in response.content:
        if block.type == "tool_use" and block.name == tool_name:
            _tracer_appel_ia(tool_name, debut, succes=True)
            return block.input

    erreur = RuntimeError("Le modele n'a pas retourne l'outil attendu")
    _tracer_appel_ia(tool_name, debut, succes=False, erreur=erreur)
    raise erreur


def _tracer_appel_ia(operation: str, debut: float, *, succes: bool, erreur: Exception | None = None) -> None:
    """Telemetrie IA minimale (mission P1.9, section 'AI Telemetry') : latence + succes/echec par
    operation (tool_name sert de proxy pour l'operation - extraction/fraude/matching/copilot
    partagent tous ce point d'entree unique). Jamais le prompt ni le document, juste de quoi
    repondre a "quelles capacites IA sont fiables en pratique" sans construire un pipeline
    d'observabilite dedie - un simple log structure, lu au besoin."""
    latence_ms = round((time.perf_counter() - debut) * 1000)
    if succes:
        logger.info("ai_call provider=anthropic operation=%s success=true latency_ms=%d", operation, latence_ms)
    else:
        logger.warning(
            "ai_call provider=anthropic operation=%s success=false latency_ms=%d error=%s",
            operation,
            latence_ms,
            type(erreur).__name__ if erreur is not None else "unknown",
        )
