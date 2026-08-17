"""Implementation reelle de LLMProvider : delegue tel quel a anthropic_client.call_structured
(inchange par cette mission). Selectionne quand AI_PROVIDER=anthropic (cf. matching_engine._get_default_provider).
"""

from typing import Any

from app.services import anthropic_client
from app.services.llm_provider import LLMProvider


class AnthropicLLMProvider(LLMProvider):
    def call_structured(
        self,
        *,
        system_prompt: str,
        user_text: str,
        tool_name: str,
        tool_description: str,
        input_schema: dict[str, Any],
    ) -> dict[str, Any]:
        return anthropic_client.call_structured(
            system_prompt=system_prompt,
            user_text=user_text,
            tool_name=tool_name,
            tool_description=tool_description,
            input_schema=input_schema,
        )
