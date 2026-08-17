"""Implementation LLMProvider sur Groq : delegue tel quel a groq_client.call_structured (meme
contrat que anthropic_client.call_structured). Selectionnee quand AI_PROVIDER=groq
(cf. matching_engine._get_default_provider / copilot_service._get_default_provider) - utilise le
meme modele/reglages (temperature=0, budget de jetons genereux) que l'extraction/la fraude, deja
verifies en conditions reelles (voir README pour le detail des deux bugs corriges en verifiant ce
chemin)."""

from typing import Any

from app.services import groq_client
from app.services.llm_provider import LLMProvider


class GroqLLMProvider(LLMProvider):
    def call_structured(
        self,
        *,
        system_prompt: str,
        user_text: str,
        tool_name: str,
        tool_description: str,
        input_schema: dict[str, Any],
    ) -> dict[str, Any]:
        return groq_client.call_structured(
            system_prompt=system_prompt,
            user_text=user_text,
            tool_name=tool_name,
            tool_description=tool_description,
            input_schema=input_schema,
        )
