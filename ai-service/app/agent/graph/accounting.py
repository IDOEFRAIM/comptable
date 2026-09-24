"""Graph LangGraph Python "AI Accounting". STEP 3 a valide l'infrastructure pure (state ->
StateGraph -> nodes -> checkpoint -> END). STEP 5 y a branche le premier vrai node metier
(validate_with_java). STEP 6 a construit le chemin document -> proposition comptable. STEP 7
ajoute le posting controle : validate_with_java et post_entry passent desormais par MCP
(app/mcp/) plutot que par AccountingClient directement (section 7.9 du plan - MCP devient la
frontiere agentique, aucune logique metier ne se deplace), une politique de posting deterministe
(app/accounting/services/posting_policy.py, section 7.7) decide si une revue humaine de POSTING
est necessaire avant de poster reellement (distincte de la revue de CLASSIFICATION plus haut dans
le graph, section 7.8), et le posting lui-meme n'a jamais lieu si la validation a echoue.

Ce module orchestre uniquement : aucune regle comptable n'y est dupliquee (resolution de compte,
partie double, validation, posting restent exclusifs a Java) ; la classification deterministe/LLM,
la construction de la proposition et la politique de posting vivent dans app/accounting/
(classifiers/services), jamais ici.

Graph actuel :
    START -> initialize -> load_extraction -> normalize_document -> classify_accounting_nature
        -+-(confiance suffisante)-------------------------+-> suggest_accounting_treatment
         +-(confiance faible)-> human_review_classification-+
        -> build_accounting_proposal -> validate_with_java
        -+-(invalide)---------------------------------------------------------------+
         +-(valide, posting automatique autorise)-----------------+-> post_entry ----+
         +-(valide, revue de posting requise)-> human_posting_approval-+-(refuse)----+
                                                                        +-(approuve)--+ (-> post_entry)
        -> finalize -> END
"""

import logging
from collections.abc import Callable
from typing import Protocol
from uuid import UUID

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.accounting.services.classification_service import ClassificationService
from app.accounting.services.normalization import normaliser
from app.accounting.services.posting_policy import approbation_humaine_requise_pour_poster
from app.accounting.services.proposal_builder import construire_proposal
from app.accounting.services.treatment import suggerer_traitement
from app.agent.hitl import demander_revue_humaine, est_acquittement_sans_correction
from app.agent.state.accounting import AccountingState
from app.mcp.client import McpAccountingClient
from app.mcp.server import creer_mcp_server
from app.schemas.accounting import (
    AccountingClassification,
    AccountingProposal,
    ApprovalResult,
    DocumentExtractionSnapshot,
    PostingResult,
    ValidationResult,
    WorkflowStatus,
)
from app.services.accounting_client import AccountingClient, AccountingClientError

logger = logging.getLogger(__name__)

NODE_INITIALIZE = "initialize"
NODE_LOAD_EXTRACTION = "load_extraction"
NODE_NORMALIZE_DOCUMENT = "normalize_document"
NODE_CLASSIFY_ACCOUNTING_NATURE = "classify_accounting_nature"
NODE_HUMAN_REVIEW_CLASSIFICATION = "human_review_classification"
NODE_SUGGEST_ACCOUNTING_TREATMENT = "suggest_accounting_treatment"
NODE_BUILD_ACCOUNTING_PROPOSAL = "build_accounting_proposal"
NODE_VALIDATE_WITH_JAVA = "validate_with_java"
NODE_HUMAN_POSTING_APPROVAL = "human_posting_approval"
NODE_POST_ENTRY = "post_entry"
NODE_FINALIZE = "finalize"

SEUIL_CONFIANCE_CLASSIFICATION = 0.70
"""Policy agentique (section 10 du plan), pas une regle comptable : en-dessous de ce seuil, le
graph route vers human_review_classification plutot que de proceder automatiquement."""


class _AccountingDocumentReader(Protocol):
    def get_document_extraction(self, document_id: UUID) -> DocumentExtractionSnapshot: ...


class _AccountingValidator(Protocol):
    def validate_proposal(self, proposal: AccountingProposal) -> ValidationResult: ...


class _AccountingPoster(Protocol):
    def post_entry(self, proposal: AccountingProposal) -> PostingResult: ...


