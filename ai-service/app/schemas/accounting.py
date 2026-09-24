"""Schemas Pydantic pour le workflow AI Accounting (LangGraph Python, cf. app/agent/accounting/).

Perimetre de ce fichier : le premier "vertical slice" (facture fournisseur uniquement, voir le
plan d'implementation STEP 1-13) - la vente client et les autres AccountingOperationType viendront
une fois ce chemin de bout en bout valide, jamais anticipes ici.

Principe directeur (identique a matching.py/copilot.py) : Python PROPOSE (classification, comptes
suggeres, brouillon d'ecriture) ; seul le Java (AccountingOperationEngine / AccountingAccountResolver)
VALIDE et POSTE reellement - ces schemas ne portent jamais de decision finale, uniquement une
proposition ou le resultat renvoye par Java apres validation/posting.

AccountPurpose, NatureAchat, AccountResolutionStatus, AccountResolutionSource et
StatutOperationProposal sont des miroirs exacts des enums Java correspondants (memes noms de
constantes) - ne jamais ajouter ici une valeur qui n'existe pas cote Java ; la validation reelle
de ce que Python envoie reste faite par Java au moment de l'appel aux capabilities (STEP 4+).
"""

from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from uuid import UUID

from pydantic import BaseModel, Field

_CAMEL_CASE_CONFIG = {"populate_by_name": True}


class AccountPurpose(str, Enum):
    """Miroir exact de com.example.comptable.comptabilite.parametrage.domain.AccountPurpose (23
    valeurs) - le seul vocabulaire que le classifieur IA a le droit d'utiliser pour suggerer un
    compte. Ne jamais inventer une 24e valeur ici sans l'ajouter d'abord cote Java."""

    ACHAT_MARCHANDISE = "ACHAT_MARCHANDISE"
    ACHAT_MATIERE = "ACHAT_MATIERE"
    ACHAT_CONSOMMABLE = "ACHAT_CONSOMMABLE"
    ACHAT_SERVICE = "ACHAT_SERVICE"
    ACHAT_FRAIS_ACCESSOIRE = "ACHAT_FRAIS_ACCESSOIRE"
    VENTE_MARCHANDISE = "VENTE_MARCHANDISE"
    VENTE_PRODUIT = "VENTE_PRODUIT"
    VENTE_SERVICE = "VENTE_SERVICE"
    TVA_COLLECTEE = "TVA_COLLECTEE"
    TVA_RECUPERABLE = "TVA_RECUPERABLE"
    STOCK_MARCHANDISE = "STOCK_MARCHANDISE"
    STOCK_MATIERE = "STOCK_MATIERE"
    VARIATION_STOCK = "VARIATION_STOCK"
    CLIENT = "CLIENT"
    FOURNISSEUR = "FOURNISSEUR"
    BANQUE = "BANQUE"
    CAISSE = "CAISSE"
    IMMOBILISATION_CORPORELLE = "IMMOBILISATION_CORPORELLE"
    IMMOBILISATION_INCORPORELLE = "IMMOBILISATION_INCORPORELLE"
    AMORTISSEMENT = "AMORTISSEMENT"
    DOTATION_AMORTISSEMENT = "DOTATION_AMORTISSEMENT"
    RESULTAT_BENEFICE = "RESULTAT_BENEFICE"
    RESULTAT_PERTE = "RESULTAT_PERTE"


class NatureAchat(str, Enum):
    """Miroir exact de com.example.comptable.comptabilite.operation.achat.NatureAchat."""

    MARCHANDISE = "MARCHANDISE"
    MATIERE_PREMIERE = "MATIERE_PREMIERE"
    CONSOMMABLE = "CONSOMMABLE"
    SERVICE = "SERVICE"
    FRAIS_ACCESSOIRE = "FRAIS_ACCESSOIRE"
    IMMOBILISATION = "IMMOBILISATION"
    CHARGE = "CHARGE"
    AUTRE = "AUTRE"


class TypeDocument(str, Enum):
    """Miroir exact de com.example.comptable.document.domain.TypeDocument - la classification
    metier Java persistee sur Document.typeDocument. Distinct de TypeDocumentDetecte
    (app.schemas.extraction) qui est le typage visuel du VLM, jamais persiste au-dela de
    l'extraction elle-meme (voir DocumentExtractionSnapshot)."""

    FACTURE_ACHAT = "FACTURE_ACHAT"
    FACTURE_VENTE = "FACTURE_VENTE"
    RECU = "RECU"
    RELEVE_BANCAIRE = "RELEVE_BANCAIRE"
    AUTRE = "AUTRE"


