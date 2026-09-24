"""STEP 11.9 : CfoAnalysisService ne recoit que des facts deja construits (jamais un acces aux
tools) - grounding structurel, pas seulement une consigne de prompt."""

from datetime import date
from decimal import Decimal

import pytest

from app.cfo.analysis import CfoAnalysisError, CfoAnalysisService
from app.schemas.cfo import CfoIntent, CfoResponse, FinancialFact, FinancialFactDataType
from app.schemas.financial_health import FinancialHealthStatus
from app.services.mock_llm_provider import MockLLMProvider


def _fact(name: str, value: Decimal, source_tool: str = "finance.get_cash_position") -> FinancialFact:
    return FinancialFact(
        name=name, value=value, currency="XOF", as_of=date(2026, 8, 27),
        source_tool=source_tool, data_type=FinancialFactDataType.ACTUAL,
    )


def test_evaluer_combine_les_facts_deja_connus_avec_lanalyse_llm():
    llm = MockLLMProvider(
        responses=[
            {
                "summary": "Situation stable mais creances elevees.",
                "analysis": ["Le cash couvre les dettes court terme."],
                "risks": ["Dependance au recouvrement des creances."],
                "recommendations": [
                    {"priority": "HIGH", "action": "Relancer les creances en retard", "reason": "creances = 21.8M",
                     "expected_impact": None, "confidence": 0.8, "supporting_facts": ["receivables_total"]}
                ],
                "missing_data": [],
            }
        ]
    )
    service = CfoAnalysisService(llm_provider=llm)
    facts = [_fact("cash", Decimal(12500000)), _fact("receivables_total", Decimal(21800000), "finance.get_receivables")]

    resultat = service.evaluer(
        user_message="Comment va mon entreprise ?", intent=CfoIntent.FINANCIAL_HEALTH,
        financial_facts=facts, tools_used=["finance.get_cash_position", "finance.get_receivables"],
    )

    assert isinstance(resultat, CfoResponse)
    assert resultat.facts == facts  # jamais reconstruits par le LLM
    assert resultat.tools_used == ["finance.get_cash_position", "finance.get_receivables"]
    assert resultat.recommendations[0].supporting_facts == ["receivables_total"]


def test_une_recommandation_ne_peut_jamais_citer_un_fact_non_recu():
    # Le LLM tente de justifier une recommandation avec un fact qui n'a jamais ete fourni -
    # doit etre filtre, jamais accepte tel quel (grounding, section 21 du plan).
    llm = MockLLMProvider(
        responses=[
            {
                "summary": "resume", "analysis": [], "risks": [],
                "recommendations": [
                    {"priority": "HIGH", "action": "action", "reason": "raison",
                     "expected_impact": None, "confidence": None,
                     "supporting_facts": ["cash", "dso_moyen_invente"]}
                ],
                "missing_data": [],
            }
        ]
    )
    service = CfoAnalysisService(llm_provider=llm)
    facts = [_fact("cash", Decimal(12500000))]

    resultat = service.evaluer(
        user_message="Question", intent=CfoIntent.CASH_POSITION, financial_facts=facts, tools_used=["finance.get_cash_position"],
    )

    assert resultat.recommendations[0].supporting_facts == ["cash"]
    assert "dso_moyen_invente" not in resultat.recommendations[0].supporting_facts


def test_missing_capabilities_est_fusionne_dans_missing_data():
    llm = MockLLMProvider(
        responses=[{"summary": "resume", "analysis": [], "risks": [], "recommendations": [], "missing_data": ["profitabilite client indisponible"]}]
    )
    service = CfoAnalysisService(llm_provider=llm)

    resultat = service.evaluer(
        user_message="Puis-je recruter deux commerciaux ?", intent=CfoIntent.SCENARIO,
        financial_facts=[], tools_used=[], missing_capabilities=["scenario simulation not yet available"],
    )

    assert "profitabilite client indisponible" in resultat.missing_data
    assert "scenario simulation not yet available" in resultat.missing_data


def test_panne_llm_leve_cfo_analysis_error():
    llm = MockLLMProvider(responses=[TimeoutError("panne simulee")])
    service = CfoAnalysisService(llm_provider=llm)

    with pytest.raises(CfoAnalysisError):
        service.evaluer(user_message="Question", intent=CfoIntent.GENERAL_FINANCIAL, financial_facts=[], tools_used=[])


def test_reponse_non_conforme_leve_cfo_analysis_error():
    llm = MockLLMProvider(responses=[{"summary": "resume"}])  # champs requis manquants
    service = CfoAnalysisService(llm_provider=llm)

    with pytest.raises(CfoAnalysisError):
        service.evaluer(user_message="Question", intent=CfoIntent.GENERAL_FINANCIAL, financial_facts=[], tools_used=[])


def test_le_statut_de_sante_financiere_deja_decide_est_transmis_au_llm_jamais_recalcule():
    # STEP 11, section 11.12 : reutilise app/finance/health_policy.py (deterministe) - le statut
    # est transmis comme un fait deja decide, jamais laisse au LLM.
    llm = MockLLMProvider(responses=[{"summary": "s", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}])
    service = CfoAnalysisService(llm_provider=llm)
    facts = [_fact("cash", Decimal(12500000))]

    service.evaluer(
        user_message="Comment va mon entreprise ?", intent=CfoIntent.FINANCIAL_HEALTH, financial_facts=facts,
        tools_used=[], financial_health_status=FinancialHealthStatus.AT_RISK,
    )

    texte = llm.calls[0]["user_text"]
    assert "AT_RISK" in texte


def test_anti_hallucination_seuls_les_chiffres_fournis_apparaissent_dans_le_prompt():
    # STEP 11, section 30/TEST 9 du plan : injecter cash=10M, receivables=30M - verifie que le
    # texte transmis au LLM ne contient que ces chiffres exacts, jamais un autre.
    llm = MockLLMProvider(responses=[{"summary": "s", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}])
    service = CfoAnalysisService(llm_provider=llm)
    facts = [_fact("cash", Decimal(10000000)), _fact("receivables_total", Decimal(30000000), "finance.get_receivables")]

    service.evaluer(user_message="Comment va mon entreprise ?", intent=CfoIntent.FINANCIAL_HEALTH, financial_facts=facts, tools_used=[])

    texte = llm.calls[0]["user_text"]
    assert "10000000" in texte
    assert "30000000" in texte
    # Exactement une ligne de fait par fact fourni - aucun troisieme montant invente/derive.
    lignes_de_fait = [ligne for ligne in texte.splitlines() if ligne.strip().startswith("- ")]
    assert len(lignes_de_fait) == 2