class MissingAccountingProposalError(ValueError):
    """Leve par validate_with_java quand AccountingState.proposal est absent - erreur structuree
    et explicite plutot qu'un KeyError/AttributeError opaque a l'appel de accounting_client."""


class MissingAccountingInputError(ValueError):
    """Leve par un node quand une entree requise (extraction/normalized_document/classification/
    accounting_treatment) est absente du state - meme discipline que MissingAccountingProposalError."""


def initialize_node(state: AccountingState) -> AccountingState:
    """Verifie que le state possede les champs minimaux avant de laisser le workflow demarrer, et
    passe le statut a RUNNING. N'initialise `errors` que s'il est reellement absent - un state
    resume qui portait deja des erreurs ne doit jamais les voir silencieusement effacees (aucune
    cle `errors` dans la mise a jour retournee => LangGraph conserve la valeur existante)."""

    champs_requis = ("workflow_id", "company_id", "document_id")
    manquants = [champ for champ in champs_requis if champ not in state]
    if manquants:
        raise ValueError(f"AccountingState incomplet avant initialize : champ(s) manquant(s) {manquants}")

    workflow_id: UUID = state["workflow_id"]
    logger.info("accounting_workflow workflow_id=%s node=%s status=%s", workflow_id, NODE_INITIALIZE, WorkflowStatus.RUNNING.value)

    mise_a_jour: AccountingState = {"status": WorkflowStatus.RUNNING}
    if "errors" not in state:
        mise_a_jour["errors"] = []
    return mise_a_jour


def finalize_node(state: AccountingState) -> AccountingState:
    """Passe le statut a COMPLETED. Aucun appel externe, aucun calcul comptable."""

    workflow_id = state.get("workflow_id")
    logger.info("accounting_workflow workflow_id=%s node=%s status=%s", workflow_id, NODE_FINALIZE, WorkflowStatus.COMPLETED.value)

    return {"status": WorkflowStatus.COMPLETED}


def creer_load_extraction_node(client: _AccountingDocumentReader) -> Callable[[AccountingState], AccountingState]:
    """Fabrique load_extraction : relit l'extraction deja persistee cote Java via
    `client.get_document_extraction` - ne relance jamais le VLM, ne reimplemente aucun pipeline de
    documents (STEP 6, section 3 du plan). `client` est un AccountingClient direct ou un
    McpAccountingClient (STEP 7.9) - le node ignore lequel, seule la forme (meme nom de methode,
    meme type de retour) compte."""

    def load_extraction_node(state: AccountingState) -> AccountingState:
        document_id = state["document_id"]
        workflow_id = state.get("workflow_id")
        logger.info("accounting_workflow workflow_id=%s document_id=%s node=%s", workflow_id, document_id, NODE_LOAD_EXTRACTION)

        try:
            extraction = client.get_document_extraction(document_id)
        except AccountingClientError as exc:
            logger.warning(
                "accounting_workflow workflow_id=%s document_id=%s node=%s result=echec error=%s retryable=%s",
                workflow_id, document_id, NODE_LOAD_EXTRACTION, type(exc).__name__, exc.retryable,
            )
            raise

        return {"extraction": extraction}

    return load_extraction_node


def normalize_document_node(state: AccountingState) -> AccountingState:
    """Convertit l'extraction deja chargee en NormalizedAccountingDocument - 100% deterministe,
    aucun LLM, aucun appel reseau (STEP 6, section 4 du plan)."""

    extraction = state.get("extraction")
    if extraction is None:
        raise MissingAccountingInputError(
            "AccountingState.extraction absent : normalize_document ne peut pas s'executer sans extraction chargee"
        )

    document = normaliser(extraction)
    logger.info(
        "accounting_workflow workflow_id=%s document_id=%s node=%s warnings=%d",
        state.get("workflow_id"), state.get("document_id"), NODE_NORMALIZE_DOCUMENT, len(document.normalization_warnings),
    )
    return {"normalized_document": document}