class AccountingOperationType(str, Enum):
    """Sous-ensemble de com.example.comptable.comptabilite.operation.common.AccountingOperationType
    limite a ce que ce premier workflow produit reellement (facture fournisseur). A etendre
    seulement quand un workflow vente/reglement equivalent est construit - ne pas anticiper."""

    ACHAT = "ACHAT"


class TaxTreatment(str, Enum):
    """Classification fiscale du document, cote Python uniquement (ce n'est PAS le miroir d'un
    enum Java existant) - oriente la ligne de TVA de la proposition vers le bon AccountPurpose.
    NON_APPLICABLE couvre l'absence de TVA sur le document (pas seulement l'exoneration
    reglementaire, qui est EXONERE)."""

    TVA_RECUPERABLE = "TVA_RECUPERABLE"
    EXONERE = "EXONERE"
    NON_APPLICABLE = "NON_APPLICABLE"


class AccountResolutionStatus(str, Enum):
    """Miroir exact de com.example.comptable.comptabilite.parametrage.AccountResolutionStatus."""

    RESOLVED = "RESOLVED"
    AMBIGUOUS = "AMBIGUOUS"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    INVALID = "INVALID"


class AccountResolutionSource(str, Enum):
    """Miroir exact de com.example.comptable.comptabilite.parametrage.AccountResolutionSource."""

    EXPLICITE = "EXPLICITE"
    MAPPING_CONTEXTUEL = "MAPPING_CONTEXTUEL"
    MAPPING_GENERIQUE = "MAPPING_GENERIQUE"
    ROLE_SYSTEME = "ROLE_SYSTEME"
    AUCUN = "AUCUN"


class StatutOperationProposal(str, Enum):
    """Miroir exact de com.example.comptable.comptabilite.operation.common.StatutOperationProposal
    (statut de l'AccountingOperationProposal Java - a ne pas confondre avec le modele
    AccountingProposal Python de ce fichier, voir sa docstring ci-dessous)."""

    PROPOSED = "PROPOSED"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"


class WorkflowStatus(str, Enum):
    """Statut du workflow LangGraph lui-meme (cote Python uniquement) - distinct du statut de la
    proposition Java (StatutOperationProposal) et de celui du Document Java."""

    PENDING = "PENDING"
    RUNNING = "RUNNING"
    AWAITING_HUMAN_REVIEW = "AWAITING_HUMAN_REVIEW"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ApprovalResult(BaseModel):
    """Decision humaine consignee dans le state du workflow apres l'interruption human_review (le
    mecanisme d'interruption/reprise lui-meme n'est pas construit ici, voir plan STEP 9 - ceci est
    uniquement le contrat de transport de la decision). reviewer_id est l'identifiant Java de
    l'utilisateur (UtilisateurPrincipal.id), jamais un email/nom libre, pour rester coherent avec
    AuditTrailService.enregistrerActionHumaine cote Java."""

    approved: bool
    reviewer_id: UUID | None = Field(default=None, alias="reviewerId")
    timestamp: datetime | None = None
    comment: str | None = None

    model_config = _CAMEL_CASE_CONFIG


class DocumentExtractionSnapshot(BaseModel):
    """Sortie du node load_extraction : instantane de l'extraction deja persistee cote Java
    (agent/accounting/AiAccountingDocumentQueryService, STEP 6) - ne relance jamais le VLM, relit
    uniquement ce que Document/DocumentService ont deja enregistre."""

    document_id: UUID = Field(alias="documentId")
    company_id: UUID = Field(alias="companyId")
    type_document: TypeDocument | None = Field(default=None, alias="typeDocument")
    montant_ht: Decimal | None = Field(default=None, alias="montantHt")
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal | None = Field(default=None, alias="montantTtc")
    date_piece: date | None = Field(default=None, alias="datePiece")
    fournisseur: str | None = None
    devise: str = "XOF"
    mode_paiement: str | None = Field(default=None, alias="modePaiement")
    confiance: float | None = None

    model_config = _CAMEL_CASE_CONFIG


class NormalizedAccountingDocument(BaseModel):
    """Sortie du node normalize_document : re-forme l'extraction deja persistee
    (DocumentExtractionSnapshot) en un contrat oriente comptabilite. Les incoherences sont
    signalees dans normalization_warnings, jamais corrigees silencieusement ni devinees.
    `libelle` est un texte libre optionnel (motif/description de l'operation, ex. "carburant") -
    l'extraction automatique actuelle ne le fournit pas encore (seulement des montants/tiers/date) ;
    il reste disponible pour une future amelioration de l'extraction ou une saisie manuelle, et
    c'est le signal principal du classifieur deterministe avec `fournisseur` (voir
    app/accounting/classifiers/deterministic.py)."""

    document_id: UUID = Field(alias="documentId")
    company_id: UUID = Field(alias="companyId")
    document_type: TypeDocument | None = Field(default=None, alias="documentType")
    fournisseur: str | None = None
    libelle: str | None = None
    nif_rccm: str | None = Field(default=None, alias="nifRccm")
    montant_ht: Decimal | None = Field(default=None, alias="montantHt")
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal | None = Field(default=None, alias="montantTtc")
    devise: str = "XOF"
    date_piece: date | None = Field(default=None, alias="datePiece")
    reference_transaction: str | None = Field(default=None, alias="referenceTransaction")
    extraction_confidence: float = Field(alias="extractionConfidence", ge=0.0, le=1.0)
    normalization_warnings: list[str] = Field(default_factory=list, alias="normalizationWarnings")

    model_config = _CAMEL_CASE_CONFIG


