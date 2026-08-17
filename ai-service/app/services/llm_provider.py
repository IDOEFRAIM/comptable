"""Abstraction minimale entre le Universal Matching Engine (app/services/matching_engine.py), le
Copilot (app/services/copilot_service.py) et le fournisseur LLM qui repond a l'unique appel
structure du pipeline. Portee volontairement etroite : extraction_service.py et fraude_service.py
n'y passent pas - ils appellent app.services.groq_client.call_structured directement (Groq, pas
Anthropic - voir mission "extraction/fraude sur Groq" ; hors perimetre de la mission originelle
"finaliser le matching IA sans API Anthropic disponible" qui a introduit cette abstraction).

Le moteur ne doit jamais savoir s'il parle a Anthropic ou a un mock : il ne connait que cette
interface, injectee (ou choisie par defaut via get_llm_provider) au moment de l'appel.
"""

from abc import ABC, abstractmethod
from typing import Any


class LLMProvider(ABC):
    @abstractmethod
    def call_structured(
        self,
        *,
        system_prompt: str,
        user_text: str,
        tool_name: str,
        tool_description: str,
        input_schema: dict[str, Any],
    ) -> dict[str, Any]:
        """Retourne le dict d'input du tool-use force (meme contrat que
        anthropic_client.call_structured). Doit lever une exception (jamais retourner None) en cas
        de panne/reponse non exploitable : c'est a l'appelant (matching_engine.analyze) de degrader
        proprement, pas au provider de le faire a sa place."""
        raise NotImplementedError