def creer_classify_accounting_nature_node(
    classification_service: ClassificationService,
) -> Callable[[AccountingState], AccountingState]:
    """Fabrique classify_accounting_nature : deterministe d'abord, LLM en repli uniquement si
    aucune regle ne correspond (voir ClassificationService) - la premiere vraie intelligence du
    workflow (STEP 6, section 5 du plan)."""

    def classify_accounting_nature_node(state: AccountingState) -> AccountingState:
        document = state.get("normalized_document")
        if document is None:
            raise MissingAccountingInputError(
                "AccountingState.normalized_document absent : classify_accounting_nature ne peut pas s'executer"
            )

        workflow_id = state.get("workflow_id")
        classification = classification_service.classifier(document, workflow_id=workflow_id)
        logger.info(
            "accounting_workflow workflow_id=%s document_id=%s node=%s nature=%s confidence=%s",
            workflow_id, state.get("document_id"), NODE_CLASSIFY_ACCOUNTING_NATURE,
            classification.accounting_nature.value, classification.confidence,
        )
        return {"classification": classification}

    return classify_accounting_nature_node


def human_review_classification_node(state: AccountingState) -> AccountingState:
    """Point d'interruption HITL specifique a la CLASSIFICATION (section 18/19 du plan) - distinct
    de la revue d'une operation comptable deja prete (validate_with_java/approval_required). Pas
    d'UI construite ici : le mecanisme interrupt/resume passe entierement par app/agent/hitl.py
    (STEP 7.1), qui est desormais le seul endroit du code a connaitre le detail du piege
    Command(resume={})/Command(resume=None) - voir sa docstring de module. Si la reprise fournit
    une classification corrigee, elle remplace celle proposee ; sinon le workflow poursuit avec la
    classification existante (un humain a simplement pris connaissance de l'incertitude)."""

    classification = state.get("classification")
    decision = demander_revue_humaine(
        {
            "reason": "low_confidence_classification",
            "document_id": str(state.get("document_id")),
            "classification": classification.model_dump(by_alias=True) if classification else None,
            "confidence": classification.confidence if classification else None,
        }
    )

    if isinstance(decision, dict) and decision.get("classification"):
        nouvelle_classification = AccountingClassification.model_validate(decision["classification"])
        return {"classification": nouvelle_classification, "approval_required": False}
    return {}


def suggest_accounting_treatment_node(state: AccountingState) -> AccountingState:
    """Traduit AccountingClassification en AccountingTreatment - purement deterministe, ne resout
    encore aucun compte reel (STEP 6, section 11 du plan)."""

    classification = state.get("classification")
    if classification is None:
        raise MissingAccountingInputError(
            "AccountingState.classification absent : suggest_accounting_treatment ne peut pas s'executer"
        )

    treatment = suggerer_traitement(classification)
    logger.info(
        "accounting_workflow workflow_id=%s document_id=%s node=%s expense_purpose=%s",
        state.get("workflow_id"), state.get("document_id"), NODE_SUGGEST_ACCOUNTING_TREATMENT, treatment.expense_purpose.value,
    )
    return {"accounting_treatment": treatment}


def build_accounting_proposal_node(state: AccountingState) -> AccountingState:
    """Combine NormalizedAccountingDocument + AccountingClassification + AccountingTreatment pour
    construire l'AccountingProposal - montants deterministes (jamais recalcules par un LLM),
    voir app/accounting/services/proposal_builder.py (STEP 6, section 13-14 du plan)."""

    document = state.get("normalized_document")
    classification = state.get("classification")
    treatment = state.get("accounting_treatment")
    if document is None or classification is None or treatment is None:
        raise MissingAccountingInputError(
            "build_accounting_proposal necessite normalized_document, classification et accounting_treatment"
        )

    proposal = construire_proposal(
        workflow_id=state["workflow_id"], document=document, classification=classification, treatment=treatment
    )
    logger.info(
        "accounting_workflow workflow_id=%s document_id=%s node=%s montant_ttc=%s",
        state.get("workflow_id"), state.get("document_id"), NODE_BUILD_ACCOUNTING_PROPOSAL, proposal.montant_ttc,
    )
    return {"proposal": proposal}


