"""Agent autonome + fournisseur LLM sur Amazon Bedrock (Claude via Converse). Les requetes sont validees
contre le modele de service reel de bedrock-runtime (botocore Stubber) : aucune erreur de forme ne passe,
sans reseau ni identifiants AWS."""

import importlib

import boto3
import pytest
from botocore.stub import Stubber

from app.core.config import get_settings
from app.schemas.agent import DoublonVerificationResponse, StatutAgent
from app.services import agent_orchestrator, bedrock_client
from app.services.bedrock_llm_provider import BedrockLLMProvider

FINAL = {
    "statutAgent": "SUCCES",
    "typeDocument": "FACTURE_NORMALISEE",
    "donneesExtraites": {"fournisseur": "ACME", "montantTtc": "15000", "devise": "XOF", "confiance": 0.9},
    "alertesFraude": [],
    "metadataAgent": {"raisonnementCritique": "RAS", "necessiteValidationHumaine": False},
}

USAGE = {"inputTokens": 1, "outputTokens": 1, "totalTokens": 2}


def _reponse(contenu):
    return {
        "output": {"message": {"role": "assistant", "content": contenu}},
        "stopReason": "tool_use",
        "usage": USAGE,
        "metrics": {"latencyMs": 1},
    }


def _tool_use(identifiant, nom, entree):
    return {"toolUse": {"toolUseId": identifiant, "name": nom, "input": entree}}


@pytest.fixture
def bedrock(monkeypatch):
    reglages = get_settings()
    monkeypatch.setattr(reglages, "ai_provider", "bedrock")
    monkeypatch.setattr(reglages, "bedrock_model_id", "modele-test")
    client = boto3.client("bedrock-runtime", region_name="us-east-1", aws_access_key_id="x", aws_secret_access_key="x")
    monkeypatch.setattr(bedrock_client, "get_client", lambda: client)
    with Stubber(client) as stub:
        yield stub


def test_boucle_agent_sur_bedrock_outil_puis_reponse_finale(bedrock, monkeypatch):
    bedrock.add_response(
        "converse",
        _reponse([{"text": "<scratchpad>doublon ?</scratchpad>"}, _tool_use("tu1", "verifier_doublon_montant", {"montant_ttc": "15000", "date_piece": "2027-01-05"})]),
    )
    bedrock.add_response("converse", _reponse([_tool_use("tu2", "repondre_final", FINAL)]))

    appels_outil = []

    def faux_doublon(entreprise_id, montant, date_piece):
        appels_outil.append((entreprise_id, montant, date_piece))
        return DoublonVerificationResponse(doublonPotentielDetecte=False, piecesSimilaires=[])

    monkeypatch.setattr("app.services.java_tools_client.verifier_doublon_montant", faux_doublon)

    resultat = agent_orchestrator.analyser_piece_autonome("ent-1", b"pas-une-image", "image/jpeg")

    assert resultat.statut_agent == StatutAgent.SUCCES
    assert len(appels_outil) == 1 and appels_outil[0][0] == "ent-1"
    bedrock.assert_no_pending_responses()


def test_le_resultat_d_outil_est_renvoye_en_toolresult_json(bedrock, monkeypatch):
    envoyes = []
    client = bedrock_client.get_client()
    original = client.converse

    def espion(**kwargs):
        envoyes.append(list(kwargs["messages"]))  # copie : la liste est completee apres l'appel
        return original(**kwargs)

    monkeypatch.setattr(client, "converse", espion)
    bedrock.add_response("converse", _reponse([_tool_use("tu1", "consulter_historique_fournisseur", {"fournisseur": "ACME"})]))
    bedrock.add_response("converse", _reponse([_tool_use("tu2", "repondre_final", FINAL)]))
    monkeypatch.setattr(
        "app.services.java_tools_client.consulter_historique_fournisseur",
        lambda *_: (_ for _ in ()).throw(RuntimeError("Java down")),
    )

    agent_orchestrator.analyser_piece_autonome("ent-1", b"x", "image/png")

    dernier = envoyes[1][-1]
    assert dernier["role"] == "user"
    bloc = dernier["content"][0]["toolResult"]
    assert bloc["toolUseId"] == "tu1"
    assert bloc["status"] == "error"
    assert "Java down" in bloc["content"][0]["json"]["erreur"]


def test_texte_libre_relance_le_modele_puis_converge(bedrock):
    bedrock.add_response("converse", _reponse([{"text": "<scratchpad>je reflechis</scratchpad>"}]))
    bedrock.add_response("converse", _reponse([_tool_use("tu1", "repondre_final", FINAL)]))

    resultat = agent_orchestrator.analyser_piece_autonome("ent-1", b"x", "image/png")

    assert resultat.statut_agent == StatutAgent.SUCCES
    bedrock.assert_no_pending_responses()


def test_panne_bedrock_degrade_en_saisie_manuelle(bedrock):
    bedrock.add_client_error("converse", service_error_code="ThrottlingException", service_message="Too many requests")

    resultat = agent_orchestrator.analyser_piece_autonome("ent-1", b"x", "image/png")

    assert resultat.statut_agent == StatutAgent.ECHEC_SAISIE_MANUELLE


def test_modele_non_defini_degrade_en_saisie_manuelle(bedrock, monkeypatch):
    monkeypatch.setattr(get_settings(), "bedrock_model_id", "")

    resultat = agent_orchestrator.analyser_piece_autonome("ent-1", b"x", "image/png")

    assert resultat.statut_agent == StatutAgent.ECHEC_SAISIE_MANUELLE


def test_bedrock_llm_provider_delegue_a_call_structured(monkeypatch):
    vus = {}
    monkeypatch.setattr(bedrock_client, "call_structured", lambda **kw: vus.update(kw) or {"ok": True})

    sortie = BedrockLLMProvider().call_structured(
        system_prompt="s", user_text="t", tool_name="n", tool_description="d", input_schema={"type": "object"}
    )

    assert sortie == {"ok": True}
    assert vus["tool_name"] == "n"


@pytest.mark.parametrize(
    "module",
    [
        "app.services.copilot_service",
        "app.services.matching_engine",
        "app.cfo.analysis",
        "app.cfo.intent",
        "app.cfo.planner",
        "app.finance.health_assessment_service",
        "app.finance.cash_risk_assessment_service",
        "app.accounting.services.classification_service",
    ],
)
def test_ai_provider_bedrock_selectionne_le_fournisseur_bedrock(module, monkeypatch):
    monkeypatch.setattr(get_settings(), "ai_provider", "bedrock")
    assert isinstance(importlib.import_module(module)._get_default_provider(), BedrockLLMProvider)
