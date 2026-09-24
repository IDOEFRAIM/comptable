"""Tests STEP 3+5+6 : STEP 3 a valide l'infrastructure LangGraph pure (build/invoke/thread/
checkpoint/interrupt/resume) ; STEP 5 y a ajoute validate_with_java ; STEP 6 a etendu le graph au
chemin complet document -> proposition (voir accounting.py et test_accounting_graph_e2e.py pour le
detail fonctionnel). Ces tests-ci restent volontairement centres sur l'infrastructure (regression),
avec un document carburant deterministe (aucun LLM/interrupt) pour ne pas re-tester la
classification ici - c'est le role de test_accounting_graph_e2e.py/test_deterministic_classifier.py."""

from decimal import Decimal
from typing import TypedDict
from unittest.mock import MagicMock
from uuid import uuid4

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command, Interrupt

from app.accounting.services.classification_service import ClassificationService
from app.agent.checkpoint import creer_checkpointer_accounting
from app.agent.graph.accounting import (
    NODE_BUILD_ACCOUNTING_PROPOSAL,
    NODE_CLASSIFY_ACCOUNTING_NATURE,
    NODE_FINALIZE,
    NODE_HUMAN_REVIEW_CLASSIFICATION,
    NODE_INITIALIZE,
    NODE_LOAD_EXTRACTION,
    NODE_NORMALIZE_DOCUMENT,
    NODE_SUGGEST_ACCOUNTING_TREATMENT,
    NODE_VALIDATE_WITH_JAVA,
    build_accounting_graph,
)
from app.agent.state.accounting import nouvel_etat_initial
from app.schemas.accounting import (
    DocumentExtractionSnapshot,
    PostingResult,
    TypeDocument,
    ValidationResult,
    WorkflowStatus,
)
from app.services.accounting_client import AccountingClient
from app.services.mock_llm_provider import MockLLMProvider


def _extraction_carburant(document_id, company_id) -> DocumentExtractionSnapshot:
    # Fournisseur/libelle deterministe (mot-cle "carburant") : jamais de LLM, jamais d'interrupt -
    # ces tests d'infrastructure n'ont pas a se soucier de la classification elle-meme.
    return DocumentExtractionSnapshot(
        document_id=document_id,
        company_id=company_id,
        type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000),
        montant_tva=Decimal(18000),
        montant_ttc=Decimal(118000),
        fournisseur="Station Total - carburant",
        confiance=0.92,
    )


def _client_mock(document_id, company_id, *, valid: bool = True, requires_review: bool = False) -> MagicMock:
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.return_value = _extraction_carburant(document_id, company_id)
    client.validate_proposal.return_value = ValidationResult(
        valid=valid, requires_review=requires_review, resolved_accounts=[], errors=[], warnings=[]
    )
    # Classification deterministe (carburant) => confiance 0.95, au-dessus du seuil de posting
    # automatique (0.85) : ces tests d'infrastructure atteignent donc post_entry sans revue de
    # posting supplementaire, tant que validate_proposal reste valid=True/requires_review=False.
    client.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")
    return client


def _classification_service() -> ClassificationService:
    return ClassificationService(llm_provider=MockLLMProvider())  # jamais appele (chemin deterministe)


def test_build_accounting_graph_se_construit():
    company_id, document_id = uuid4(), uuid4()
    graph = build_accounting_graph(
        accounting_client=_client_mock(document_id, company_id), classification_service=_classification_service()
    )
    assert isinstance(graph, CompiledStateGraph)
    assert {
        NODE_INITIALIZE,
        NODE_LOAD_EXTRACTION,
        NODE_NORMALIZE_DOCUMENT,
        NODE_CLASSIFY_ACCOUNTING_NATURE,
        NODE_HUMAN_REVIEW_CLASSIFICATION,
        NODE_SUGGEST_ACCOUNTING_TREATMENT,
        NODE_BUILD_ACCOUNTING_PROPOSAL,
        NODE_VALIDATE_WITH_JAVA,
        NODE_FINALIZE,
    }.issubset(graph.nodes.keys())


def test_execution_complete_traverse_tout_le_pipeline_jusqua_completed():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    client = _client_mock(document_id, company_id)
    graph = build_accounting_graph(
        accounting_client=client, classification_service=_classification_service(), checkpointer=creer_checkpointer_accounting()
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id), config=config)

    assert resultat["status"] is WorkflowStatus.COMPLETED
    assert resultat["validation_result"] is not None
    client.validate_proposal.assert_called_once()
    # Confiance de classification (0.95, deterministe) au-dessus du seuil de posting automatique,
    # validation Java propre : le posting a lieu sans revue humaine supplementaire (STEP 7.7).
    assert resultat["posting_result"].posted is True
    client.post_entry.assert_called_once()


