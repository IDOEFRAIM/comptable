"""STEP 11.1 : CfoState - meme discipline que test_accounting_state.py/test_financial_health_graph
(TypedDict, mise a jour partielle, serialisable, aucune donnee sensible)."""

from datetime import UTC, datetime
from decimal import Decimal
from uuid import uuid4

from app.agent.state.cfo import CFO_STATE_ADAPTER, CfoState, nouvel_etat_initial
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    ProposedAccountingLine,
    TaxTreatment,
    WorkflowStatus,
)
from app.schemas.cfo import (
    CfoIntent,
    CfoRecommendation,
    CfoResponse,
    FinancialFact,
    FinancialFactDataType,
    ToolResultRecord,
)

_FORBIDDEN_KEY_SUBSTRINGS = ("token", "password", "secret", "credential", "api_key", "apikey")


def test_creation_minimale_via_le_helper():
    conversation_id, workflow_id, company_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(conversation_id, workflow_id, company_id, "Comment va mon entreprise ?")

    assert state["conversation_id"] == conversation_id
    assert state["company_id"] == company_id
    assert state["user_message"] == "Comment va mon entreprise ?"
    assert state["status"] is WorkflowStatus.PENDING
    assert state["tool_calls"] == []
    assert state["tool_results"] == []
    assert state["financial_facts"] == []
    # Un state minimal ne porte aucun resultat intermediaire - absent, pas None.
    assert "intent" not in state
    assert "response" not in state
    assert "pending_proposal" not in state


def test_creation_avec_une_proposition_deja_construite_ailleurs():
    conversation_id, workflow_id, company_id = uuid4(), uuid4(), uuid4()
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT, accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE, confidence=0.95, requires_review=False,
    )
    proposal = AccountingProposal(
        workflow_id=workflow_id, document_id=uuid4(), company_id=company_id,
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
        montant_ttc=Decimal(118000), classification=classification, idempotency_key="k",
    )

    state = nouvel_etat_initial(
        conversation_id, workflow_id, company_id, "Comptabilise cette facture.", pending_proposal=proposal
    )

    assert state["pending_proposal"] is proposal


def test_creation_minimale_par_litteral_dict_sans_le_helper():
    state: CfoState = {"conversation_id": uuid4(), "workflow_id": uuid4(), "company_id": uuid4(), "user_message": "test"}
    assert "status" not in state
    assert "intent" not in state


def test_aucune_cle_sensible_dans_un_state_complet():
    state = _etat_complet()
    payload = CFO_STATE_ADAPTER.dump_python(state, by_alias=True)
    _verifier_aucune_cle_sensible(payload)


def _verifier_aucune_cle_sensible(valeur) -> None:
    if isinstance(valeur, dict):
        for cle, sous_valeur in valeur.items():
            assert not any(motif in str(cle).lower() for motif in _FORBIDDEN_KEY_SUBSTRINGS), cle
            _verifier_aucune_cle_sensible(sous_valeur)
    elif isinstance(valeur, list):
        for element in valeur:
            _verifier_aucune_cle_sensible(element)


def _etat_complet() -> CfoState:
    conversation_id, workflow_id, company_id = uuid4(), uuid4(), uuid4()
    fact = FinancialFact(
        name="cash", value=Decimal(12500000), currency="XOF", period="2026-08", as_of="2026-08-27",
        source_tool="finance.get_cash_position", data_type=FinancialFactDataType.ACTUAL,
    )
    response = CfoResponse(
        summary="Situation stable.", facts=[fact], analysis=["Le cash reste positif."],
        risks=["Creances elevees."],
        recommendations=[CfoRecommendation(priority="HIGH", action="Relancer les creances", reason="creances=21.8M", supporting_facts=["cash"])],
        missing_data=[], tools_used=["finance.get_cash_position"],
    )
    return CfoState(
        conversation_id=conversation_id, workflow_id=workflow_id, company_id=company_id,
        user_message="Comment va mon entreprise ?", intent=CfoIntent.FINANCIAL_HEALTH, intent_confidence=0.9,
        planned_tools=[], tool_calls=["finance.get_cash_position"],
        tool_results=[ToolResultRecord(tool_name="finance.get_cash_position", arguments={}, result={"total": "12500000"},
                                        timestamp=datetime.now(UTC))],
        financial_facts=[fact], scenario_result=None, analysis=response, response=response,
        status=WorkflowStatus.COMPLETED, errors=[],
    )


def test_serialisation_round_trip_state_complet_preserve_les_types_imbriques():
    state = _etat_complet()

    payload = CFO_STATE_ADAPTER.dump_json(state)
    reconstruit = CFO_STATE_ADAPTER.validate_json(payload)

    assert reconstruit == state
    assert isinstance(reconstruit["response"], CfoResponse)
    assert reconstruit["financial_facts"][0].value == Decimal(12500000)
    assert reconstruit["status"] is WorkflowStatus.COMPLETED


def test_serialisation_round_trip_state_minimal():
    conversation_id, workflow_id, company_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(conversation_id, workflow_id, company_id, "Pourquoi mon cash baisse ?")

    payload = CFO_STATE_ADAPTER.dump_json(state)
    reconstruit = CFO_STATE_ADAPTER.validate_json(payload)

    assert reconstruit == state
    assert reconstruit["company_id"] == company_id  # reste un vrai UUID, pas une chaine
