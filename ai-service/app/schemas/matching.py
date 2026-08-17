import datetime
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel, Field

# Plusieurs modeles ci-dessous ont un champ nomme `date` : Pydantic v2 resout les annotations en
# utilisant le namespace de la classe (qui contient alors `date = None`, la valeur du champ) comme
# localns, ce qui masque le type `date` importe si on l'importe nommement (`from datetime import
# date`). Reference qualifiee (`datetime.date`) partout ci-dessous pour l'eviter.

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class SourceType(str, Enum):
    """Type d'un evenement source OU d'un candidat : le meme enum sert aux deux (cf.
    MatchingCandidate) pour permettre par exemple de comparer une transaction bancaire a une
    AUTRE transaction bancaire/Mobile Money (detection de virement interne, cf. MatchType.INTERNAL_TRANSFER),
    pas seulement a des ecritures/factures. Extensible sans casser l'API existante (nouvelles
    valeurs ajoutables plus tard)."""

    BANK_TRANSACTION = "BANK_TRANSACTION"
    MOBILE_MONEY_TRANSACTION = "MOBILE_MONEY_TRANSACTION"
    ACCOUNTING_ENTRY = "ACCOUNTING_ENTRY"
    CUSTOMER_INVOICE = "CUSTOMER_INVOICE"
    SUPPLIER_INVOICE = "SUPPLIER_INVOICE"
    DOCUMENT = "DOCUMENT"


class MatchDecision(str, Enum):
    MATCH_PROPOSED = "MATCH_PROPOSED"
    PARTIAL_MATCH = "PARTIAL_MATCH"
    AMBIGUOUS_MATCH = "AMBIGUOUS_MATCH"
    NO_MATCH = "NO_MATCH"


class MatchType(str, Enum):
    ONE_TO_ONE = "ONE_TO_ONE"
    ONE_TO_MANY = "ONE_TO_MANY"
    MANY_TO_ONE = "MANY_TO_ONE"
    PARTIAL_PAYMENT = "PARTIAL_PAYMENT"
    PAYMENT_WITH_FEES = "PAYMENT_WITH_FEES"
    ADVANCE_PAYMENT = "ADVANCE_PAYMENT"
    INTERNAL_TRANSFER = "INTERNAL_TRANSFER"
    NONE = "NONE"


class HistoricalPayment(BaseModel):
    """Un paiement passe avec ce tiers, fourni en contexte optionnel (MatchingContext.history) :
    signal probabiliste ("ce fournisseur est generalement paye par banque"), jamais une regle
    certaine - cf. matching_prompts.SYSTEM_PROMPT."""

    amount: Decimal
    date: datetime.date | None = None
    method: str | None = None

    model_config = _CAMEL_CASE_CONFIG


class MatchingContext(BaseModel):
    country: str | None = None
    accounting_framework: str | None = Field(default=None, alias="accountingFramework")
    company_id: str | None = Field(default=None, alias="companyId")
    history: list[HistoricalPayment] = Field(default_factory=list)

    model_config = _CAMEL_CASE_CONFIG


class MatchingSource(BaseModel):
    """L'evenement a rapprocher (une transaction bancaire, Mobile Money, une ecriture...)."""

    type: SourceType
    id: str
    date: datetime.date
    amount: Decimal  # signe libre : la semantique du signe depend du type source, pas imposee ici
    currency: str = "XOF"
    label: str | None = None
    reference: str | None = None
    counterparty: str | None = None

    model_config = _CAMEL_CASE_CONFIG


class MatchingCandidate(BaseModel):
    """Un candidat possible pour le rapprochement : ecriture, facture, document, tiers, paiement,
    ou meme une autre transaction (virement interne). `status` reste une chaine libre (pas un
    enum ferme) : sa semantique varie selon `type` (UNPAID/PAID pour une facture, LETTREE pour une
    ecriture...) et ce moteur ne doit pas coder en dur les statuts metier de chaque module Java."""

    type: SourceType
    id: str
    date: datetime.date | None = None
    amount: Decimal | None = None
    currency: str = "XOF"
    label: str | None = None
    reference: str | None = None
    counterparty: str | None = None
    status: str | None = None

    model_config = _CAMEL_CASE_CONFIG


class MatchingRequest(BaseModel):
    source: MatchingSource
    candidates: list[MatchingCandidate]
    context: MatchingContext = Field(default_factory=MatchingContext)

    model_config = _CAMEL_CASE_CONFIG


class MatchProposal(BaseModel):
    candidate_id: str = Field(alias="candidateId")
    candidate_type: SourceType = Field(alias="candidateType")
    score: float

    model_config = _CAMEL_CASE_CONFIG


class MatchingResponse(BaseModel):
    """Reponse du Universal Matching Engine. L'IA PROPOSE (`matches`, `confidence`,
    `requiresHumanReview`) ; elle ne decide, ne valide et ne persiste jamais rien - c'est au
    Core Spring Boot d'appliquer ses propres regles metier sur cette proposition."""

    decision: MatchDecision
    confidence: float
    match_type: MatchType = Field(alias="matchType")
    matches: list[MatchProposal] = Field(default_factory=list)
    reasons: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    requires_human_review: bool = Field(alias="requiresHumanReview")

    model_config = _CAMEL_CASE_CONFIG
