"""Prompt du classifieur d'intention CFO (STEP 11.5 du plan d'implementation) - demande
UNIQUEMENT une intention parmi une liste fermee et une confiance, jamais une reponse finale
(section 7 du plan : "pas de reponse finale"). Le LLM ne choisit jamais quels tools appeler ici -
c'est le role du planner (app/cfo/prompts/planner.py), une etape separee."""

from app.schemas.cfo import CfoIntent

TOOL_NAME = "classifier_intention_cfo"

SYSTEM_PROMPT = (
    "Tu classes la question financiere d'un dirigeant dans une categorie fermee. Tu ne repondes "
    "jamais a la question ici, tu ne choisis jamais de tool - tu identifies uniquement de quoi "
    "parle la question et a quel point tu en es sur."
)

TOOL_DESCRIPTION = "Retourne l'intention (categorie) de la question et un niveau de confiance."


def construire_input_schema() -> dict:
    return {
        "type": "object",
        "properties": {
            "intent": {"type": "string", "enum": [i.value for i in CfoIntent]},
            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        },
        "required": ["intent", "confidence"],
    }
