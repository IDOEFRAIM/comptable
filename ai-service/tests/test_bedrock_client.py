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


def test_la_requete_respecte_le_modele_officiel_de_l_api_converse(reglages, monkeypatch):
    """Valide nos parametres contre le modele de service reel de bedrock-runtime (botocore) : une erreur de forme
    (champ manquant, mauvais type) serait detectee ici, sans appel reseau ni identifiants."""
    import boto3
    from botocore.stub import Stubber

    client = boto3.client("bedrock-runtime", region_name="us-east-1", aws_access_key_id="x", aws_secret_access_key="x")
    schema_reel = {
        "type": "object",
        "properties": {"montant_ht": {"type": ["number", "null"], "description": "d"}, "confiance": {"type": "number"}},
        "required": ["confiance"],
    }
    with Stubber(client) as stub:
        stub.add_response(
            "converse",
            {
                "output": {"message": {"role": "assistant", "content": [{"toolUse": {"toolUseId": "t1", "name": "extraire", "input": {"confiance": 0.9}}}]}},
                "stopReason": "tool_use",
                "usage": {"inputTokens": 1, "outputTokens": 1, "totalTokens": 2},
                "metrics": {"latencyMs": 1},
            },
        )
        monkeypatch.setattr(bedrock_client, "get_client", lambda: client)
        resultat = bedrock_client.call_structured(
            system_prompt="s", user_text="t", tool_name="extraire", tool_description="d", input_schema=schema_reel,
            image_b64=base64.b64encode(b"x").decode(), image_media_type="image/jpeg",
        )
        assert resultat == {"confiance": 0.9}
        stub.assert_no_pending_responses()


def test_le_detail_d_erreur_aws_est_lisible(reglages):
    from botocore.exceptions import ClientError

    erreur = ClientError({"Error": {"Code": "ValidationException", "Message": "The provided model identifier is invalid."}}, "Converse")
    assert bedrock_client._detail_erreur(erreur) == "ValidationException: The provided model identifier is invalid."


def test_modele_qui_refuse_tool_choice_est_rappele_sans_forcer_l_outil(reglages, monkeypatch):
    """Tous les modeles Bedrock (Nova, Llama, Mistral...) ne gerent pas toolChoice : un seul nouvel essai sans lui."""
    from botocore.exceptions import ClientError

    class ClientSansToolChoice(FauxBedrock):
        def __init__(self):
            super().__init__(_reponse(entree={"x": 5}))
            self.essais = []

        def converse(self, **kwargs):
            self.essais.append(kwargs)
            if "toolChoice" in kwargs["toolConfig"]:
                raise ClientError({"Error": {"Code": "ValidationException", "Message": "This model doesn't support the toolConfig.toolChoice.tool field."}}, "Converse")
            return self.reponse

    faux = ClientSansToolChoice()
    monkeypatch.setattr(bedrock_client, "get_client", lambda: faux)
    resultat = bedrock_client.call_structured(system_prompt="s", user_text="t", tool_name="extraire", tool_description="d", input_schema=SCHEMA)
    assert resultat == {"x": 5}
    assert len(faux.essais) == 2 and "toolChoice" not in faux.essais[1]["toolConfig"]


def test_une_autre_erreur_de_validation_n_est_pas_rejouee(reglages, monkeypatch):
    from botocore.exceptions import ClientError

    class Refuse(FauxBedrock):
        def __init__(self):
            super().__init__(None)
            self.essais = 0

        def converse(self, **kwargs):
            self.essais += 1
            raise ClientError({"Error": {"Code": "ValidationException", "Message": "The provided model identifier is invalid."}}, "Converse")

    faux = Refuse()
    monkeypatch.setattr(bedrock_client, "get_client", lambda: faux)
    with pytest.raises(ClientError):
        bedrock_client.call_structured(system_prompt="s", user_text="t", tool_name="n", tool_description="d", input_schema=SCHEMA)
    assert faux.essais == 1
