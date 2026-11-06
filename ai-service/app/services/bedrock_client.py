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

    parametres: dict[str, Any] = {
        "modelId": settings.bedrock_model_id,
        "system": [{"text": system_prompt}],
        "messages": [{"role": "user", "content": content}],
        "inferenceConfig": {"maxTokens": 3000, "temperature": 0},
        "toolConfig": {
            "tools": [{"toolSpec": {"name": tool_name, "description": tool_description, "inputSchema": {"json": input_schema}}}],
            "toolChoice": {"tool": {"name": tool_name}},
        },
    }
    debut = time.perf_counter()
    try:
        try:
            reponse = get_client().converse(**parametres)
        except Exception as exc:
            if not _refuse_tool_choice(exc):
                raise
            # Tous les modeles Bedrock ne savent pas « forcer » un outil (toolChoice) : on le laisse alors
            # libre, il n'a de toute facon qu'un seul outil a sa disposition.
            logger.info("Le modele %s refuse toolChoice : nouvel essai sans forcer l'outil", settings.bedrock_model_id)
            del parametres["toolConfig"]["toolChoice"]
            reponse = get_client().converse(**parametres)
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


def converse_avec_outils(
    *,
    system_prompt: str,
    messages: list[dict[str, Any]],
    outils: list[dict[str, Any]],
    max_tokens: int = 2048,
) -> dict[str, Any]:
    """Un tour de conversation avec outils (boucle agentique) via l'API Converse : le modele choisit
    librement d'appeler zero, un ou plusieurs outils (toolChoice auto, contrairement a call_structured). Valable pour tout modele Bedrock gerant les outils (Claude, Nova, Llama, Mistral...).
    `outils` au format {name, description, input_schema}. Retourne le message assistant brut
    ({"role": "assistant", "content": [{"text"...} | {"toolUse": {...}}]}) a rajouter tel quel a `messages`."""
    settings = get_settings()
    if not settings.bedrock_model_id:
        raise RuntimeError("BEDROCK_MODEL_ID n'est pas defini")

    debut = time.perf_counter()
    try:
        reponse = get_client().converse(
            modelId=settings.bedrock_model_id,
            system=[{"text": system_prompt}],
            messages=messages,
            inferenceConfig={"maxTokens": max_tokens, "temperature": 0},
            toolConfig={
                "tools": [
                    {"toolSpec": {"name": o["name"], "description": o["description"], "inputSchema": {"json": o["input_schema"]}}}
                    for o in outils
                ]
            },
        )
    except Exception as exc:
        _tracer_appel_ia("agent_tour", debut, succes=False, erreur=exc)
        raise

    _tracer_appel_ia("agent_tour", debut, succes=True)
    return reponse["output"]["message"]


def _refuse_tool_choice(erreur: Exception) -> bool:
    """Vrai si AWS rejette la requete parce que le modele ne gere pas toolChoice (ValidationException)."""
    reponse = getattr(erreur, "response", None)
    if not isinstance(reponse, dict):
        return False
    detail = reponse.get("Error", {})
    return detail.get("Code") == "ValidationException" and "toolchoice" in str(detail.get("Message", "")).lower()


def _detail_erreur(erreur: Exception | None) -> str:
    """Code et message d'erreur renvoyes par AWS (ex. « ValidationException: The provided model identifier is
    invalid »). Jamais le contenu de la requete : seulement ce que le service repond sur son propre refus."""
    if erreur is None:
        return "unknown"
    reponse = getattr(erreur, "response", None)
    if isinstance(reponse, dict) and "Error" in reponse:
        detail = reponse["Error"]
        return f"{detail.get('Code', type(erreur).__name__)}: {str(detail.get('Message', ''))[:300]}"
    return f"{type(erreur).__name__}: {str(erreur)[:300]}"


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
            _detail_erreur(erreur),
        )


def autodiagnostic() -> int:
    """Appel minimal de bout en bout, a lancer sur le serveur : `docker exec <conteneur ai-service> uv run python -m
    app.services.bedrock_client`. Affiche la configuration lue, puis la reponse du modele ou l'erreur complete d'AWS."""
    reglages = get_settings()
    print(f"VLM_PROVIDER={reglages.vlm_provider} BEDROCK_REGION={reglages.bedrock_region} BEDROCK_MODEL_ID={reglages.bedrock_model_id!r}")
    try:
        resultat = call_structured(
            system_prompt="Tu remplis l'outil demande.",
            user_text="Appelle l'outil avec nombre=7.",
            tool_name="repondre",
            tool_description="Renvoie un nombre",
            input_schema={"type": "object", "properties": {"nombre": {"type": "number"}}, "required": ["nombre"]},
        )
    except Exception as exc:  # noqa: BLE001 - diagnostic : on affiche la cause exacte
        print("ECHEC :", _detail_erreur(exc))
        return 1
    print("OK :", resultat)
    return 0


if __name__ == "__main__":
    raise SystemExit(autodiagnostic())
