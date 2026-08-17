"""Tests du MockLLMProvider (mission "finaliser le matching IA sans API Anthropic disponible") :
les 7 scenarios minimums de la section 5, plus la preuve que le pipeline complet
(matching_engine.analyze) fonctionne de bout en bout sans aucune cle Anthropic et que le mock ne
contourne jamais les garde-fous existants (section 6/7)."""

from datetime import date
from decimal import Decimal

import pytest

from app.schemas.matching import MatchDecision, MatchingCandidate, MatchingContext, MatchingRequest, MatchingSource, SourceType
from app.services import matching_engine
from app.services.matching_scoring import calculer_scores
from app.services.mock_llm_provider import MockLLMProvider


def _source(**overrides) -> MatchingSource:
    defaults = dict(
        type=SourceType.BANK_TRANSACTION,
        id="tx-1",
        date=date(2026, 6, 18),
        amount=Decimal("-485000"),
        currency="XOF",
        label="VIR ABC SARL FAC452",
        counterparty="ABC SARL",
    )
    defaults.update(overrides)
    return MatchingSource(**defaults)


def _candidat(id_, **overrides) -> MatchingCandidate:
    defaults = dict(
        type=SourceType.SUPPLIER_INVOICE,
        id=id_,
        date=date(2026, 6, 16),
        amount=Decimal("485000"),
        currency="XOF",
        counterparty="ABC SARL",
        status="UNPAID",
    )
    defaults.update(overrides)
    return MatchingCandidate(**defaults)


def _appeler_le_mock(source: MatchingSource, candidats: list[MatchingCandidate], provider: MockLLMProvider) -> dict:
    """Reutilise le formatage reel de matching_engine._appeler_llm (le meme code qu'en
    production) plutot que de reconstruire un user_text a la main : ce test casse si le format du
    prompt change sans que MockLLMProvider soit mis a jour en consequence."""
    avec_scores = [(c, calculer_scores(source, c)) for c in candidats]
    return matching_engine._appeler_llm(provider, source, avec_scores, MatchingContext())


# --- Les 7 scenarios minimums (section 5), pilotes par mot-cle dans le texte envoye au provider ---


def test_cas1_match_propose_par_defaut_sans_mot_cle():
    candidat = _candidat("fac-452")
    resultat = _appeler_le_mock(_source(), [candidat], MockLLMProvider())

    assert resultat["decision"] == "MATCH_PROPOSED"
    assert resultat["confidence"] == 0.97
    assert resultat["matches"][0]["candidateId"] == "fac-452"
    assert resultat["matches"][0]["candidateType"] == "SUPPLIER_INVOICE"
    assert resultat["requiresHumanReview"] is False


def test_cas2_ambiguous_match_avec_deux_candidats():
    candidat_a = _candidat("fac-452")
    candidat_b = _candidat("fac-501")
    resultat = _appeler_le_mock(
        _source(label="VIR ABC SARL MOCK_AMBIGUOUS"), [candidat_a, candidat_b], MockLLMProvider()
    )

    assert resultat["decision"] == "AMBIGUOUS_MATCH"
    assert len(resultat["matches"]) == 2
    assert resultat["requiresHumanReview"] is True


def test_cas3_no_match():
    candidat = _candidat("fac-452")
    resultat = _appeler_le_mock(_source(label="VIR INCONNU MOCK_NO_MATCH"), [candidat], MockLLMProvider())

    assert resultat["decision"] == "NO_MATCH"
    assert resultat["matches"] == []


def test_cas4_partial_match():
    candidat = _candidat("fac-452", amount=Decimal("500000"))
    resultat = _appeler_le_mock(
        _source(label="VIR ABC SARL MOCK_PARTIAL", amount=Decimal("-300000")), [candidat], MockLLMProvider()
    )

    assert resultat["decision"] == "PARTIAL_MATCH"
    assert resultat["matches"][0]["candidateId"] == "fac-452"


