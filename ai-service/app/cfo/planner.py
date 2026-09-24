"""select_tools (STEP 11.6 du plan d'implementation) : deuxieme etape du CFO - decide quels tools
MCP appeler pour repondre a la question, a partir du catalogue reel (app/agent/tools/catalog.py).
Ne retourne que des noms de tools deja presents dans le catalogue (contrainte structurelle via le
schema JSON `enum`, section 26 du plan - TOOL_ALLOWLIST) - jamais un tool invente, jamais leurs
arguments (section 27, tenant lock). N'execute jamais les tools lui-meme."""

import logging

from app.agent.tools.catalog import CFO_TOOL_CATALOG, CfoToolDescriptor
from app.cfo.prompts.planner import (
    SYSTEM_PROMPT,
    TOOL_DESCRIPTION,
    TOOL_NAME,
    construire_catalogue_texte,
    construire_input_schema,
)
from app.core.config import get_settings
from app.schemas.cfo import CfoIntent
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


class ToolPlanningError(Exception):
    """Le LLM de planification a echoue ou renvoye une reponse non conforme - jamais masquee en
    une liste de tools inventee (meme discipline que IntentClassificationError)."""


def _get_default_provider() -> LLMProvider:
    provider = get_settings().ai_provider
    if provider == "anthropic":
        return AnthropicLLMProvider()
    if provider == "groq":
        return GroqLLMProvider()
    return MockLLMProvider()


def select_tools(
    user_message: str, intent: CfoIntent, llm_provider: LLMProvider | None = None,
    catalog: tuple[CfoToolDescriptor, ...] = CFO_TOOL_CATALOG,
) -> list[str]:
    """Retourne une liste ordonnee de noms de tools - jamais leurs arguments, jamais un nom hors
    catalogue (le schema JSON contraint la sortie a un `enum` des noms reellement catalogues,
    section 26 du plan)."""

    provider = llm_provider if llm_provider is not None else _get_default_provider()
    noms_disponibles = [descripteur.name for descripteur in catalog]

    user_text = (
        f"Question du dirigeant : {user_message}\n"
        f"Intention deja identifiee : {intent.value}\n\n"
        f"Catalogue des outils disponibles :\n{construire_catalogue_texte(catalog)}"
    )

    try:
        brut = provider.call_structured(
            system_prompt=SYSTEM_PROMPT,
            user_text=user_text,
            tool_name=TOOL_NAME,
            tool_description=TOOL_DESCRIPTION,
            input_schema=construire_input_schema(noms_disponibles),
        )
        outils = list(brut["tools"])
    except Exception as exc:
        logger.warning("cfo_planner result=echec error=%s", type(exc).__name__)
        raise ToolPlanningError(f"Planification des tools en echec : {type(exc).__name__}") from exc

    # Defense en profondeur (section 26, TOOL_ALLOWLIST) : meme si le schema JSON contraint deja
    # la sortie a un enum des noms catalogues, un provider ne respectant pas strictement le schema
    # (mock/test, ou un futur provider moins strict) ne doit jamais pouvoir injecter un tool hors
    # catalogue - filtre silencieusement, jamais une exception qui ferait echouer tout le tour.
    outils_valides = [nom for nom in outils if nom in noms_disponibles]
    if len(outils_valides) != len(outils):
        logger.warning("cfo_planner outils_rejetes=%s", set(outils) - set(outils_valides))

    logger.info("cfo_planner intent=%s tools=%s", intent.value, outils_valides)
    return outils_valides
