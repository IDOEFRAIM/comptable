"""Tests STEP 6 : le vrai chemin document -> proposition comptable, de bout en bout, avec VLM/
LLM/Java entierement mockes (aucun appel reseau reel) - voir accounting.py pour le graph complet."""

from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

from app.accounting.services.classification_service import ClassificationService
from app.agent.checkpoint import creer_checkpointer_accounting
from app.agent.graph.accounting import (
    NODE_BUILD_ACCOUNTING_PROPOSAL,
    NODE_CLASSIFY_ACCOUNTING_NATURE,
    NODE_FINALIZE,
    NODE_HUMAN_POSTING_APPROVAL,
    NODE_HUMAN_REVIEW_CLASSIFICATION,
    NODE_INITIALIZE,
    NODE_LOAD_EXTRACTION,
    NODE_NORMALIZE_DOCUMENT,
    NODE_POST_ENTRY,
    NODE_SUGGEST_ACCOUNTING_TREATMENT,
    NODE_VALIDATE_WITH_JAVA,
    build_accounting_graph,
    creer_load_extraction_node,
)
from app.agent.hitl import acquitter_sans_correction, reprendre_avec_correction
from app.agent.state.accounting import nouvel_etat_initial
from app.schemas.accounting import (
    AccountResolutionSource,
    AccountResolutionStatus,
    DocumentExtractionSnapshot,
    PostingResult,
    ResolvedAccountInfo,
    TypeDocument,
    ValidationResult,
    WorkflowStatus,
)
from app.services.accounting_client import AccountingClient, AccountingServiceUnavailableError
from app.services.mock_llm_provider import MockLLMProvider


def _snapshot_carburant(document_id, company_id) -> DocumentExtractionSnapshot:
    return DocumentExtractionSnapshot(
        document_id=document_id,
        company_id=company_id,
        type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000),
        montant_tva=Decimal(18000),
        montant_ttc=Decimal(118000),
        fournisseur="Station Total - carburant",
        devise="XOF",
        confiance=0.92,
    )


def _client_mock(*, extraction: DocumentExtractionSnapshot, valid: bool = True, requires_review: bool = False) -> MagicMock:
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.return_value = extraction
    client.validate_proposal.return_value = ValidationResult(
        valid=valid,
        requires_review=requires_review,
        resolved_accounts=[
            ResolvedAccountInfo(
                status=AccountResolutionStatus.RESOLVED,
                source=AccountResolutionSource.MAPPING_GENERIQUE,
                compte_numero="605",
                explication="Resolu via le parametrage comptable de l'entreprise.",
            )
        ],
        errors=[],
        warnings=[],
    )
    client.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")
    return client


def test_load_extraction_node_appelle_le_client_et_ne_masque_pas_ses_erreurs():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.side_effect = AccountingServiceUnavailableError("Java indisponible", 503)

    node = creer_load_extraction_node(client)

    import pytest

    with pytest.raises(AccountingServiceUnavailableError):
        node(state)
    client.get_document_extraction.assert_called_once_with(document_id)


def test_vertical_slice_facture_fournisseur_carburant_de_bout_en_bout():
    """Le vertical slice obligatoire (facture fournisseur carburant) : document -> VLM (mock) ->
    normalisation -> classification deterministe (aucun LLM necessaire) -> traitement -> proposal
    -> validation Java (mock) -> COMPLETED, avec les comptes resolus par Java integres au state."""

    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    extraction = _snapshot_carburant(document_id, company_id)
    client = _client_mock(extraction=extraction)
    classification_service = ClassificationService(llm_provider=MockLLMProvider())  # jamais appele ici

    graph = build_accounting_graph(accounting_client=client, classification_service=classification_service)
    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id))

    assert resultat["status"] is WorkflowStatus.COMPLETED
    assert resultat["normalized_document"].montant_ttc == Decimal(118000)
    assert resultat["classification"].accounting_nature.value == "FUEL"
    assert resultat["accounting_treatment"].expense_purpose.value == "ACHAT_CONSOMMABLE"
    assert resultat["proposal"].lines[0].montant_ht == Decimal(100000)
    assert resultat["validation_result"].valid is True
    # Cas 7 : la resolution de compte par Java (AccountingAccountResolver) est bien integree.
    assert resultat["resolved_accounts"][0].compte_numero == "605"
    client.get_document_extraction.assert_called_once_with(document_id)
    client.validate_proposal.assert_called_once()
    # STEP 7 : classification deterministe (0.95) au-dessus du seuil de posting automatique et
    # validation Java propre => posting sans revue humaine supplementaire.
    assert resultat["posting_result"].posted is True
    assert resultat["posting_result"].entry_number == "ACH-2026-0001"
    client.post_entry.assert_called_once()


def test_build_accounting_graph_contient_tous_les_nouveaux_noeuds():
    client = _client_mock(extraction=_snapshot_carburant(uuid4(), uuid4()))
    graph = build_accounting_graph(accounting_client=client, classification_service=ClassificationService(llm_provider=MockLLMProvider()))

    assert {
        NODE_INITIALIZE,
        NODE_LOAD_EXTRACTION,
        NODE_NORMALIZE_DOCUMENT,
        NODE_CLASSIFY_ACCOUNTING_NATURE,
        NODE_HUMAN_REVIEW_CLASSIFICATION,
        NODE_SUGGEST_ACCOUNTING_TREATMENT,
        NODE_BUILD_ACCOUNTING_PROPOSAL,
        NODE_VALIDATE_WITH_JAVA,
        NODE_HUMAN_POSTING_APPROVAL,
        NODE_POST_ENTRY,
        NODE_FINALIZE,
    }.issubset(graph.nodes.keys())


