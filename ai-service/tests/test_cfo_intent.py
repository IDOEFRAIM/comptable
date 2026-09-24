"""STEP 11.5 : classify_intent retourne (intent, confidence) uniquement - jamais une reponse
finale, jamais une intention inventee sur panne LLM."""

import pytest

from app.cfo.intent import IntentClassificationError, classify_intent
from app.schemas.cfo import CfoIntent
from app.services.mock_llm_provider import MockLLMProvider


def test_classifie_une_question_de_sante_financiere():
    llm = MockLLMProvider(responses=[{"intent": "FINANCIAL_HEALTH", "confidence": 0.95}])

    intent, confidence = classify_intent("Comment va mon entreprise ?", llm_provider=llm)

    assert intent is CfoIntent.FINANCIAL_HEALTH
    assert confidence == 0.95


def test_classifie_une_question_de_cash_forecast():
    llm = MockLLMProvider(responses=[{"intent": "CASH_FORECAST", "confidence": 0.9}])

    intent, confidence = classify_intent("Combien aurai-je dans 13 semaines ?", llm_provider=llm)

    assert intent is CfoIntent.CASH_FORECAST
    assert confidence == 0.9


def test_classifie_une_question_scenario():
    llm = MockLLMProvider(responses=[{"intent": "SCENARIO", "confidence": 0.8}])

    intent, confidence = classify_intent("Puis-je recruter deux commerciaux ?", llm_provider=llm)

    assert intent is CfoIntent.SCENARIO
    assert confidence == 0.8


def test_ne_retourne_jamais_une_reponse_finale_seulement_intent_et_confidence():
    llm = MockLLMProvider(responses=[{"intent": "CASH_POSITION", "confidence": 0.7}])

    resultat = classify_intent("Quel est mon cash actuel ?", llm_provider=llm)

    assert resultat == (CfoIntent.CASH_POSITION, 0.7)


def test_reponse_llm_non_conforme_leve_intent_classification_error():
    llm = MockLLMProvider(responses=[{"intent": "NOT_A_REAL_INTENT", "confidence": 0.5}])

    with pytest.raises(IntentClassificationError):
        classify_intent("Question quelconque", llm_provider=llm)


def test_panne_llm_leve_intent_classification_error():
    llm = MockLLMProvider(responses=[TimeoutError("panne simulee")])

    with pytest.raises(IntentClassificationError):
        classify_intent("Question quelconque", llm_provider=llm)


def test_le_prompt_transmet_bien_la_question_de_lutilisateur():
    llm = MockLLMProvider(responses=[{"intent": "GENERAL_FINANCIAL", "confidence": 0.6}])

    classify_intent("Pourquoi ma tresorerie baisse ?", llm_provider=llm)

    assert llm.calls[0]["user_text"] == "Pourquoi ma tresorerie baisse ?"
