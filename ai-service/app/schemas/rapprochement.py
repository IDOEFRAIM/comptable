from datetime import date
from decimal import Decimal
from uuid import UUID

from pydantic import BaseModel, Field

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class LigneReleve(BaseModel):
    id: UUID
    date_operation: date = Field(alias="dateOperation")
    montant: Decimal  # signe : positif = entree (argent recu), negatif = sortie (argent envoye)
    libelle: str

    model_config = _CAMEL_CASE_CONFIG


class LigneEcritureCandidate(BaseModel):
    """Miroir de comptabilite.domain.LigneEcriture : debit/credit distincts (un seul des deux
    est non nul), pas un montant signe unique — necessaire pour reproduire exactement le
    controle de direction de MatchingSemantiqueService.directionCorrespond."""

    id: UUID
    date_ecriture: date = Field(alias="dateEcriture")
    debit: Decimal
    credit: Decimal
    libelle: str
    tiers: str | None = None

    model_config = _CAMEL_CASE_CONFIG


class RapprochementSemantiqueRequest(BaseModel):
    lignes_non_rapprochees: list[LigneReleve] = Field(alias="lignesNonRapprochees")
    candidats: list[LigneEcritureCandidate]

    model_config = _CAMEL_CASE_CONFIG


class AppariementSemantique(BaseModel):
    """Miroir de agent.rapprochement.AppariementSemantique.

    Deja filtre par le guard-rail (voir app.services.rapprochement_service) : seuls les
    appariements avec confiance > seuil, ids presents dans la requete, direction du montant
    coherente (debit/credit), et sans reemploi d'une meme ligne/ecriture, sont retournes.
    """

    ligne_releve_id: UUID = Field(alias="ligneReleveId")
    ligne_ecriture_id: UUID = Field(alias="ligneEcritureId")
    confiance: float
    justification: str

    model_config = _CAMEL_CASE_CONFIG