def test_cas6_classification_a_faible_confiance_interrompt_le_workflow():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    extraction = DocumentExtractionSnapshot(
        document_id=document_id, company_id=company_id, type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(50000), montant_tva=Decimal(9000), montant_ttc=Decimal(59000),
        fournisseur="Divers Services SARL", confiance=0.7,
    )
    client = _client_mock(extraction=extraction)
    llm = MockLLMProvider(
        responses=[
            {
                "operation_type": "ACHAT",
                "accounting_nature": "OTHER",
                "suggested_accounts": [{"purpose": "ACHAT_SERVICE", "confidence": 0.5}],
                "tax_treatment": "TVA_RECUPERABLE",
                "confidence": 0.5,
                "requires_review": True,
            }
        ]
    )
    classification_service = ClassificationService(llm_provider=llm)
    graph = build_accounting_graph(
        accounting_client=client, classification_service=classification_service, checkpointer=creer_checkpointer_accounting()
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}

    resultat_interrompu = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id), config=config)

    assert "__interrupt__" in resultat_interrompu
    interruption = resultat_interrompu["__interrupt__"][0]
    assert interruption.value["reason"] == "low_confidence_classification"
    assert interruption.value["confidence"] == 0.5
    etat_en_pause = graph.get_state(config)
    assert etat_en_pause.next == (NODE_HUMAN_REVIEW_CLASSIFICATION,)
    # La proposition n'a pas encore ete construite ni envoyee a Java : l'IA propose, mais rien
    # n'est valide tant qu'un humain n'a pas ete informe de l'incertitude.
    client.validate_proposal.assert_not_called()

    # Reprise sans correction : app/agent/hitl.py est desormais le seul endroit du code a
    # connaitre le piege Command(resume={})/Command(resume=None) (voir sa docstring de module).
    resultat_apres_classification = graph.invoke(acquitter_sans_correction(), config=config)

    client.validate_proposal.assert_called_once()
    # STEP 7.7/7.8 : la confiance de classification (0.5, conservee telle quelle - jamais devinee a
    # la hausse) reste sous le seuil de posting automatique => une SECONDE interruption, distincte
    # de la premiere, est necessaire avant de poster reellement (ACCOUNTING_POSTING_APPROVAL).
    assert "__interrupt__" in resultat_apres_classification
    interruption_posting = resultat_apres_classification["__interrupt__"][0]
    assert interruption_posting.value["type"] == "ACCOUNTING_POSTING_APPROVAL"
    assert interruption_posting.value["reason"] == "confiance_classification_insuffisante"
    assert interruption_posting.value["document_id"] == str(document_id)
    etat_en_pause_posting = graph.get_state(config)
    assert etat_en_pause_posting.next == (NODE_HUMAN_POSTING_APPROVAL,)
    client.post_entry.assert_not_called()

    resultat_final = graph.invoke(acquitter_sans_correction(), config=config)

    assert "__interrupt__" not in resultat_final
    assert resultat_final["status"] is WorkflowStatus.COMPLETED
    assert resultat_final["classification"].confidence == 0.5  # conservee telle quelle, jamais devinee a la hausse
    assert resultat_final["approval_result"].approved is True
    assert resultat_final["posting_result"].posted is True
    client.post_entry.assert_called_once()


def test_cas6_reprise_avec_une_classification_corrigee_par_lhumain_est_prise_en_compte():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    extraction = DocumentExtractionSnapshot(
        document_id=document_id, company_id=company_id, montant_ht=Decimal(50000),
        montant_tva=Decimal(9000), montant_ttc=Decimal(59000), fournisseur="Divers Services SARL", confiance=0.7,
    )
    client = _client_mock(extraction=extraction)
    llm = MockLLMProvider(
        responses=[
            {
                "operation_type": "ACHAT",
                "accounting_nature": "OTHER",
                "suggested_accounts": [{"purpose": "ACHAT_SERVICE", "confidence": 0.4}],
                "tax_treatment": "TVA_RECUPERABLE",
                "confidence": 0.4,
                "requires_review": True,
            }
        ]
    )
    graph = build_accounting_graph(
        accounting_client=client,
        classification_service=ClassificationService(llm_provider=llm),
        checkpointer=creer_checkpointer_accounting(),
    )
    config = {"configurable": {"thread_id": str(workflow_id)}}
    graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id), config=config)

    correction = {
        "classification": {
            "operationType": "ACHAT",
            "accountingNature": "RENT",
            "suggestedAccounts": [{"purpose": "ACHAT_SERVICE", "confidence": 0.99}],
            "taxTreatment": "TVA_RECUPERABLE",
            "confidence": 0.99,
            "requiresReview": False,
        }
    }
    resultat_final = graph.invoke(reprendre_avec_correction(correction), config=config)

    assert resultat_final["classification"].accounting_nature.value == "RENT"
    assert resultat_final["classification"].confidence == 0.99
    assert resultat_final["status"] is WorkflowStatus.COMPLETED
    # Confiance corrigee (0.99) au-dessus du seuil de posting automatique : une seule interruption
    # (classification) a eu lieu, jamais une seconde pour l'approbation de posting.
    assert "__interrupt__" not in resultat_final
    assert resultat_final["posting_result"].posted is True
    client.post_entry.assert_called_once()
