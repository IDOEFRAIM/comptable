"""Implementation LLMProvider sur Amazon Bedrock (n'importe quel modele Bedrock via l'API Converse) : delegue tel quel a
bedrock_client.call_structured, meme contrat que groq_client/anthropic_client. Selectionnee quand
AI_PROVIDER=bedrock (cf. les fonctions _get_default_provider de chaque service). Aucune cle API :
boto3 utilise le role IAM de l'instance EC2."""

from typing import Any

from app.services import bedrock_client
from app.services.llm_provider import LLMProvider


class BedrockLLMProvider(LLMProvider):
    def call_structured(
        self,
        *,
        system_prompt: str,
        user_text: str,
        tool_name: str,
        tool_description: str,
        input_schema: dict[str, Any],
    ) -> dict[str, Any]:
        return bedrock_client.call_structured(
            system_prompt=system_prompt,
            user_text=user_text,
            tool_name=tool_name,
            tool_description=tool_description,
            input_schema=input_schema,
        )
