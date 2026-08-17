from datetime import date
from decimal import Decimal

from pydantic import BaseModel, Field

from app.schemas.extraction import OcrResult
from app.schemas.fraude import AlerteFraude


class DonneesExtraites(BaseModel):
    """Sous-ensemble de OcrResult effectivement utile a Spring Boot pour pre-remplir la piece :
    pas de `reussite`/`message` (redondants avec confiance=0.0 + alertes vides côté payload
    parent), qui restent des details d'implementation de l'appel VLM. Alias camelCase pour
    matcher la convention Jackson du reste des DTOs Java (ex: document.ocr.OcrResult)."""

    fournisseur: str | None = None
    montant_ht: Decimal | None = Field(default=None, alias="montantHt")
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal | None = Field(default=None, alias="montantTtc")
    devise: str = "XOF"
    mode_paiement: str | None = Field(default=None, alias="modePaiement")
    date_piece: date | None = Field(default=None, alias="datePiece")
    confiance: float

    model_config = {"populate_by_name": True}

    @classmethod
    def depuis_ocr_result(cls, resultat: OcrResult) -> "DonneesExtraites":
        return cls(
            fournisseur=resultat.fournisseur,
            montant_ht=resultat.montant_ht,
            montant_tva=resultat.montant_tva,
            montant_ttc=resultat.montant_ttc,
            devise=resultat.devise,
            mode_paiement=resultat.mode_paiement,
            date_piece=resultat.date_piece,
            confiance=resultat.confiance,
        )


class WhatsAppDocumentPayload(BaseModel):
    """Payload unifie envoye a Spring Boot (`POST /api/v1/internal/whatsapp/documents`,
    champ `metadata` du multipart, a cote du fichier original) une fois l'extraction VLM et
    l'analyse de fraude visuelle terminees pour une piece recue par WhatsApp. Spring Boot n'a
    plus qu'a resoudre `client_phone` -> entreprise, verifier l'idempotence sur `message_id`,
    et creer le Document avec ces champs deja calcules."""

    client_phone: str = Field(alias="clientPhone")
    message_id: str = Field(alias="messageId")
    donnees: DonneesExtraites
    alertes_fraude: list[AlerteFraude] = Field(default_factory=list, alias="alertesFraude")

    model_config = {"populate_by_name": True}
