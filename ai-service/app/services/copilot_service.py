"""Evidence-First AI Copilot (mission P1, sections 14-19, 42-48) : un seul appel LLM par question,
jamais une boucle d'outils (contrairement a agent_orchestrator.py, reserve aux cas ambigus - voir
mission section 45). Le LLM ne calcule jamais rien : le Core Java a deja determine l'intention de
la question et recupere tous les faits necessaires avant d'appeler ce service ; ce module ne fait
que les mettre en mots, en francais, sans jamais en ajouter."""

import logging

from app.core.config import get_settings
from app.schemas.copilot import CopilotExplainRequest, CopilotExplainResponse
from app.services import copilot_prompts
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.bedrock_llm_provider import BedrockLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


def _get_default_provider() -> LLMProvider:
    if get_settings().ai_provider == "anthropic":
        return AnthropicLLMProvider()
    if get_settings().ai_provider == "groq":
        return GroqLLMProvider()
    if get_settings().ai_provider == "bedrock":
        return BedrockLLMProvider()
    return MockLLMProvider()


def expliquer(request: CopilotExplainRequest, *, llm_provider: LLMProvider | None = None) -> CopilotExplainResponse:
    """Ne leve jamais d'exception : une panne LLM degrade vers une explication generique plutot
    que de faire planter l'appelant (meme philosophie que matching_engine.analyze) - les faits
    dejà calcules par le Core Java restent fiables et affichables meme sans mise en mots IA."""
    provider = llm_provider if llm_provider is not None else _get_default_provider()
    facts_formates = _formater_facts(request.facts)
    user_text = copilot_prompts.construire_texte_utilisateur(request.question, request.intent, facts_formates)

    try:
        raw = provider.call_structured(
            system_prompt=copilot_prompts.SYSTEM_PROMPT,
            user_text=user_text,
            tool_name="expliquer_dossier",
            tool_description="Retourne une explication en francais basee uniquement sur les faits fournis",
            input_schema=copilot_prompts.INPUT_SCHEMA,
        )
        explanation = raw.get("explanation")
        if not explanation:
            raise ValueError("reponse LLM sans champ 'explanation' exploitable")
        return CopilotExplainResponse(explanation=explanation)
    except Exception as exc:
        logger.warning("Copilot : LLM indisponible, degradation vers une explication generique : %s", exc)
        return CopilotExplainResponse(
            explanation="Explication automatique indisponible pour le moment (IA hors-ligne) - "
            "les faits verifies ci-dessus restent fiables et a jour."
        )


def _formater_facts(facts: dict) -> str:
    if not facts:
        return "(aucun fait disponible)"
    return "\n".join(f"- {cle} : {valeur}" for cle, valeur in facts.items())
