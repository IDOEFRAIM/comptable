"""CfoAnalysisService (STEP 11.9 du plan d'implementation) : derniere etape du CFO ("PASS 2" -
section 25 du plan) - transforme les facts deja recus en reponse narrative structuree. Ne recoit
JAMAIS un acces aux tools/a la base : uniquement la liste de FinancialFact deja construite par
FinancialFactExtractor (STEP 11.8), deja tracee jusqu'a son tool d'origine. Le LLM ne peut donc
structurellement pas inventer un chiffre absent de cette liste - il ne voit que ce qui lui est
donne."""

import logging

from app.cfo.prompts.analysis import (
    SYSTEM_PROMPT,
    TOOL_DESCRIPTION,
    TOOL_NAME,
    construire_input_schema,
)
from app.core.config import get_settings
from app.schemas.cfo import CfoIntent, CfoRecommendation, CfoResponse, FinancialFact
from app.schemas.financial_health import FinancialHealthStatus
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


class CfoAnalysisError(Exception):
    """Le LLM d'analyse a echoue ou renvoye une reponse non conforme - jamais masquee en une
    analyse inventee (meme discipline que CashRiskLlmError/FinancialHealthLlmError)."""


def _get_default_provider() -> LLMProvider:
    provider = get_settings().ai_provider
    if provider == "anthropic":
        return AnthropicLLMProvider()
    if provider == "groq":
        return GroqLLMProvider()
    return MockLLMProvider()


class CfoAnalysisService:
    def __init__(self, llm_provider: LLMProvider | None = None):
        self._llm_provider = llm_provider

    def evaluer(
        self, *, user_message: str, intent: CfoIntent, financial_facts: list[FinancialFact],
        tools_used: list[str], missing_capabilities: list[str] | None = None,
        financial_health_status: FinancialHealthStatus | None = None,
        cash_forecast_confidence: str | None = None,
    ) -> CfoResponse:
        provider = self._llm_provider if self._llm_provider is not None else _get_default_provider()
        user_text = self._construire_user_text(
            user_message, intent, financial_facts, missing_capabilities or [],
            financial_health_status, cash_forecast_confidence,
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
            logger.warning("cfo_analysis result=echec error=appel_llm detail=%s", type(exc).__name__)
            raise CfoAnalysisError(f"Appel LLM d'analyse en echec : {type(exc).__name__}") from exc

        noms_facts_connus = {fait.name for fait in financial_facts}
        try:
            recommandations = [
                CfoRecommendation(
                    priority=r["priority"], action=r["action"], reason=r["reason"],
                    expected_impact=r.get("expected_impact"), confidence=r.get("confidence"),
                    # Defense en profondeur (section 21 du plan, grounding) : une recommandation ne
                    # peut jamais citer un fact qui n'a pas reellement ete recu.
                    supporting_facts=[nom for nom in r.get("supporting_facts", []) if nom in noms_facts_connus],
                )
                for r in brut["recommendations"]
            ]
            missing_data = list(brut["missing_data"]) + list(missing_capabilities or [])
            response = CfoResponse(
                summary=brut["summary"], facts=financial_facts, analysis=list(brut["analysis"]),
                risks=list(brut["risks"]), recommendations=recommandations, missing_data=missing_data,
                tools_used=tools_used,
            )
        except (KeyError, TypeError) as exc:
            logger.warning("cfo_analysis result=echec error=sortie_non_conforme detail=%s", type(exc).__name__)
            raise CfoAnalysisError("Sortie structuree du LLM non conforme a CfoResponse") from exc

        logger.info(
            "cfo_analysis intent=%s facts=%d recommendations=%d missing_data=%d",
            intent.value, len(financial_facts), len(response.recommendations), len(response.missing_data),
        )
        return response

    @staticmethod
    def _construire_user_text(
        user_message: str, intent: CfoIntent, financial_facts: list[FinancialFact], missing_capabilities: list[str],
        financial_health_status: FinancialHealthStatus | None = None,
        cash_forecast_confidence: str | None = None,
    ) -> str:
        lignes = [f"Question du dirigeant : {user_message}", f"Intention : {intent.value}", ""]
        if financial_health_status is not None:
            # Reutilise app/finance/health_policy.py::determiner_statut (deterministe, calcule dans
            # le node analyze du graph CFO) - jamais recalcule ni redecide par le LLM ici, meme
            # discipline que FinancialHealthAssessmentService (STEP 8).
            lignes.append(
                f"Statut de sante financiere deja decide par le systeme (a expliquer, jamais a "
                f"recalculer) : {financial_health_status.value}"
            )
            lignes.append("")
        if cash_forecast_confidence is not None:
            # Deja calcule par CashForecastService (Java, STEP 9) - transmis tel quel, jamais
            # redevine par le LLM (aucun champ Decimal ne peut porter un label qualitatif LOW/
            # MEDIUM/HIGH, donc transmis ici plutot que via FinancialFact).
            lignes.append(f"Confiance de la prevision de tresorerie (deja calculee) : {cash_forecast_confidence}")
            lignes.append("")
        if financial_facts:
            lignes.append("Faits financiers disponibles (ce sont les SEULES donnees que tu peux citer) :")
            for fait in financial_facts:
                lignes.append(
                    f"  - {fait.name} = {fait.value} {fait.currency}"
                    f" (type={fait.data_type.value}, source={fait.source_tool}"
                    + (f", periode={fait.period}" if fait.period else "")
                    + (f", au {fait.as_of}" if fait.as_of else "")
                    + ")"
                )
        else:
            lignes.append("Aucun fait financier disponible.")
        if missing_capabilities:
            lignes.append("")
            lignes.append("Capabilities non disponibles pour cette question (a signaler explicitement) :")
            for capacite in missing_capabilities:
                lignes.append(f"  - {capacite}")
        return "\n".join(lignes)
