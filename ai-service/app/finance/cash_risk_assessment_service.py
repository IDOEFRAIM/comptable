"""Orchestration du node analyze_cash_risk (STEP 9.14-9.16 du plan d'implementation).

Le statut (HEALTHY/WATCH/AT_RISK/CRITICAL), la semaine critique et le solde minimum projete sont
deja decides avant d'appeler cette classe (app/finance/cash_risk_policy.py, deterministe) - ce
service ne fait qu'appeler le LLM pour produire le texte narratif (main_drivers/
positive_factors/recommended_actions), toujours grounde dans le CashForecast deja recu via MCP,
jamais un recalcul. Reutilise integralement LLMProvider - meme discipline que
FinancialHealthAssessmentService (STEP 8) et ClassificationService (STEP 6) : un seul appel
structure, jamais une boucle multi-tours, jamais un nouveau framework agent."""

import logging
from decimal import Decimal
from uuid import UUID

from pydantic import ValidationError

from app.core.config import get_settings
from app.finance.prompts.cash_risk import (
    SYSTEM_PROMPT,
    TOOL_DESCRIPTION,
    TOOL_NAME,
    construire_input_schema,
)
from app.schemas.cash_risk import CashRiskAssessment, CashRiskStatus
from app.schemas.finance import CashForecast
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


class CashRiskLlmError(Exception):
    """Le LLM d'interpretation a echoue ou renvoye une reponse non conforme a CashRiskAssessment -
    jamais masquee en une interpretation inventee (structured output obligatoire, meme discipline
    que FinancialHealthLlmError/ClassificationLlmError)."""


def _get_default_provider() -> LLMProvider:
    """Meme switch que health_assessment_service.py/classification_service.py - pas de nouvelle
    abstraction provider pour ce service (section 26 du plan STEP 8, toujours valable ici)."""

    provider = get_settings().ai_provider
    if provider == "anthropic":
        return AnthropicLLMProvider()
    if provider == "groq":
        return GroqLLMProvider()
    return MockLLMProvider()


class CashRiskAssessmentService:
    """`llm_provider=None` (defaut) utilise le provider configure (AI_PROVIDER) - injectable pour
    les tests, meme convention que FinancialHealthAssessmentService."""

    def __init__(self, llm_provider: LLMProvider | None = None):
        self._llm_provider = llm_provider

    def evaluer(
        self, *, status: CashRiskStatus, critical_week: int | None, minimum_projected_cash: Decimal,
        forecast: CashForecast, workflow_id: UUID | None = None,
    ) -> CashRiskAssessment:
        provider = self._llm_provider if self._llm_provider is not None else _get_default_provider()
        user_text = self._construire_user_text(status, critical_week, minimum_projected_cash, forecast)

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
                "cash_risk workflow_id=%s node=analyze_cash_risk result=echec error=%s",
                workflow_id, type(exc).__name__,
            )
            raise CashRiskLlmError(f"Appel LLM d'interpretation en echec : {type(exc).__name__}") from exc

        try:
            assessment = CashRiskAssessment.model_validate(
                {
                    **brut,
                    "status": status.value,
                    "critical_week": critical_week,
                    "minimum_projected_cash": str(minimum_projected_cash),
                    "as_of": forecast.as_of.isoformat(),
                    "period": f"{forecast.period_start.isoformat()}/{forecast.period_end.isoformat()}",
                    "currency": forecast.currency,
                }
            )
        except ValidationError as exc:
            logger.warning(
                "cash_risk workflow_id=%s node=analyze_cash_risk result=echec error=structured_output_invalide",
                workflow_id,
            )
            raise CashRiskLlmError("Sortie structuree du LLM non conforme a CashRiskAssessment") from exc

        logger.info(
            "cash_risk workflow_id=%s node=analyze_cash_risk status=%s critical_week=%s",
            workflow_id, assessment.status.value, assessment.critical_week,
        )
        return assessment

    @staticmethod
    def _construire_user_text(status: CashRiskStatus, critical_week: int | None, minimum_projected_cash: Decimal,
                               forecast: CashForecast) -> str:
        """Ne transmet QUE des faits deja calcules par Java (CashForecast recu via MCP) - jamais un
        chiffre/evenement invente ici."""

        lignes = [
            f"Statut deja decide par le systeme (a expliquer, jamais a recalculer) : {status.value}",
            f"Semaine critique : {critical_week if critical_week is not None else 'aucune identifiee'}",
            f"Solde minimum projete sur l'horizon : {minimum_projected_cash}",
            f"Solde d'ouverture : {forecast.opening_cash} (au {forecast.as_of})",
            f"Solde de cloture en fin d'horizon : {forecast.closing_cash}",
            f"Seuil minimum de tresorerie : {forecast.minimum_cash_threshold if forecast.minimum_cash_threshold is not None else 'non configure'}",
            f"Confiance du forecast : {forecast.confidence.value}",
        ]
        if forecast.data_quality_warnings:
            lignes.append("Avertissements de qualite de donnees : " + "; ".join(forecast.data_quality_warnings))
        if forecast.liquidity_events:
            lignes.append("Evenements de liquidite detectes :")
            for evenement in forecast.liquidity_events:
                lignes.append(
                    f"  - {evenement.type.value} (severite {evenement.severity.value}, semaine "
                    f"{evenement.week_index}, montant {evenement.amount}) : {evenement.reason}"
                )
        else:
            lignes.append("Aucun evenement de liquidite detecte.")
        return "\n".join(lignes)
