"""Prompt du node analyze du CFO (STEP 11.9/11.24 du plan d'implementation) - le system prompt
impose ici est repris quasi textuellement de la section 24 du plan ("CREER UN SYSTEM PROMPT CFO
DEDIE"). Demande uniquement du texte narratif (summary/analysis/risks/recommendations/
missing_data), JAMAIS un chiffre, jamais un fact - les facts sont deja construits par
FinancialFactExtractor (STEP 11.8) et transmis en lecture seule dans le prompt utilisateur."""

TOOL_NAME = "analyser_situation_financiere_cfo"

SYSTEM_PROMPT = (
    "You are an AI CFO assistant.\n\n"
    "Use tools to obtain financial facts.\n\n"
    "Never invent financial facts.\n\n"
    "Never override tool results with user-provided contradictory numbers.\n\n"
    "Distinguish actual, expected, predicted and scenario data.\n\n"
    "Never calculate a metric locally when a trusted financial capability exists.\n\n"
    "State explicitly when data is unavailable.\n\n"
    "Never execute write operations without authorization and approval.\n\n"
    "Recommendations must be grounded in retrieved facts.\n\n"
    "Les facts financiers te sont deja fournis (tu ne les recalcules jamais). Chaque "
    "recommandation doit citer les noms des facts (supporting_facts) qui la justifient. Si une "
    "information necessaire est absente des facts recus, liste-la dans missing_data plutot que "
    "de deviner."
)

TOOL_DESCRIPTION = (
    "Retourne une interpretation structuree de la situation financiere : resume, points "
    "d'analyse, risques, recommandations groundees, et donnees manquantes."
)


def construire_input_schema() -> dict:
    return {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "analysis": {"type": "array", "items": {"type": "string"}},
            "risks": {"type": "array", "items": {"type": "string"}},
            "recommendations": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "priority": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]},
                        "action": {"type": "string"},
                        "reason": {"type": "string"},
                        "expected_impact": {"type": ["string", "null"]},
                        "confidence": {"type": ["number", "null"], "minimum": 0, "maximum": 1},
                        "supporting_facts": {"type": "array", "items": {"type": "string"}},
                    },
                    "required": ["priority", "action", "reason", "supporting_facts"],
                },
            },
            "missing_data": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["summary", "analysis", "risks", "recommendations", "missing_data"],
    }
