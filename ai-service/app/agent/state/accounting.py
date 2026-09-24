"""AccountingState : contrat de state pour le futur workflow LangGraph Python "AI Accounting"
(premier vertical slice : facture fournisseur). Ce module ne contient aucun node, aucun graph,
aucun appel reseau/LLM/Java - uniquement la forme des donnees echangees entre les nodes
(load_extraction, normalize_document, classify_accounting_nature, human_review_classification,
suggest_accounting_treatment, build_accounting_proposal, validate_with_java, finalize - voir
app/agent/graph/accounting.py). Pas encore de posting (post_with_java, hors perimetre STEP 6).

Choix de forme : TypedDict(total=False), le format le plus repandu pour un state consomme par
langgraph.graph.StateGraph - chaque node futur retournera une mise a jour partielle (un dict ne
portant que les cles qu'il modifie), fusionnee par LangGraph selon la strategie "derniere ecriture
gagne" par defaut. Aucun reducer personnalise ici : les champs de ce state sont tous des valeurs
simples ou des objets Pydantic remplaces entierement par le node qui les produit, jamais
accumules/merges champ par champ - un reducer ne serait justifie que pour un champ growable (ex.
une liste de messages), ce qui n'est pas le cas ici.

Ce que ce state ne contient JAMAIS (section 7/17 du plan d'implementation) :
- l'image/le PDF brut du document (reste cote Java/stockage documents, jamais duplique ici) ;
- un secret, token, ou identifiant d'authentification - company_id/document_id/workflow_id sont
  du contexte transporte, jamais une preuve d'autorisation (voir docstring de AccountingState.company_id
  ci-dessous) ; chaque appel Java (STEP 4+) doit revalider l'acces via le mecanisme Java existant
  (EntrepriseAccessService.verifierAcces), jamais faire confiance a ce qui est deja dans le state ;
- une trace de raisonnement libre du LLM (chain-of-thought) - seul un `rationale` court et deja
  filtre (AccountingClassification.rationale) transite, jamais un raisonnement brut.
"""

from typing import TypedDict
from uuid import UUID

from pydantic import TypeAdapter

from app.schemas.accounting import (
    AccountingClassification,
    AccountingProposal,
    AccountingTreatment,
    ApprovalResult,
    DocumentExtractionSnapshot,
    NormalizedAccountingDocument,
    PostingResult,
    ResolvedAccountInfo,
    ValidationResult,
    WorkflowStatus,
)


class AccountingState(TypedDict, total=False):
    """State du workflow AI Accounting.

    Tous les champs sont optionnels au sens TypedDict `total=False` : un champ absent signifie
    "le node qui le produit n'a pas encore tourne", jamais "valeur vide/nulle" - un node lit un
    champ absent avec `state.get(...)`, jamais `state[...]` directement.

    company_id est le contexte metier transporte par le workflow, PAS une preuve d'autorisation :
    sa seule presence ici ne doit jamais etre traitee comme suffisante pour autoriser une action.
    """

    # Identite du workflow - presents des la creation du state (voir nouvel_etat_initial).
    workflow_id: UUID
    company_id: UUID
    document_id: UUID

    # Resultats intermediaires - absents tant que le node correspondant n'a pas encore tourne.
    extraction: DocumentExtractionSnapshot
    normalized_document: NormalizedAccountingDocument

    classification: AccountingClassification
    accounting_treatment: AccountingTreatment
    resolved_accounts: list[ResolvedAccountInfo]
    proposal: AccountingProposal

    validation_result: ValidationResult
    approval_required: bool
    approval_result: ApprovalResult

    posting_result: PostingResult

    # Suivi du workflow (jamais de stack trace complete ni de donnee sensible dans errors - les
    # logs detailles restent dans le systeme de logging, cf. app/services/*_client.py::_tracer_appel_ia).
    status: WorkflowStatus
    errors: list[str]


ACCOUNTING_STATE_ADAPTER: TypeAdapter = TypeAdapter(AccountingState)
"""Valide/serialise un AccountingState complet (UUID/Decimal/enum/modeles Pydantic imbriques
inclus) sans ecrire de serializer custom - reutilisable tel quel par un futur checkpointer
LangGraph (STEP 12) si sa serialisation par defaut ne suffit pas."""


def nouvel_etat_initial(workflow_id: UUID, company_id: UUID, document_id: UUID) -> AccountingState:
    """Etat minimal de depart d'un workflow, avant meme le chargement de l'extraction
    (load_extraction, STEP 3). Aucune logique metier ici, uniquement la forme initiale du contrat."""

    return AccountingState(
        workflow_id=workflow_id,
        company_id=company_id,
        document_id=document_id,
        status=WorkflowStatus.PENDING,
        errors=[],
    )
