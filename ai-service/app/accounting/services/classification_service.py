"""Orchestration de classify_accounting_nature (STEP 6.4-6.5) : deterministe d'abord, LLM en repli
uniquement si aucune regle deterministe ne correspond - jamais l'inverse (section 5 du plan).

Reutilise integralement LLMProvider (app/services/llm_provider.py) - pas de nouvel agent
framework, pas de boucle multi-tours, un seul appel structure (meme discipline que
matching_engine.py : deterministe d'abord, LLM au plus une fois)."""

import logging
from uuid import UUID

from pydantic import ValidationError

from app.accounting.classifiers.deterministic import classifier_deterministe
from app.accounting.prompts.classification import (
    SYSTEM_PROMPT,
    TOOL_DESCRIPTION,
    TOOL_NAME,
    construire_input_schema,
)
from app.core.config import get_settings
from app.schemas.accounting import AccountingClassification, NormalizedAccountingDocument
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


class ClassificationLlmError(Exception):
    """Le LLM de secours a echoue ou renvoye une reponse non conforme a AccountingClassification -
    jamais masquee en une classification inventee (structured output obligatoire, section 9 du
    plan)."""


def _get_default_provider() -> LLMProvider:
    """Meme switch que matching_engine.py/copilot_service.py::_get_default_provider - pas de
    nouvelle abstraction provider pour ce classifieur (section 26 du plan)."""

    provider = get_settings().ai_provider
    if provider == "anthropic":
        return AnthropicLLMProvider()
    if provider == "groq":
        return GroqLLMProvider()
    return MockLLMProvider()


class ClassificationService:
    """`llm_provider=None` (defaut) utilise le provider configure (AI_PROVIDER) - injectable pour
    les tests, meme convention que matching_engine.analyze(..., llm_provider=...)."""

    def __init__(self, llm_provider: LLMProvider | None = None):
        self._llm_provider = llm_provider

    def classifier(self, document: NormalizedAccountingDocument, *, workflow_id: UUID | None = None) -> AccountingClassification:
        resultat_deterministe = classifier_deterministe(document)
        if resultat_deterministe is not None:
            logger.info(
                "accounting_classification workflow_id=%s classifier=deterministe nature=%s confidence=%s",
                workflow_id, resultat_deterministe.accounting_nature.value, resultat_deterministe.confidence,
            )
            return resultat_deterministe

        return self._classifier_par_llm(document, workflow_id)

    def _classifier_par_llm(self, document: NormalizedAccountingDocument, workflow_id: UUID | None) -> AccountingClassification:
        provider = self._llm_provider if self._llm_provider is not None else _get_default_provider()
        user_text = (
            f"Fournisseur: {document.fournisseur or 'inconnu'}\n"
            f"Libelle: {document.libelle or 'non renseigne'}\n"
            f"Montant HT: {document.montant_ht}\n"
            f"Montant TVA: {document.montant_tva}\n"
            f"Montant TTC: {document.montant_ttc}\n"
            f"Devise: {document.devise}"
        )

        try:
            brut = provider.call_structured(
                system_prompt=SYSTEM_PROMPT,
                user_text=user_text,
                tool_name=TOOL_NAME,
                tool_description=TOOL_DESCRIPTION,
                input_schema=construire_input_schema(),
            )
        except Exception as exc:
            logger.warning(
                "accounting_classification workflow_id=%s classifier=llm result=echec error=%s",
                workflow_id, type(exc).__name__,
            )
            raise ClassificationLlmError(f"Appel LLM de classification en echec : {type(exc).__name__}") from exc

        try:
            classification = AccountingClassification.model_validate(brut)
        except ValidationError as exc:
            logger.warning(
                "accounting_classification workflow_id=%s classifier=llm result=echec error=structured_output_invalide",
                workflow_id,
            )
            raise ClassificationLlmError("Sortie structuree du LLM non conforme a AccountingClassification") from exc

        logger.info(
            "accounting_classification workflow_id=%s classifier=llm nature=%s confidence=%s",
            workflow_id, classification.accounting_nature.value, classification.confidence,
        )
        return classification
