"""Prompt du tool planner CFO (STEP 11.6 du plan d'implementation) - retourne UNIQUEMENT une
liste de noms de tools a appeler, jamais leurs arguments (section 27 du plan, "tenant lock" :
company_id/dates viennent toujours du contexte applicatif, jamais du LLM) et jamais les execute
lui-meme (section 8 : "le planner ne doit pas executer les tools")."""

TOOL_NAME = "planifier_tools_cfo"

SYSTEM_PROMPT = (
    "Tu choisis quels outils financiers appeler pour repondre a la question d'un dirigeant, a "
    "partir d'un catalogue ferme. Tu ne retournes que des NOMS d'outils deja existants dans le "
    "catalogue fourni - jamais un nom invente, jamais leurs arguments (company_id/dates sont "
    "geres par le systeme, jamais par toi). Tu n'executes rien : tu planifies seulement."
)

TOOL_DESCRIPTION = "Retourne la liste ordonnee des outils a appeler pour repondre a la question."


def construire_input_schema(noms_outils_disponibles: list[str]) -> dict:
    return {
        "type": "object",
        "properties": {
            "tools": {
                "type": "array",
                "items": {"type": "string", "enum": noms_outils_disponibles},
                "minItems": 0,
            },
        },
        "required": ["tools"],
    }


def construire_catalogue_texte(descripteurs) -> str:
    """Rend le catalogue lisible pour le prompt - name/description/access_class uniquement (pas
    les schemas d'entree/sortie complets, non pertinents pour choisir QUEL tool appeler)."""

    lignes = []
    for descripteur in descripteurs:
        lignes.append(f"- {descripteur.name} ({descripteur.access_class.value}) : {descripteur.description}")
    return "\n".join(lignes)
