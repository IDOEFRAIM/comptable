import json
import logging
import time
from typing import Any

from groq import Groq

from app.core.config import get_settings

_client: Groq | None = None

logger = logging.getLogger(__name__)


def get_client() -> Groq:
    global _client
    if _client is None:
        _client = Groq(api_key=get_settings().groq_api_key)
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
    """Equivalent Groq de anthropic_client.call_structured : force l'usage d'un tool unique
    (function calling au format OpenAI-compatible expose par Groq) pour obtenir une reponse
    structuree fiable, jamais de parsing de texte libre / JSON en best-effort. Meme signature
    que la version Anthropic - extraction_service.py et fraude_service.py n'ont besoin de
    changer que leur import pour basculer de l'un a l'autre."""
    content: list[dict[str, Any]] = [{"type": "text", "text": user_text}]
    if image_b64 and image_media_type:
        content.append(
            {
                "type": "image_url",
                "image_url": {"url": f"data:{image_media_type};base64,{image_b64}"},
            }
        )

    settings = get_settings()
    debut = time.perf_counter()
    try:
        response = get_client().chat.completions.create(
            model=settings.groq_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": content},
            ],
            tools=[
                {
                    "type": "function",
                    "function": {
                        "name": tool_name,
                        "description": tool_description,
                        "parameters": input_schema,
                    },
                }
            ],
            tool_choice={"type": "function", "function": {"name": tool_name}},
            # temperature=0, pas 1 : verifie experimentalement (facture reelle, qwen/qwen3.6-27b)
            # que temperature=1 rend le tool-calling force peu fiable sur ce modele. Coherent avec
            # le principe du reste du projet : jamais laisser une extraction structuree a l'alea.
            temperature=0,
            # 3000, pas 1024 : qwen/qwen3.6-27b est un modele de raisonnement qui consomme des
            # jetons de "reflexion" interne avant d'emettre le tool call - budget observe en test
            # reel entre ~750 et ~1070 jetons de reflexion selon l'appel, donc non fixe (temperature=0
            # n'est pas parfaitement deterministe cote Groq - meme requete, longueur de reflexion
            # differente d'un appel a l'autre). 1024 tronque systematiquement, 2048 a parfois encore
            # tronque en test reel (2 echecs identiques observes) ; 3000 laisse une marge nettement
            # plus confortable tout en restant sous la limite de 8000 jetons/minute du compte de
            # test (palier gratuit Groq) avec une seule image encodee en base64.
            max_completion_tokens=3000,
        )
    except Exception as exc:
        _tracer_appel_ia(tool_name, debut, succes=False, erreur=exc)
        raise

    tool_calls = response.choices[0].message.tool_calls or []
    for tool_call in tool_calls:
        if tool_call.function.name != tool_name:
            continue
        try:
            arguments = json.loads(tool_call.function.arguments)
        except (TypeError, ValueError) as exc:
            _tracer_appel_ia(tool_name, debut, succes=False, erreur=exc)
            raise RuntimeError("Le modele a retourne des arguments non exploitables (JSON invalide)") from exc
        _tracer_appel_ia(tool_name, debut, succes=True)
        return arguments

    erreur = RuntimeError("Le modele n'a pas retourne l'outil attendu")
    _tracer_appel_ia(tool_name, debut, succes=False, erreur=erreur)
    raise erreur


def _tracer_appel_ia(operation: str, debut: float, *, succes: bool, erreur: Exception | None = None) -> None:
    """Meme contrat de telemetrie que anthropic_client._tracer_appel_ia (mission P1.9) : latence +
    succes/echec par operation, jamais le prompt ni le document."""
    latence_ms = round((time.perf_counter() - debut) * 1000)
    if succes:
        logger.info("ai_call provider=groq operation=%s success=true latency_ms=%d", operation, latence_ms)
    else:
        logger.warning(
            "ai_call provider=groq operation=%s success=false latency_ms=%d error=%s",
            operation,
            latence_ms,
            type(erreur).__name__ if erreur is not None else "unknown",
        )
