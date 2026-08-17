from enum import Enum

from pydantic import BaseModel, Field


class NiveauRisqueFraude(str, Enum):
    """Miroir de agent.fraude.NiveauRisqueFraude. L'ordre FAIBLE < MOYEN < CRITIQUE fait foi
    cote Java pour l'agregation "pire alerte gagne" (RapportFraude.depuisAlertes)."""

    FAIBLE = "FAIBLE"
    MOYEN = "MOYEN"
    CRITIQUE = "CRITIQUE"


class TypeDocument(str, Enum):
    """Miroir de document.domain.TypeDocument."""

    FACTURE_ACHAT = "FACTURE_ACHAT"
    FACTURE_VENTE = "FACTURE_VENTE"
    RECU = "RECU"
    RELEVE_BANCAIRE = "RELEVE_BANCAIRE"
    AUTRE = "AUTRE"


class AlerteFraude(BaseModel):
    """Miroir de agent.fraude.AlerteFraude. Ne contient ici que les alertes issues de
    l'analyse visuelle (FORMAT_VISUEL_SUSPECT, FOURNISSEUR_NON_IDENTIFIABLE) : les alertes
    deterministes (ECART_TVA, MONTANT_ROND_SUSPECT, DOUBLON_POTENTIEL) necessitent l'entreprise
    et la base de donnees, et restent calculees cote Spring Boot. Le RapportFraude final
    (score agrege, "pire alerte gagne") est assemble cote Java a partir des deux listes.

    Alias camelCase (actionRecommandee) pour matcher la convention Jackson du record Java
    d'origine ; FastAPI serialise les reponses par alias par defaut (response_model_by_alias),
    donc /fraude/analyse-visuelle expose deja ce nom en JSON."""

    code: str
    motif: str
    gravite: NiveauRisqueFraude
    action_recommandee: str = Field(alias="actionRecommandee")

    model_config = {"populate_by_name": True}