class AccountingNatureLabel(str, Enum):
    """Vocabulaire de classification interne a Python (PAS un miroir Java, contrairement a
    AccountPurpose) - une categorie metier lisible (ex. "carburant") qui se traduit ensuite en
    AccountPurpose reel via suggest_accounting_treatment. Liste volontairement minimale (MVP) :
    etendre au fur et a mesure de besoins reels constates, jamais par anticipation."""

    FUEL = "FUEL"
    RENT = "RENT"
    TELECOM = "TELECOM"
    OTHER = "OTHER"


class SuggestedAccount(BaseModel):
    purpose: AccountPurpose
    confidence: float = Field(ge=0.0, le=1.0)

    model_config = _CAMEL_CASE_CONFIG


class AccountingClassification(BaseModel):
    """Sortie du node classify_accounting_nature : une proposition de classification, jamais un
    compte final choisi (AccountingAccountResolver cote Java reste seul juge - voir
    ValidationResult). requires_review est calcule ici a partir de la confiance/l'ambiguite, mais
    Java peut independamment forcer une revue humaine (ValidationResult.requires_review) meme si
    ce champ valait False - les deux verifications sont cumulatives, jamais l'une a la place de
    l'autre."""

    operation_type: AccountingOperationType = Field(alias="operationType")
    accounting_nature: AccountingNatureLabel = Field(alias="accountingNature")
    nature_achat: NatureAchat | None = Field(default=None, alias="natureAchat")
    suggested_accounts: list[SuggestedAccount] = Field(default_factory=list, alias="suggestedAccounts")
    tax_treatment: TaxTreatment = Field(alias="taxTreatment")
    confidence: float = Field(ge=0.0, le=1.0)
    requires_review: bool = Field(alias="requiresReview")
    rationale: str | None = None

    model_config = _CAMEL_CASE_CONFIG


class AccountingTreatment(BaseModel):
    """Sortie du node suggest_accounting_treatment : traduit AccountingClassification en un plan
    de traitement structure (quel purpose pour la charge, quel purpose pour la TVA, quel purpose
    pour le tiers) - ne resout encore aucun compte reel : la resolution en compte concret reste
    exclusive a Java (AccountingAccountResolver, via validate_with_java -> ValidationResult.resolved_accounts)."""

    expense_purpose: AccountPurpose = Field(alias="expensePurpose")
    tax_purpose: AccountPurpose | None = Field(default=None, alias="taxPurpose")
    counterparty_purpose: AccountPurpose = Field(alias="counterpartyPurpose")
    confidence: float = Field(ge=0.0, le=1.0)

    model_config = _CAMEL_CASE_CONFIG


class ProposedAccountingLine(BaseModel):
    purpose: AccountPurpose
    montant_ht: Decimal = Field(alias="montantHt")
    designation: str

    model_config = _CAMEL_CASE_CONFIG


class AccountingProposal(BaseModel):
    """Sortie du node build_accounting_proposal : le brouillon d'ecriture propose par l'IA, avant
    toute validation Java. Ce n'est PAS le meme modele que
    com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal (Java) -
    c'est un contrat de transport minimal que Java convertit/valide vers ce contrat existant
    (STEP 4), jamais une reimplementation du modele comptable Java cote Python."""

    workflow_id: UUID = Field(alias="workflowId")
    document_id: UUID = Field(alias="documentId")
    company_id: UUID = Field(alias="companyId")
    lines: list[ProposedAccountingLine] = Field(default_factory=list)
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal = Field(alias="montantTtc")
    classification: AccountingClassification
    idempotency_key: str = Field(alias="idempotencyKey")
    date_piece: date = Field(default_factory=date.today, alias="datePiece")
    libelle: str = "Operation"

    model_config = _CAMEL_CASE_CONFIG


