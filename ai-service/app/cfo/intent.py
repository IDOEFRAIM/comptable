"""classify_intent (STEP 11.5 du plan d'implementation) : premiere etape du CFO - identifie de
quoi parle la question du dirigeant, avant toute selection de tool. Reutilise integralement
LLMProvider (app/services/llm_provider.py) - meme discipline que ClassificationService (STEP 6) et
FinancialHealthAssessmentService (STEP 8) : un seul appel structure, jamais une boucle
multi-tours."""

import logging

from app.cfo.prompts.intent import (
    SYSTEM_PROMPT,
    TOOL_DESCRIPTION,
    TOOL_NAME,
    construire_input_schema,
)
from app.core.config import get_settings
from app.schemas.cfo import CfoIntent
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


class IntentClassificationError(Exception):
    """Le LLM de classification d'intention a echoue ou renvoye une reponse non conforme - jamais
    masquee en une intention inventee (structured output obligatoire, meme discipline que
    ClassificationLlmError/FinancialHealthLlmError/CashRiskLlmError). C'est au node du graph
    (app/agent/graph/cfo.py), jamais a ce module, de decider s'il degrade vers GENERAL_FINANCIAL
    ou laisse l'erreur se propager - une politique de repli reste une decision de graph, pas un
    detail cache dans le service."""


def _get_default_provider() -> LLMProvider:
    """Meme switch que classification_service.py/health_assessment_service.py/
    cash_risk_assessment_service.py - pas de nouvelle abstraction provider."""

    provider = get_settings().ai_provider
    if provider == "anthropic":
        return AnthropicLLMProvider()
    if provider == "groq":
        return GroqLLMProvider()
    return MockLLMProvider()


def classify_intent(user_message: str, llm_provider: LLMProvider | None = None) -> tuple[CfoIntent, float]:
    """Retourne (intent, confidence) - jamais une reponse finale (section 7 du plan). Leve
    IntentClassificationError sur panne LLM ou sortie non conforme - jamais une intention
    inventee en silence."""

    provider = llm_provider if llm_provider is not None else _get_default_provider()

    try:
        brut = provider.call_structured(
            system_prompt=SYSTEM_PROMPT,
            user_text=user_message,
            tool_name=TOOL_NAME,
            tool_description=TOOL_DESCRIPTION,
            input_schema=construire_input_schema(),
        )
    except Exception as exc:
        logger.warning("cfo_intent result=echec error=appel_llm detail=%s", type(exc).__name__)
        raise IntentClassificationError(f"Appel LLM de classification d'intention en echec : {type(exc).__name__}") from exc

    try:
        intent = CfoIntent(brut["intent"])
        confidence = float(brut["confidence"])
    except (KeyError, ValueError, TypeError) as exc:
        logger.warning("cfo_intent result=echec error=sortie_non_conforme detail=%s", type(exc).__name__)
        raise IntentClassificationError("Sortie structuree du LLM non conforme (intent/confidence)") from exc

    logger.info("cfo_intent intent=%s confidence=%s", intent.value, confidence)
    return intent, confidence
