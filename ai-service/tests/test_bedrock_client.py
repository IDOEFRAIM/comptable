import base64

import pytest

from app.core.config import get_settings
from app.services import bedrock_client, groq_client

SCHEMA = {"type": "object", "properties": {"x": {"type": "number"}}}


class FauxBedrock:
    def __init__(self, reponse):
        self.reponse = reponse
        self.appel = None

    def converse(self, **kwargs):
        self.appel = kwargs
        return self.reponse


@pytest.fixture
def reglages(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "vlm_provider", "bedrock")
    monkeypatch.setattr(s, "bedrock_model_id", "modele-test")
    return s


def _reponse(nom="extraire", entree=None):
    return {"output": {"message": {"content": [{"toolUse": {"name": nom, "input": entree or {"x": 1}}}]}}}


def test_call_structured_envoie_image_et_outil_force_et_retourne_l_entree_de_l_outil(reglages, monkeypatch):
    faux = FauxBedrock(_reponse())
    monkeypatch.setattr(bedrock_client, "get_client", lambda: faux)
    image = base64.b64encode(b"octets-image").decode()
    resultat = bedrock_client.call_structured(
        system_prompt="sys", user_text="texte", tool_name="extraire", tool_description="d", input_schema=SCHEMA,
        image_b64=image, image_media_type="image/png",
    )
    assert resultat == {"x": 1}
    assert faux.appel["modelId"] == "modele-test"
    assert faux.appel["toolConfig"]["toolChoice"] == {"tool": {"name": "extraire"}}
    bloc_image = faux.appel["messages"][0]["content"][1]["image"]
    assert bloc_image["format"] == "png" and bloc_image["source"]["bytes"] == b"octets-image"
    assert faux.appel["inferenceConfig"]["temperature"] == 0


def test_reponse_sans_l_outil_attendu_leve_une_erreur(reglages, monkeypatch):
    monkeypatch.setattr(bedrock_client, "get_client", lambda: FauxBedrock({"output": {"message": {"content": [{"text": "bonjour"}]}}}))
    with pytest.raises(RuntimeError):
        bedrock_client.call_structured(system_prompt="s", user_text="t", tool_name="extraire", tool_description="d", input_schema=SCHEMA)


def test_modele_non_defini_leve_une_erreur_claire(reglages, monkeypatch):
    monkeypatch.setattr(reglages, "bedrock_model_id", "")
    with pytest.raises(RuntimeError, match="BEDROCK_MODEL_ID"):
        bedrock_client.call_structured(system_prompt="s", user_text="t", tool_name="n", tool_description="d", input_schema=SCHEMA)


def test_groq_client_delegue_a_bedrock_quand_vlm_provider_bedrock(reglages, monkeypatch):
    monkeypatch.setattr(bedrock_client, "get_client", lambda: FauxBedrock(_reponse(entree={"x": 42})))
    assert groq_client.call_structured(system_prompt="s", user_text="t", tool_name="extraire", tool_description="d", input_schema=SCHEMA) == {"x": 42}
