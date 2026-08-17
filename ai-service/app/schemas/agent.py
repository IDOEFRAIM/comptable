from datetime import date
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field

from app.schemas.extraction import TypeDocumentDetecte

# --- Reponse finale de l'agent comptable autonome (agent_orchestrator.py) ---
# Miroir exact du contrat JSON specifie par l'utilisateur (statutAgent/typeDocument/
# donneesExtraites/alertesFraude/metadataAgent) : c'est le schema d'entree du "tool" force
# repondre_final, jamais du texte libre reconstruit apres coup.


class StatutAgent(str, Enum):
    SUCCES = "SUCCES"
    SUCCES_AVEC_RESERVES = "SUCCES_AVEC_RESERVES"
    ECHEC_SAISIE_MANUELLE = "ECHEC_SAISIE_MANUELLE"
    # Introduit par le protocole (etape 4) pour le cas ou une clarification a ete demandee au
    # client (demander_clarification_client) : distinct d'ECHEC_SAISIE_MANUELLE, qui signale un
    # document lui-meme inexploitable/non comptabilisable plutot qu'une info manquante.
    ATTENTE_CLIENT = "ATTENTE_CLIENT"


class ModePaiementAgent(str, Enum):
    WAVE = "WAVE"
    ORANGE_MONEY = "ORANGE_MONEY"
    MTN_MOMO = "MTN_MOMO"
    ESPECES = "ESPECES"
    VIREMENT = "VIREMENT"
    AUTRE = "AUTRE"


class DeviseAgent(str, Enum):
    XOF = "XOF"
    XAF = "XAF"
    EUR = "EUR"
    USD = "USD"


class CodeAlerteAgent(str, Enum):
    FORMAT_VISUEL_SUSPECT = "FORMAT_VISUEL_SUSPECT"
    LOGO_ALTERE = "LOGO_ALTERE"
    POLICE_NON_UNIFORME = "POLICE_NON_UNIFORME"
    REFERENCE_ABSENTE = "REFERENCE_ABSENTE"
    ANOMALIE_MATHEMATIQUE = "ANOMALIE_MATHEMATIQUE"
    DOCUMENT_NON_COMPTABLE = "DOCUMENT_NON_COMPTABLE"


class GraviteAgent(str, Enum):
    CRITIQUE = "CRITIQUE"
    MOYEN = "MOYEN"


class DonneesExtraitesAgent(BaseModel):
    """Montants en chaines (pas Decimal) : c'est le contrat explicite donne par l'utilisateur
    ("montantHt": "string ou null") pour ce schema precis, distinct de OcrResult (Decimal) qui
    reste le contrat des endpoints extraction/document et de l'ingestion WhatsApp."""

    fournisseur: str | None = None
    nif_rccm: str | None = Field(default=None, alias="nifRccm")
    date_piece: date | None = Field(default=None, alias="datePiece")
    montant_ht: str | None = Field(default=None, alias="montantHt")
    montant_tva: str | None = Field(default=None, alias="montantTva")
    montant_ttc: str | None = Field(default=None, alias="montantTtc")
    devise: DeviseAgent = DeviseAgent.XOF
    mode_paiement: ModePaiementAgent | None = Field(default=None, alias="modePaiement")
    reference_transaction: str | None = Field(default=None, alias="referenceTransaction")
    confiance: float = 0.0

    model_config = {"populate_by_name": True}


class AlerteFraudeAgent(BaseModel):
    code: CodeAlerteAgent
    motif: str
    gravite: GraviteAgent
    action_recommandee: str = Field(alias="actionRecommandee")

    model_config = {"populate_by_name": True}


class MetadataAgent(BaseModel):
    raisonnement_critique: str = Field(alias="raisonnementCritique")
    necessite_validation_humaine: bool = Field(alias="necessiteValidationHumaine")

    model_config = {"populate_by_name": True}


class AnalyseAgentResponse(BaseModel):
    statut_agent: StatutAgent = Field(alias="statutAgent")
    type_document: TypeDocumentDetecte = Field(alias="typeDocument")
    donnees_extraites: DonneesExtraitesAgent = Field(alias="donneesExtraites")
    alertes_fraude: list[AlerteFraudeAgent] = Field(default_factory=list, alias="alertesFraude")
    metadata_agent: MetadataAgent = Field(alias="metadataAgent")

    model_config = {"populate_by_name": True}


# --- Reponses des outils Java (agent/tools/, cf. java_tools_client.py) ---
# Montants en Decimal ici (pas en string) : ce sont de vraies reponses JSON produites par
# Jackson cote Java, qui serialise BigDecimal comme un nombre JSON, pas une chaine.


class PieceHistorique(BaseModel):
    date_piece: date | None = Field(default=None, alias="datePiece")
    montant_ttc: Decimal | None = Field(default=None, alias="montantTtc")

    model_config = {"populate_by_name": True}


class DoublonVerificationResponse(BaseModel):
    doublon_potentiel_detecte: bool = Field(alias="doublonPotentielDetecte")
    pieces_similaires: list[PieceHistorique] = Field(default_factory=list, alias="piecesSimilaires")

    model_config = {"populate_by_name": True}


class HistoriqueFournisseurResponse(BaseModel):
    nombre_pieces: int = Field(alias="nombrePieces")
    montant_moyen: Decimal | None = Field(default=None, alias="montantMoyen")
    montant_min: Decimal | None = Field(default=None, alias="montantMin")
    montant_max: Decimal | None = Field(default=None, alias="montantMax")
    dernieres_pieces: list[PieceHistorique] = Field(default_factory=list, alias="dernieresPieces")

    model_config = {"populate_by_name": True}


class DemandeClarificationResponse(BaseModel):
    demande_id: str = Field(alias="demandeId")
    statut: str

    model_config = {"populate_by_name": True}