def creer_validate_with_java_node(client: _AccountingValidator) -> Callable[[AccountingState], AccountingState]:
    """Fabrique le node validate_with_java lie a un client donne - injection par fermeture (pas de
    framework DI, pas de singleton global) : chaque appelant de build_accounting_graph fournit son
    propre client, reel ou mock, ce qui garde le graph facilement testable. `client` est un
    AccountingClient direct ou (depuis STEP 7.9) un McpAccountingClient qui passe par l'outil MCP
    accounting.validate_proposal - le node ignore lequel, seule la forme compte.

    Le node orchestre uniquement : il ne recalcule aucun total, ne verifie jamais debit==credit,
    ne resout aucun compte, ne decide jamais lui-meme de la validite - tout cela reste exclusif a
    Java (AccountingAccountResolver). Toute AccountingClientError (qu'elle vienne directement
    d'AccountingClient ou qu'elle ait traverse MCP - voir app/mcp/client.py) est loggee puis
    re-levee telle quelle, jamais remappee/masquee.
    """

    def validate_with_java_node(state: AccountingState) -> AccountingState:
        proposal = state.get("proposal")
        if proposal is None:
            raise MissingAccountingProposalError(
                "AccountingState.proposal absent : validate_with_java ne peut pas s'executer sans proposition a valider"
            )

        workflow_id = state.get("workflow_id")
        document_id = state.get("document_id")
        company_id = state.get("company_id")
        logger.info(
            "accounting_workflow workflow_id=%s document_id=%s company_id=%s node=%s",
            workflow_id, document_id, company_id, NODE_VALIDATE_WITH_JAVA,
        )

        try:
            resultat = client.validate_proposal(proposal)
        except AccountingClientError as exc:
            logger.warning(
                "accounting_workflow workflow_id=%s document_id=%s company_id=%s node=%s result=echec "
                "error=%s retryable=%s human_reviewable=%s",
                workflow_id, document_id, company_id, NODE_VALIDATE_WITH_JAVA,
                type(exc).__name__, exc.retryable, exc.human_reviewable,
            )
            raise

        logger.info(
            "accounting_workflow workflow_id=%s document_id=%s company_id=%s node=%s result=succes "
            "valid=%s requires_review=%s",
            workflow_id, document_id, company_id, NODE_VALIDATE_WITH_JAVA, resultat.valid, resultat.requires_review,
        )
        return {
            "validation_result": resultat,
            "approval_required": resultat.requires_review,
            "resolved_accounts": resultat.resolved_accounts,
        }

    return validate_with_java_node


def _raison_revue_posting(classification: AccountingClassification | None, validation: ValidationResult) -> str:
    """Raison structuree jointe au paquet d'interruption human_posting_approval - jamais un texte
    libre invente a la volee, pour rester exploitable par une future UI/tableau de bord."""

    if not validation.valid:
        return "validation_echouee"
    if validation.requires_review:
        return "revue_requise_par_java"
    if validation.warnings:
        return "avertissements_java"
    if classification is None:
        return "classification_absente"
    return "confiance_classification_insuffisante"


def human_posting_approval_node(state: AccountingState) -> AccountingState:
    """Point d'interruption HITL specifique a l'APPROBATION DE POSTING (STEP 7.8 du plan) -
    distinct de human_review_classification : type d'interruption different
    (ACCOUNTING_POSTING_APPROVAL), raison et paquet differents. N'est atteint que si
    posting_policy.approbation_humaine_requise_pour_poster l'a exige (voir
    _router_apres_validation) - jamais une decision du LLM. Passe par app/agent/hitl.py comme tout
    point d'interruption de ce graph (STEP 7.1)."""

    proposal = state.get("proposal")
    validation = state.get("validation_result")
    if proposal is None or validation is None:
        raise MissingAccountingProposalError(
            "human_posting_approval necessite proposal et validation_result dans le state"
        )

    document = state.get("normalized_document")
    payload = {
        "type": "ACCOUNTING_POSTING_APPROVAL",
        "workflow_id": str(state.get("workflow_id")),
        "document_id": str(state.get("document_id")),
        # Aucune proposition n'est encore persistee cote Java a ce stade (elle ne l'est qu'au
        # moment du posting lui-meme, cf. AiAccountingPostingService) : idempotency_key est
        # l'identifiant stable de cette proposition cote Python, utilise ici en son absence.
        "proposal_id": proposal.idempotency_key,
        "reason": _raison_revue_posting(state.get("classification"), validation),
        "amount": str(proposal.montant_ttc),
        "currency": document.devise if document else "XOF",
        "resolved_accounts": [compte.model_dump(by_alias=True) for compte in validation.resolved_accounts],
    }
    decision = demander_revue_humaine(payload)

    if est_acquittement_sans_correction(decision):
        return {"approval_result": ApprovalResult(approved=True)}
    if isinstance(decision, dict):
        return {
            "approval_result": ApprovalResult(
                approved=bool(decision.get("approved", False)),
                reviewer_id=decision.get("reviewer_id"),
                comment=decision.get("comment"),
            )
        }
    return {"approval_result": ApprovalResult(approved=False)}


