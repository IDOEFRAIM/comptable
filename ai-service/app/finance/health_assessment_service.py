"""Orchestration du node analyze_financial_health (STEP 8.8-8.9 du plan d'implementation).

Le statut (WATCH/AT_RISK/CRITICAL) est deja decide avant d'appeler cette classe
(app/finance/health_policy.py, deterministe) - ce service ne fait qu'appeler le LLM pour produire
le texte narratif (signals/risks/positive_factors/priority_actions), toujours grounde dans les
faits deja recus via MCP, jamais un recalcul. Reutilise integralement LLMProvider
(app/services/llm_provider.py) - meme discipline que ClassificationService (STEP 6) : un seul
appel structure, jamais une boucle multi-tours, jamais un nouveau framework agent."""

import logging
from uuid import UUID

from pydantic import ValidationError

from app.core.config import get_settings
from app.finance.prompts.health import (
    SYSTEM_PROMPT,
    TOOL_DESCRIPTION,
    TOOL_NAME,
    construire_input_schema,
)
from app.schemas.finance import CashPosition, FinancialSnapshotWithMargins, Payables, Receivables
from app.schemas.financial_health import FinancialHealthAssessment, FinancialHealthStatus
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.bedrock_llm_provider import BedrockLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


class FinancialHealthLlmError(Exception):
    """Le LLM d'interpretation a echoue ou renvoye une reponse non conforme a
    FinancialHealthAssessment - jamais masquee en une interpretation inventee (structured output
    obligatoire, meme discipline que ClassificationLlmError)."""


def _get_default_provider() -> LLMProvider:
    """Meme switch que classification_service.py/matching_engine.py/copilot_service.py - pas de
    nouvelle abstraction provider pour ce service (section 26 du plan)."""

    provider = get_settings().ai_provider
    if provider == "anthropic":
        return AnthropicLLMProvider()
    if provider == "groq":
        return GroqLLMProvider()
    if provider == "bedrock":
        return BedrockLLMProvider()
    return MockLLMProvider()


class FinancialHealthAssessmentService:
    """`llm_provider=None` (defaut) utilise le provider configure (AI_PROVIDER) - injectable pour
    les tests, meme convention que ClassificationService."""

    def __init__(self, llm_provider: LLMProvider | None = None):
        self._llm_provider = llm_provider

    def evaluer(
        self,
        *,
        status: FinancialHealthStatus,
        snapshot: FinancialSnapshotWithMargins,
        cash_position: CashPosition,
        receivables: Receivables,
        payables: Payables,
        workflow_id: UUID | None = None,
    ) -> FinancialHealthAssessment:
        provider = self._llm_provider if self._llm_provider is not None else _get_default_provider()
        user_text = self._construire_user_text(status, snapshot, cash_position, receivables, payables)

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
                "financial_health workflow_id=%s node=analyze_financial_health result=echec error=%s",
                workflow_id, type(exc).__name__,
            )
            raise FinancialHealthLlmError(f"Appel LLM d'interpretation en echec : {type(exc).__name__}") from exc

        try:
            assessment = FinancialHealthAssessment.model_validate(
                {
                    **brut,
                    "status": status.value,
                    "as_of": cash_position.date_reference.isoformat(),
                    "period": f"{snapshot.snapshot.periode_debut.isoformat()}/{snapshot.snapshot.periode_fin.isoformat()}",
                    # Aucun DTO Java de la Financial Intelligence Layer ne porte de champ devise
                    # aujourd'hui (limite documentee, meme convention que le reste du systeme -
                    # cf. NormalizedAccountingDocument.devise, defaut "XOF" SYSCOHADA) - jamais
                    # invente comme une valeur calculee, seulement un defaut de plateforme.
                    "currency": "XOF",
                }
            )
        except ValidationError as exc:
            logger.warning(
                "financial_health workflow_id=%s node=analyze_financial_health result=echec "
                "error=structured_output_invalide",
                workflow_id,
            )
            raise FinancialHealthLlmError("Sortie structuree du LLM non conforme a FinancialHealthAssessment") from exc

        logger.info(
            "financial_health workflow_id=%s node=analyze_financial_health status=%s",
            workflow_id, assessment.status.value,
        )
        return assessment

    @staticmethod
    def _construire_user_text(
        status: FinancialHealthStatus,
        snapshot: FinancialSnapshotWithMargins,
        cash_position: CashPosition,
        receivables: Receivables,
        payables: Payables,
    ) -> str:
        """Ne transmet QUE des faits deja calcules par Java - jamais un ratio derive ici (DSO,
        runway...) qui donnerait au LLM l'impression que ces concepts font partie des donnees
        disponibles."""

        marge = (
            f"{snapshot.net_margin}" if snapshot.net_margin is not None
            else f"indisponible ({snapshot.net_margin_unavailable_reason})"
        )
        return (
            f"Statut deja decide par le systeme (a expliquer, jamais a recalculer) : {status.value}\n"
            f"Periode : {snapshot.snapshot.periode_debut} au {snapshot.snapshot.periode_fin}\n"
            f"Revenue : {snapshot.snapshot.revenue}\n"
            f"Expenses : {snapshot.snapshot.expenses}\n"
            f"Profit : {snapshot.snapshot.profit}\n"
            f"Net margin : {marge}\n"
            f"Cash : {cash_position.total} (au {cash_position.date_reference})\n"
            f"Receivables total : {receivables.total} (current : {receivables.current}, overdue : {receivables.overdue})\n"
            f"Payables total : {payables.total} (current : {payables.current}, overdue : {payables.overdue})"
        )