def test_state_preservation_des_identifiants():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    graph = build_accounting_graph(
        accounting_client=_client_mock(document_id, company_id), classification_service=_classification_service()
    )

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id))

    assert resultat["workflow_id"] == workflow_id
    assert resultat["company_id"] == company_id
    assert resultat["document_id"] == document_id


def test_les_erreurs_existantes_ne_sont_jamais_silencieusement_effacees():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)
    state["errors"] = ["erreur survenue avant ce run (ex. reprise apres echec)"]
    graph = build_accounting_graph(
        accounting_client=_client_mock(document_id, company_id), classification_service=_classification_service()
    )

    resultat = graph.invoke(state)

    assert resultat["errors"] == ["erreur survenue avant ce run (ex. reprise apres echec)"]


def test_thread_id_est_accepte_par_le_graph():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    graph = build_accounting_graph(
        accounting_client=_client_mock(document_id, company_id),
        classification_service=_classification_service(),
        checkpointer=creer_checkpointer_accounting(),
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id), config=config)

    assert resultat["status"] is WorkflowStatus.COMPLETED
    # Le thread_id a bien identifie une execution retrouvable par le checkpointer.
    etat_courant = graph.get_state(config)
    assert etat_courant.values["workflow_id"] == workflow_id


def test_checkpoint_enregistre_lhistorique_de_lexecution_et_permet_la_reprise():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    graph = build_accounting_graph(
        accounting_client=_client_mock(document_id, company_id),
        classification_service=_classification_service(),
        checkpointer=creer_checkpointer_accounting(),
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}

    graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id), config=config)

    historique = list(graph.get_state_history(config))
    # Un checkpoint par etape traversee (initialize, load_extraction, normalize_document,
    # classify_accounting_nature, suggest_accounting_treatment, build_accounting_proposal,
    # validate_with_java, post_entry, finalize) au minimum - le pipeline etendu (STEP 6/7) ne casse
    # pas la persistance de l'historique deja validee en STEP 3/5.
    assert len(historique) >= 9
    noeuds_traverses = {checkpoint.metadata.get("source") for checkpoint in historique}
    assert "loop" in noeuds_traverses or "input" in noeuds_traverses

    etat_final = graph.get_state(config)
    assert etat_final.next == ()  # plus rien a executer : le workflow est bien termine


class _EtatMiniGraphInterrupt(TypedDict, total=False):
    """State ad-hoc, volontairement decouple d'AccountingState : ce mini-graph ne prouve que
    l'infrastructure interrupt/resume de LangGraph elle-meme, jamais une regle metier AI Accounting
    (voir consigne STEP 3.11 - a ne surtout pas brancher sur le vrai accounting_graph)."""

    valeur_entree: str
    decision_humaine: str


def _review_gate_node(state: _EtatMiniGraphInterrupt) -> _EtatMiniGraphInterrupt:
    from langgraph.types import interrupt

    decision = interrupt({"question": "Approuver ?", "valeur_entree": state.get("valeur_entree")})
    return {"decision_humaine": decision}


def _build_mini_graph_interrupt() -> CompiledStateGraph:
    graph = StateGraph(_EtatMiniGraphInterrupt)
    graph.add_node("review_gate", _review_gate_node)
    graph.add_edge(START, "review_gate")
    graph.add_edge("review_gate", END)
    return graph.compile(checkpointer=InMemorySaver())


def test_infrastructure_interrupt_puis_resume_fonctionne():
    graph = _build_mini_graph_interrupt()
    config = {"configurable": {"thread_id": str(uuid4())}}

    resultat_interrompu = graph.invoke({"valeur_entree": "facture X"}, config=config)

    assert "__interrupt__" in resultat_interrompu
    interruptions = resultat_interrompu["__interrupt__"]
    assert len(interruptions) == 1
    assert isinstance(interruptions[0], Interrupt)
    assert interruptions[0].value["question"] == "Approuver ?"

    etat_en_pause = graph.get_state(config)
    assert etat_en_pause.next == ("review_gate",)  # pas encore termine, en attente d'une decision

    resultat_final = graph.invoke(Command(resume="approuve"), config=config)

    assert "__interrupt__" not in resultat_final
    assert resultat_final["decision_humaine"] == "approuve"
    assert graph.get_state(config).next == ()