def creer_post_entry_node(client: _AccountingPoster) -> Callable[[AccountingState], AccountingState]:
    """Fabrique post_entry (STEP 7.6/7.9) : seule operation mutative du graph. `client` est un
    AccountingClient direct ou (en pratique, via build_accounting_graph) un McpAccountingClient qui
    passe par l'outil MCP accounting.post_entry. Le node ne decide jamais SI il faut poster (deja
    tranche par la policy/le routeur avant d'atteindre ce node) - il ne fait que transmettre la
    proposition deja validee et consigner le resultat."""

    def post_entry_node(state: AccountingState) -> AccountingState:
        proposal = state.get("proposal")
        if proposal is None:
            raise MissingAccountingProposalError(
                "AccountingState.proposal absent : post_entry ne peut pas s'executer sans proposition"
            )

        workflow_id = state.get("workflow_id")
        document_id = state.get("document_id")
        logger.info("accounting_workflow workflow_id=%s document_id=%s node=%s", workflow_id, document_id, NODE_POST_ENTRY)

        try:
            resultat = client.post_entry(proposal)
        except AccountingClientError as exc:
            logger.warning(
                "accounting_workflow workflow_id=%s document_id=%s node=%s result=echec error=%s retryable=%s",
                workflow_id, document_id, NODE_POST_ENTRY, type(exc).__name__, exc.retryable,
            )
            raise

        logger.info(
            "accounting_workflow workflow_id=%s document_id=%s node=%s result=succes posted=%s",
            workflow_id, document_id, NODE_POST_ENTRY, resultat.posted,
        )
        return {"posting_result": resultat}

    return post_entry_node


def _router_apres_classification(state: AccountingState) -> str:
    """Policy agentique (section 10/18 du plan), pas une regle comptable : distingue "je ne suis
    pas sur de la nature" (ici) de "cette proposition est prete mais necessite une validation
    humaine" (approval_required, calcule par validate_with_java) - jamais melanges."""

    classification = state.get("classification")
    if classification is not None and classification.confidence >= SEUIL_CONFIANCE_CLASSIFICATION:
        return "confiance_suffisante"
    return "revue_necessaire"


def _router_apres_validation(state: AccountingState) -> str:
    """Policy agentique (STEP 7.7) : une proposition invalide n'est JAMAIS postee (route direct
    vers finalize, quelle que soit la confiance de classification) ; une proposition valide passe
    par la politique de posting deterministe (posting_policy.py, jamais le LLM) pour decider si un
    humain doit approuver le posting avant qu'il n'ait reellement lieu."""

    validation = state.get("validation_result")
    if validation is None or not validation.valid:
        return "rejete"
    if approbation_humaine_requise_pour_poster(state.get("classification"), validation):
        return "revue_posting_requise"
    return "poster_directement"


def _router_apres_approbation_posting(state: AccountingState) -> str:
    """Policy agentique (STEP 7.8) : seul un ApprovalResult.approved=True declenche reellement le
    posting - toute autre reponse (refus explicite, absence de decision) n'aboutit jamais a un
    appel a post_entry."""

    approval = state.get("approval_result")
    if approval is not None and approval.approved:
        return "poster"
    return "refuse"


