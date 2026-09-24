"""Prompt du node analyze_financial_health (STEP 8.9) - demande uniquement du texte narratif
(signals/risks/positive_factors/priority_actions/data_completeness_notes), JAMAIS un statut
(deja decide par app/finance/health_policy.py, deterministe) ni un chiffre recalcule. Contrainte
anti-hallucination explicite (section 28 du plan) : le LLM ne doit jamais inventer un ratio (DSO,
cash runway, croissance) qui ne lui a pas ete fourni dans les faits."""

TOOL_NAME = "evaluer_sante_financiere"

SYSTEM_PROMPT = (
    "Tu interpretes une situation financiere a partir de faits deja calcules et verifies (jamais "
    "par toi) : revenue, expenses, profit, cash, receivables, payables. Le niveau de sante "
    "financiere (status) est deja decide par un systeme deterministe et t'est fourni - tu ne dois "
    "jamais le recalculer ni le contredire, seulement l'expliquer avec les faits recus. "
    "Tu ne dois JAMAIS citer un chiffre qui ne t'a pas ete explicitement fourni : n'invente "
    "jamais un DSO (delai moyen de recouvrement), un cash runway (nombre de mois de survie), un "
    "taux de croissance, ou toute autre metrique non presente dans les faits. Si une information "
    "utile manque pour juger un aspect precis, dis-le explicitement dans "
    "data_completeness_notes plutot que de deviner."
)

TOOL_DESCRIPTION = (
    "Retourne une interpretation structuree de la situation financiere : signaux observes, "
    "risques, facteurs positifs, actions prioritaires recommandees, et toute donnee manquante "
    "empechant un jugement plus complet."
)


def construire_input_schema() -> dict:
    return {
        "type": "object",
        "properties": {
            "signals": {"type": "array", "items": {"type": "string"}},
            "risks": {"type": "array", "items": {"type": "string"}},
            "positive_factors": {"type": "array", "items": {"type": "string"}},
            "priority_actions": {"type": "array", "items": {"type": "string"}},
            "data_completeness_notes": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["signals", "risks", "positive_factors", "priority_actions"],
    }
