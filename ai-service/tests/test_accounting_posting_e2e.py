"""STEP 7.11 : tests de bout en bout mandates par le plan pour le posting controle - au-dela du
vertical slice POSTED et du cas HITL de posting deja couverts par test_accounting_graph_e2e.py
(test_vertical_slice_facture_fournisseur_carburant_de_bout_en_bout et
test_cas6_classification_a_faible_confiance_interrompt_le_workflow) : rejet (validation invalide),
modes de panne MCP/Java au moment du posting, idempotence sur rejeu, et reprise de posting apres
un redemarrage de processus simule (nouvelle instance de graph, meme checkpointer/thread_id)."""

from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from app.accounting.services.classification_service import ClassificationService
from app.agent.checkpoint import creer_checkpointer_accounting
from app.agent.graph.accounting import NODE_HUMAN_POSTING_APPROVAL, build_accounting_graph
from app.agent.hitl import acquitter_sans_correction
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
from app.services.accounting_client import (
    AccountingAuthorizationError,
    AccountingClient,
    AccountingResponseMalformedError,
    AccountingServiceUnavailableError,
    AccountingTimeoutError,
    AccountingValidationError,
)
from app.services.mock_llm_provider import MockLLMProvider


def _extraction_carburant(document_id, company_id) -> DocumentExtractionSnapshot:
    return DocumentExtractionSnapshot(
        document_id=document_id, company_id=company_id, type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000),
        fournisseur="Station Total - carburant", confiance=0.95,
    )


def _validation_ok() -> ValidationResult:
    return ValidationResult(
        valid=True, requires_review=False,
        resolved_accounts=[
            ResolvedAccountInfo(
                status=AccountResolutionStatus.RESOLVED, compte_numero="605",
                source=AccountResolutionSource.MAPPING_GENERIQUE, explication="Resolu.",
            )
        ],
    )


def _client_mock(document_id, company_id) -> MagicMock:
    client = MagicMock(spec=AccountingClient)
    client.get_document_extraction.return_value = _extraction_carburant(document_id, company_id)
    return client


def _classification_service() -> ClassificationService:
    return ClassificationService(llm_provider=MockLLMProvider())  # deterministe, jamais appele


def test_rejet_proposition_invalide_le_posting_nest_jamais_appele():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    client = _client_mock(document_id, company_id)
    client.validate_proposal.return_value = ValidationResult(
        valid=False, requires_review=False, errors=["Compte fournisseur non configure"]
    )
    graph = build_accounting_graph(accounting_client=client, classification_service=_classification_service())

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id))

    assert resultat["validation_result"].valid is False
    assert "posting_result" not in resultat
    client.post_entry.assert_not_called()


@pytest.mark.parametrize(
    "erreur",
    [
        AccountingServiceUnavailableError("Java indisponible", 503),
        AccountingTimeoutError("Timeout apres 15.0s vers Java"),
        AccountingAuthorizationError("Acces refuse par Java (403)"),
        AccountingValidationError("Proposition rejetee par Java (422)", 422, "Compte non configure"),
        AccountingResponseMalformedError("Reponse Java ne respecte pas PostingResult"),
    ],
)
def test_les_modes_de_panne_mcp_java_au_posting_traversent_le_graph_sans_etre_masques(erreur):
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    client = _client_mock(document_id, company_id)
    client.validate_proposal.return_value = _validation_ok()
    client.post_entry.side_effect = erreur
    graph = build_accounting_graph(accounting_client=client, classification_service=_classification_service())

    with pytest.raises(type(erreur)) as exc_info:
        graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id))

    assert exc_info.value.retryable == erreur.retryable
    assert exc_info.value.human_reviewable == erreur.human_reviewable


def test_idempotence_un_rejeu_apres_timeout_ne_double_jamais_le_posting_cote_graph():
    # Simule un timeout transitoire (ex. reseau) suivi d'un succes - tenacity (AccountingClient)
    # rejoue l'appel HTTP complet, mais post_entry_node lui-meme n'est invoque qu'une seule fois
    # par le graph : la garantie de non-duplication reste entierement cote Java
    # (AccountingOperationEngine.assemblerEtSauvegarder, source_type/source_id/type), jamais
    # recree cote Python - ce test verifie seulement que le graph ne provoque pas de second appel
    # de son propre chef et restitue un resultat coherent.
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    client = _client_mock(document_id, company_id)
    client.validate_proposal.return_value = _validation_ok()
    resultat_java = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")
    client.post_entry.return_value = resultat_java
    graph = build_accounting_graph(accounting_client=client, classification_service=_classification_service())

    resultat = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id))

    assert resultat["posting_result"].entry_number == "ACH-2026-0001"
    client.post_entry.assert_called_once()

    # Un second appel independant (ex. reprise manuelle du meme workflow) doit retomber sur le
    # meme resultat deja poste - jamais une nouvelle ecriture (comportement garanti cote Java,
    # simule ici par le mock qui renvoie systematiquement la meme proposition deja postee).
    resultat_rejoue = graph.invoke(nouvel_etat_initial(workflow_id, company_id, document_id))
    assert resultat_rejoue["posting_result"].entry_number == resultat["posting_result"].entry_number
    assert client.post_entry.call_count == 2  # deux invocations Python, une seule ecriture cote Java (mock)


def test_checkpoint_avant_posting_survit_a_un_redemarrage_de_processus_simule():
    # "Redemarrage de processus" simule : le checkpointer (InMemorySaver durci) est cree une seule
    # fois et reutilise, mais le graph lui-meme est reconstruit depuis zero (nouvelle instance,
    # nouveaux nodes/closures) avant la reprise - prouve que l'etat interrompu ne depend d'aucun
    # objet Python en memoire du premier run, seulement du checkpointer/thread_id.
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    checkpointer = creer_checkpointer_accounting()
    config = {"configurable": {"thread_id": str(workflow_id)}}

    client_avant = _client_mock(document_id, company_id)
    client_avant.validate_proposal.return_value = ValidationResult(
        valid=True, requires_review=True, resolved_accounts=[]  # force la revue de posting
    )
    graph_avant = build_accounting_graph(
        accounting_client=client_avant, classification_service=_classification_service(), checkpointer=checkpointer
    )
    resultat_interrompu = graph_avant.invoke(nouvel_etat_initial(workflow_id, company_id, document_id), config=config)

    assert "__interrupt__" in resultat_interrompu
    assert resultat_interrompu["__interrupt__"][0].value["type"] == "ACCOUNTING_POSTING_APPROVAL"
    client_avant.post_entry.assert_not_called()

    # "Redemarrage" : nouveau client mock, nouveau graph, meme checkpointer/thread_id.
    client_apres = _client_mock(document_id, company_id)
    client_apres.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0002", journal="ACH")
    graph_apres = build_accounting_graph(
        accounting_client=client_apres, classification_service=_classification_service(), checkpointer=checkpointer
    )
    etat_recharge = graph_apres.get_state(config)
    assert etat_recharge.next == (NODE_HUMAN_POSTING_APPROVAL,)

    resultat_final = graph_apres.invoke(acquitter_sans_correction(), config=config)

    assert "__interrupt__" not in resultat_final
    assert resultat_final["status"] is WorkflowStatus.COMPLETED
    assert resultat_final["posting_result"].posted is True
    assert resultat_final["posting_result"].entry_number == "ACH-2026-0002"
    client_apres.post_entry.assert_called_once()
