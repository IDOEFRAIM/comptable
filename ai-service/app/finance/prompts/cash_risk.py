"""Prompt du node analyze_cash_risk (STEP 9.16) - demande uniquement du texte narratif
(main_drivers/positive_factors/recommended_actions/data_completeness_notes), JAMAIS un statut, une
semaine critique ou un solde minimum (deja decides par app/finance/cash_risk_policy.py,
deterministe, a partir du CashForecast recu de Java). Meme contrainte anti-hallucination que
app/finance/prompts/health.py (STEP 8, section 30 du plan) : le LLM ne doit jamais inventer une
transaction, un solde ou un evenement de liquidite absent des faits recus."""

TOOL_NAME = "evaluer_risque_de_tresorerie"

SYSTEM_PROMPT = (
    "Tu interpretes un forecast de tresorerie sur plusieurs semaines (KNOWN/EXPECTED/PREDICTED) "
    "et ses evenements de liquidite, tous deja calcules et verifies (jamais par toi) par un "
    "moteur deterministe. Le niveau de risque (status), la semaine critique et le solde minimum "
    "projete te sont deja fournis - tu ne dois jamais les recalculer ni les contredire, "
    "seulement les expliquer avec les chiffres et evenements recus. "
    "Tu ne dois JAMAIS citer un solde de cloture, une semaine, un montant ou un evenement de "
    "liquidite qui ne t'a pas ete explicitement fourni dans les faits - n'invente jamais une "
    "transaction absente. Si une information utile manque pour expliquer un aspect precis, "
    "dis-le explicitement dans data_completeness_notes plutot que de deviner."
)

TOOL_DESCRIPTION = (
    "Retourne une interpretation structuree du risque de tresorerie : facteurs expliquant la "
    "trajectoire du cash (drivers), facteurs positifs, actions prioritaires recommandees, et "
    "toute donnee manquante empechant un jugement plus complet."
)


def construire_input_schema() -> dict:
    return {
        "type": "object",
        "properties": {
            "main_drivers": {"type": "array", "items": {"type": "string"}},
            "positive_factors": {"type": "array", "items": {"type": "string"}},
            "recommended_actions": {"type": "array", "items": {"type": "string"}},
            "data_completeness_notes": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["main_drivers", "positive_factors", "recommended_actions"],
    }