def build_accounting_graph(
    accounting_client: AccountingClient,
    classification_service: ClassificationService,
    checkpointer: BaseCheckpointSaver | None = None,
) -> CompiledStateGraph:
    """Factory explicite (jamais compile au moment de l'import). `accounting_client` et
    `classification_service` restent les seules dependances externes obligatoires (aucun
    singleton global, signature inchangee depuis STEP 6) : c'est ce qui rend
    load_extraction/classify_accounting_nature/validate_with_java/post_entry testables sans reseau
    ni LLM reel. `checkpointer=None` compile sans persistance.

    Depuis STEP 7.9, load_extraction/validate_with_java/post_entry ne recoivent plus
    `accounting_client` directement : ce module construit ici un serveur MCP (app/mcp/server.py)
    autour de ce meme `accounting_client`, puis un McpAccountingClient (app/mcp/client.py) qui leur
    est injecte a la place - MCP devient la frontiere agentique du graph, sans deplacer la moindre
    logique metier (elle reste entierement dans accounting_client/Java, inchangee) ni changer la
    signature publique de cette factory."""

    mcp_server = creer_mcp_server(accounting_client)
    mcp_client = McpAccountingClient(mcp_server)

    load_extraction_node = creer_load_extraction_node(mcp_client)
    classify_accounting_nature_node = creer_classify_accounting_nature_node(classification_service)
    validate_with_java_node = creer_validate_with_java_node(mcp_client)
    post_entry_node = creer_post_entry_node(mcp_client)

    graph = StateGraph(AccountingState)
    graph.add_node(NODE_INITIALIZE, initialize_node)
    graph.add_node(NODE_LOAD_EXTRACTION, load_extraction_node)
    graph.add_node(NODE_NORMALIZE_DOCUMENT, normalize_document_node)
    graph.add_node(NODE_CLASSIFY_ACCOUNTING_NATURE, classify_accounting_nature_node)
    graph.add_node(NODE_HUMAN_REVIEW_CLASSIFICATION, human_review_classification_node)
    graph.add_node(NODE_SUGGEST_ACCOUNTING_TREATMENT, suggest_accounting_treatment_node)
    graph.add_node(NODE_BUILD_ACCOUNTING_PROPOSAL, build_accounting_proposal_node)
    graph.add_node(NODE_VALIDATE_WITH_JAVA, validate_with_java_node)
    graph.add_node(NODE_HUMAN_POSTING_APPROVAL, human_posting_approval_node)
    graph.add_node(NODE_POST_ENTRY, post_entry_node)
    graph.add_node(NODE_FINALIZE, finalize_node)

    graph.add_edge(START, NODE_INITIALIZE)
    graph.add_edge(NODE_INITIALIZE, NODE_LOAD_EXTRACTION)
    graph.add_edge(NODE_LOAD_EXTRACTION, NODE_NORMALIZE_DOCUMENT)
    graph.add_edge(NODE_NORMALIZE_DOCUMENT, NODE_CLASSIFY_ACCOUNTING_NATURE)
    graph.add_conditional_edges(
        NODE_CLASSIFY_ACCOUNTING_NATURE,
        _router_apres_classification,
        {
            "confiance_suffisante": NODE_SUGGEST_ACCOUNTING_TREATMENT,
            "revue_necessaire": NODE_HUMAN_REVIEW_CLASSIFICATION,
        },
    )
    graph.add_edge(NODE_HUMAN_REVIEW_CLASSIFICATION, NODE_SUGGEST_ACCOUNTING_TREATMENT)
    graph.add_edge(NODE_SUGGEST_ACCOUNTING_TREATMENT, NODE_BUILD_ACCOUNTING_PROPOSAL)
    graph.add_edge(NODE_BUILD_ACCOUNTING_PROPOSAL, NODE_VALIDATE_WITH_JAVA)
    graph.add_conditional_edges(
        NODE_VALIDATE_WITH_JAVA,
        _router_apres_validation,
        {
            "rejete": NODE_FINALIZE,
            "poster_directement": NODE_POST_ENTRY,
            "revue_posting_requise": NODE_HUMAN_POSTING_APPROVAL,
        },
    )
    graph.add_conditional_edges(
        NODE_HUMAN_POSTING_APPROVAL,
        _router_apres_approbation_posting,
        {
            "poster": NODE_POST_ENTRY,
            "refuse": NODE_FINALIZE,
        },
    )
    graph.add_edge(NODE_POST_ENTRY, NODE_FINALIZE)
    graph.add_edge(NODE_FINALIZE, END)
    return graph.compile(checkpointer=checkpointer)
