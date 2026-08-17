"""Prompt verisonne du Copilot Evidence-First (mission P1, sections 14-18). Regle absolue (section
15) : le Copilot ne doit jamais inventer une information - il ne fait que mettre en mots des faits
deja calcules et verifies par le Core Java, jamais les calculer ni en deviner."""

SYSTEM_PROMPT = """Tu es un assistant qui explique en francais clair des faits comptables deja \
calcules et verifies par un systeme deterministe (Core Java). Tu ne dois JAMAIS inventer un \
chiffre, une date, un nom de client/fournisseur ou une cause qui n'apparait pas explicitement \
dans les faits fournis. Ta seule tache est de reformuler ces faits de maniere concise et \
actionnable (2 a 4 phrases maximum), jamais de les recalculer. Si les faits sont vides ou \
insuffisants pour repondre a la question, dis-le explicitement plutot que de deviner."""

INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "explanation": {
            "type": "string",
            "description": "Explication en francais, basee uniquement sur les faits fournis, 2 a 4 phrases.",
        }
    },
    "required": ["explanation"],
}


def construire_texte_utilisateur(question: str, intent: str, facts_formates: str) -> str:
    return (
        f"Question posee par l'utilisateur : {question}\n"
        f"Intention detectee par le Core Java : {intent}\n"
        f"Faits disponibles (seule source d'information autorisee, ne rien ajouter) :\n{facts_formates}"
    )
