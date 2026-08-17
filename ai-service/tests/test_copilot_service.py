from app.schemas.copilot import CopilotExplainRequest
from app.services import copilot_service
from app.services.mock_llm_provider import MockLLMProvider


def _requete(**overrides) -> CopilotExplainRequest:
    defaults = dict(
        question="Pourquoi ce dossier n'est-il pas pret ?",
        intent="POURQUOI_DOSSIER_PAS_PRET",
        facts={"scoreGlobal": 82, "findingsCritiques": 1, "findingsImportants": 2},
    )
    defaults.update(overrides)
    return CopilotExplainRequest(**defaults)


def test_reformule_les_faits_fournis_par_le_provider():
    provider = MockLLMProvider(responses=[{"explanation": "Le dossier est a 82% a cause d'un finding critique."}])

    reponse = copilot_service.expliquer(_requete(), llm_provider=provider)

    assert reponse.explanation == "Le dossier est a 82% a cause d'un finding critique."
    assert len(provider.calls) == 1
    assert "82" in provider.calls[0]["user_text"]
    assert provider.calls[0]["tool_name"] == "expliquer_dossier"


def test_degrade_proprement_quand_le_llm_est_indisponible():
    provider = MockLLMProvider(responses=[TimeoutError("panne simulee")])

    reponse = copilot_service.expliquer(_requete(), llm_provider=provider)

    assert "indisponible" in reponse.explanation.lower()


def test_degrade_proprement_quand_la_reponse_llm_est_invalide():
    provider = MockLLMProvider(responses=[{"autre_champ": "valeur inattendue"}])

    reponse = copilot_service.expliquer(_requete(), llm_provider=provider)

    assert "indisponible" in reponse.explanation.lower()


def test_aucun_fait_disponible_est_signale_dans_le_texte_envoye():
    provider = MockLLMProvider(responses=[{"explanation": "Aucune donnee disponible."}])

    copilot_service.expliquer(_requete(facts={}), llm_provider=provider)

    assert "aucun fait disponible" in provider.calls[0]["user_text"]


def test_mock_par_defaut_repond_sans_confondre_avec_le_matching():
    # Sans reponses scriptees : le mock generique (tool_name != "proposer_rapprochement") doit
    # repondre avec un champ "explanation" exploitable, jamais la forme du Universal Matching Engine.
    provider = MockLLMProvider()

    reponse = copilot_service.expliquer(_requete(), llm_provider=provider)

    assert reponse.explanation
    assert "indisponible" not in reponse.explanation.lower()
