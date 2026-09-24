"""Tool policy du CFO (STEP 11, section 6 du plan d'implementation) - deterministe, jamais laissee
au LLM. Regle : READ -> autorise, SIMULATION -> autorise, WRITE -> approbation humaine obligatoire,
sans aucune exception (section 38 du plan : "aucune exception"). Le LLM ne decide jamais seul
qu'une operation d'ecriture est autorisee - c'est cette policy, et uniquement elle, qui en decide."""

from app.agent.tools.catalog import obtenir_descripteur
from app.schemas.cfo import ToolAccessClass


class UnknownToolError(ValueError):
    """Leve quand un nom de tool ne correspond a aucun descripteur du catalogue - jamais autorise
    par defaut (fail-closed), jamais une classification devinee pour un tool inconnu."""


def classifier_outil(nom_outil: str) -> ToolAccessClass:
    descripteur = obtenir_descripteur(nom_outil)
    if descripteur is None:
        raise UnknownToolError(f"Tool inconnu du catalogue CFO : {nom_outil}")
    return descripteur.access_class


def approbation_requise(nom_outil: str) -> bool:
    """Vrai uniquement pour un tool WRITE - jamais pour READ/SIMULATION. Fail-closed : un tool
    inconnu du catalogue leve UnknownToolError plutot que d'etre silencieusement autorise."""

    return classifier_outil(nom_outil) is ToolAccessClass.WRITE
