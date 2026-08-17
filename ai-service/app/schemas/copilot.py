"""Contrat de POST /api/v1/copilot/explain (mission P1, "Evidence-First AI Copilot"). Le Core Java
calcule TOUS les faits (facts) avant l'appel : ce microservice ne fait que les reformuler en
francais, jamais les calculer ni en inventer."""

from typing import Any

from pydantic import BaseModel


class CopilotExplainRequest(BaseModel):
    question: str
    intent: str
    facts: dict[str, Any]


class CopilotExplainResponse(BaseModel):
    explanation: str
