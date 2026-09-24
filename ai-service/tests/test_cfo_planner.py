"""STEP 11.6 : select_tools ne retourne jamais un nom de tool hors catalogue, jamais des
arguments (section 8/26/27 du plan)."""

import pytest

from app.cfo.planner import ToolPlanningError, select_tools
from app.schemas.cfo import CfoIntent
from app.services.mock_llm_provider import MockLLMProvider


def test_pourquoi_ma_tresorerie_baisse_selectionne_cash_receivables_payables_forecast():
    llm = MockLLMProvider(
        responses=[{"tools": ["finance.get_cash_position", "finance.get_cash_forecast", "finance.get_receivables", "finance.get_payables"]}]
    )

    outils = select_tools("Pourquoi ma tresorerie baisse ?", CfoIntent.CASH_POSITION, llm_provider=llm)

    assert outils == ["finance.get_cash_position", "finance.get_cash_forecast", "finance.get_receivables", "finance.get_payables"]


def test_combien_aurai_je_dans_13_semaines_selectionne_uniquement_le_forecast():
    llm = MockLLMProvider(responses=[{"tools": ["finance.get_cash_forecast"]}])

    outils = select_tools("Combien aurai-je dans 13 semaines ?", CfoIntent.CASH_FORECAST, llm_provider=llm)

    assert outils == ["finance.get_cash_forecast"]


def test_comment_va_mon_entreprise_selectionne_les_cinq_capabilities_financieres():
    llm = MockLLMProvider(
        responses=[{
            "tools": [
                "finance.get_financial_snapshot", "finance.get_cash_position",
                "finance.get_receivables", "finance.get_payables", "finance.get_working_capital",
            ]
        }]
    )

    outils = select_tools("Comment va mon entreprise ?", CfoIntent.FINANCIAL_HEALTH, llm_provider=llm)

    assert set(outils) == {
        "finance.get_financial_snapshot", "finance.get_cash_position",
        "finance.get_receivables", "finance.get_payables", "finance.get_working_capital",
    }


def test_un_tool_invente_hors_catalogue_est_filtre_jamais_retourne():
    llm = MockLLMProvider(responses=[{"tools": ["finance.get_cash_position", "finance.simulate_scenario"]}])

    outils = select_tools("Puis-je recruter deux commerciaux ?", CfoIntent.SCENARIO, llm_provider=llm)

    assert outils == ["finance.get_cash_position"]
    assert "finance.simulate_scenario" not in outils


def test_aucun_tool_necessaire_retourne_une_liste_vide():
    llm = MockLLMProvider(responses=[{"tools": []}])

    outils = select_tools("Merci beaucoup !", CfoIntent.GENERAL_FINANCIAL, llm_provider=llm)

    assert outils == []


def test_panne_llm_leve_tool_planning_error():
    llm = MockLLMProvider(responses=[TimeoutError("panne simulee")])

    with pytest.raises(ToolPlanningError):
        select_tools("Question", CfoIntent.GENERAL_FINANCIAL, llm_provider=llm)


def test_le_prompt_ne_demande_jamais_les_arguments_des_tools():
    llm = MockLLMProvider(responses=[{"tools": ["finance.get_cash_position"]}])

    select_tools("Quel est mon cash ?", CfoIntent.CASH_POSITION, llm_provider=llm)

    schema = llm.calls[0]["input_schema"]
    assert set(schema["properties"].keys()) == {"tools"}
    assert "company_id" not in str(schema)
