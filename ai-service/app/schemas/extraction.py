from datetime import date
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field


class TypeDocumentDetecte(str, Enum):
    """Typage du document (Phase 1 du raisonnement de l'agent d'extraction) : determine avant
    l'extraction des champs, sur la seule base de l'analyse visuelle globale."""

    FACTURE_NORMALISEE = "FACTURE_NORMALISEE"
    RECIPISSE_MOBILE_MONEY = "RECIPISSE_MOBILE_MONEY"
    TICKET_CAISSE = "TICKET_CAISSE"
    FACTURE_PROFORMA = "FACTURE_PROFORMA"
    INEXPLOITABLE = "INEXPLOITABLE"


class OcrResult(BaseModel):
    """Sur-ensemble retro-compatible de com.example.comptable.document.ocr.OcrResult (record
    Java) : les 7 champs d'origine (montant_ht -> confiance) sont un miroir exact et gardent le
    meme sens ; tous les autres champs sont additionnels a ce microservice (utiles pour le
    payload d'ingestion WhatsApp, cf. app/schemas/ingestion.py, et pour le typage/l'auto-critique
    de l'agent d'extraction), absents du record Java. Cote Spring Boot, la deserialisation
    Jackson doit ignorer les champs inconnus (meme convention que WhatsAppWebhookPayload) pour
    rester compatible si ce resultat est un jour renvoye tel quel a `/extraction/document`.

    Alias camelCase pour matcher la convention Jackson du record Java d'origine ; FastAPI
    serialise les reponses par alias par defaut (response_model_by_alias).
    """

    montant_ht: Decimal | None = Field(default=None, alias="montantHt")
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal | None = Field(default=None, alias="montantTtc")
    date_piece: date | None = Field(default=None, alias="datePiece")
    reussite: bool
    message: str | None = None
    confiance: float

    fournisseur: str | None = None
    numero_facture: str | None = Field(default=None, alias="numeroFacture")
    devise: str = "XOF"
    mode_paiement: str | None = Field(default=None, alias="modePaiement")

    # Champs Phase 1 (typage) / Phase 2 etendue (UEMOA/CEMAC) du raisonnement en 4 phases.
    type_document: TypeDocumentDetecte | None = Field(default=None, alias="typeDocument")
    nif_rccm: str | None = Field(default=None, alias="nifRccm")
    reference_transaction: str | None = Field(default=None, alias="referenceTransaction")

    model_config = {"populate_by_name": True}

    @classmethod
    def echec(cls, message: str) -> "OcrResult":
        return cls(reussite=False, message=message, confiance=0.0)