class AccountingProposalValidationRequest(BaseModel):
    """Sous-ensemble d'AccountingProposal reellement transmis a validate_with_java (STEP 4,
    app/services/accounting_client.py) : `classification`/`idempotency_key` restent internes a
    Python (raisonnement IA, cle de posting future) - Java n'en a aucun besoin pour cette
    validation en lecture seule, et les lui envoyer ferait echouer la deserialisation Jackson
    (aucune configuration "ignorer les champs inconnus" cote Java pour cet endpoint)."""

    workflow_id: UUID = Field(alias="workflowId")
    document_id: UUID = Field(alias="documentId")
    company_id: UUID = Field(alias="companyId")
    lines: list[ProposedAccountingLine]
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal = Field(alias="montantTtc")

    model_config = _CAMEL_CASE_CONFIG

    @classmethod
    def depuis_proposal(cls, proposal: "AccountingProposal") -> "AccountingProposalValidationRequest":
        return cls(
            workflow_id=proposal.workflow_id,
            document_id=proposal.document_id,
            company_id=proposal.company_id,
            lines=proposal.lines,
            montant_tva=proposal.montant_tva,
            montant_ttc=proposal.montant_ttc,
        )


class AccountingProposalPostRequest(BaseModel):
    """Miroir exact de
    com.example.comptable.agent.accounting.web.dto.PostAiAccountingProposalRequest (STEP 7) -
    memes lignes que AccountingProposalValidationRequest (jamais un troisieme modele de
    proposition), avec en plus date_piece/libelle requis pour construire une ecriture reelle -
    inutiles pour une simple validation en lecture seule."""

    workflow_id: UUID = Field(alias="workflowId")
    document_id: UUID = Field(alias="documentId")
    company_id: UUID = Field(alias="companyId")
    lines: list[ProposedAccountingLine]
    montant_tva: Decimal | None = Field(default=None, alias="montantTva")
    montant_ttc: Decimal = Field(alias="montantTtc")
    date_piece: date = Field(alias="datePiece")
    libelle: str

    model_config = _CAMEL_CASE_CONFIG

    @classmethod
    def depuis_proposal(cls, proposal: "AccountingProposal") -> "AccountingProposalPostRequest":
        return cls(
            workflow_id=proposal.workflow_id,
            document_id=proposal.document_id,
            company_id=proposal.company_id,
            lines=proposal.lines,
            montant_tva=proposal.montant_tva,
            montant_ttc=proposal.montant_ttc,
            date_piece=proposal.date_piece,
            libelle=proposal.libelle,
        )


class ResolvedAccountInfo(BaseModel):
    """Miroir de com.example.comptable.comptabilite.parametrage.web.dto.ResolvedAccountDto -
    renvoye par Java lors de validate_with_java, jamais calcule cote Python."""

    status: AccountResolutionStatus
    compte_id: UUID | None = Field(default=None, alias="compteId")
    compte_numero: str | None = Field(default=None, alias="compteNumero")
    compte_intitule: str | None = Field(default=None, alias="compteIntitule")
    source: AccountResolutionSource
    explication: str

    model_config = _CAMEL_CASE_CONFIG


class ValidationResult(BaseModel):
    """Reponse de Java a validate_with_java - c'est Java, jamais Python, qui decide si la
    proposition est valide et si une revue humaine est necessaire."""

    valid: bool
    requires_review: bool = Field(alias="requiresReview")
    resolved_accounts: list[ResolvedAccountInfo] = Field(default_factory=list, alias="resolvedAccounts")
    errors: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)

    model_config = _CAMEL_CASE_CONFIG


class PostingResult(BaseModel):
    """Miroir exact de
    com.example.comptable.agent.accounting.web.dto.AiAccountingPostingResponseDto (STEP 7) -
    la seule source de verite sur si une ecriture a reellement ete posee. `posted` ne peut jamais
    devenir vrai sans etre passe par AccountingOperationEngine cote Java - jamais un flag decide
    ou devine cote Python."""

    posted: bool
    proposal_id: UUID | None = Field(default=None, alias="proposalId")
    entry_id: UUID | None = Field(default=None, alias="entryId")
    entry_number: str | None = Field(default=None, alias="entryNumber")
    posted_at: datetime | None = Field(default=None, alias="postedAt")
    journal: str | None = None
    errors: list[str] = Field(default_factory=list)

    model_config = _CAMEL_CASE_CONFIG


class WorkflowResult(BaseModel):
    """Resultat final agrege du workflow LangGraph AI Accounting."""

    workflow_id: UUID = Field(alias="workflowId")
    document_id: UUID = Field(alias="documentId")
    company_id: UUID = Field(alias="companyId")
    status: WorkflowStatus
    classification: AccountingClassification | None = None
    proposal: AccountingProposal | None = None
    validation: ValidationResult | None = None
    posting: PostingResult | None = None
    errors: list[str] = Field(default_factory=list)

    model_config = _CAMEL_CASE_CONFIG