def test_cas5_json_invalide():
    candidat = _candidat("fac-452")
    with pytest.raises(ValueError):
        _appeler_le_mock(_source(label="VIR ABC SARL MOCK_INVALID_JSON"), [candidat], MockLLMProvider())


def test_cas6_id_halluciene_absent_des_candidats_envoyes():
    candidat = _candidat("fac-452")
    resultat = _appeler_le_mock(_source(label="VIR ABC SARL MOCK_HALLUCINATE"), [candidat], MockLLMProvider())

    assert resultat["matches"][0]["candidateId"] != "fac-452"


def test_cas7_timeout_provider():
    candidat = _candidat("fac-452")
    with pytest.raises(TimeoutError):
        _appeler_le_mock(_source(label="VIR ABC SARL MOCK_TIMEOUT"), [candidat], MockLLMProvider())


# --- Reponses scriptees (mode utilise par tests/test_matching_engine.py) ---


def test_reponses_scriptees_consommees_dans_l_ordre():
    provider = MockLLMProvider(responses=[{"decision": "NO_MATCH"}, {"decision": "MATCH_PROPOSED"}])
    kwargs = dict(system_prompt="s", user_text="u", tool_name="t", tool_description="d", input_schema={})

    premiere = provider.call_structured(**kwargs)
    seconde = provider.call_structured(**kwargs)

    assert premiere["decision"] == "NO_MATCH"
    assert seconde["decision"] == "MATCH_PROPOSED"
    assert len(provider.calls) == 2


def test_exception_scriptee_est_levee():
    provider = MockLLMProvider(responses=[TimeoutError("panne simulee")])
    with pytest.raises(TimeoutError):
        provider.call_structured(system_prompt="s", user_text="u", tool_name="t", tool_description="d", input_schema={})


# --- Pipeline complet sans aucune cle Anthropic : le mock ne contourne jamais les garde-fous ---


def test_end_to_end_ambiguous_via_matching_engine_analyze():
    """Section 7 : Spring Boot -> AiServiceClient -> ai-service -> MockLLMProvider -> resultat
    structure -> validation Python, sans aucune reponse scriptee ni cle Anthropic - le moteur
    choisit lui-meme le mock par mot-cle. Les deux candidats sont scores de facon identique pour
    garantir que le court-circuit deterministe ne s'applique pas (ecart de score nul < seuil de
    court-circuit) et que l'appel passe bien par le LLM (mocke)."""
    candidat_a = _candidat("fac-452")
    candidat_b = _candidat("fac-501")
    requete = MatchingRequest(
        source=_source(label="VIR PAIEMENT MOCK_AMBIGUOUS"),
        candidates=[candidat_a, candidat_b],
    )

    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider())

    assert reponse.decision == MatchDecision.AMBIGUOUS_MATCH
    assert reponse.requires_human_review is True
    assert len(reponse.matches) == 2


def test_le_mock_ne_contourne_pas_le_garde_fou_anti_hallucination():
    """Section 6 : meme avec le mock, un candidateId invente doit etre rejete par
    matching_engine._valider_et_construire exactement comme avec un vrai appel Anthropic - le
    garde-fou vit dans le moteur, pas dans le provider."""
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(label="VIR ABC SARL MOCK_HALLUCINATE"), candidates=[candidat])

    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider())

    assert reponse.decision == MatchDecision.AMBIGUOUS_MATCH
    assert reponse.matches == []
    assert reponse.requires_human_review is True
    assert "invalid_ai_output" in reponse.reasons


def test_ai_provider_par_defaut_est_mock_sans_configuration():
    """Section 18 (critere de reussite) : sans AUCUNE configuration, get_settings().ai_provider
    vaut deja "mock" - le systeme est utilisable des le premier lancement sans cle Anthropic."""
    from app.core.config import get_settings

    assert get_settings().ai_provider == "mock"
