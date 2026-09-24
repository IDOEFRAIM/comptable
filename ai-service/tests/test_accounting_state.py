from decimal import Decimal
from uuid import uuid4

from app.agent.state.accounting import (
    ACCOUNTING_STATE_ADAPTER,
    AccountingState,
    nouvel_etat_initial,
)
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountingTreatment,
    AccountPurpose,
    ApprovalResult,
    DocumentExtractionSnapshot,
    NormalizedAccountingDocument,
    PostingResult,
    ProposedAccountingLine,
    ResolvedAccountInfo,
    SuggestedAccount,
    TaxTreatment,
    ValidationResult,
    WorkflowStatus,
)

_FORBIDDEN_KEY_SUBSTRINGS = ("token", "password", "secret", "credential", "api_key", "apikey")


def _etat_complet(workflow_id, company_id, document_id) -> AccountingState:
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.94,
        requires_review=False,
        suggested_accounts=[SuggestedAccount(purpose=AccountPurpose.ACHAT_SERVICE, confidence=0.94)],
    )
    proposal = AccountingProposal(
        workflow_id=workflow_id,
        document_id=document_id,
        company_id=company_id,
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Prestation")],
        montant_tva=Decimal(18000),
        montant_ttc=Decimal(118000),
        classification=classification,
        idempotency_key=f"{document_id}:ACHAT",
    )
    treatment = AccountingTreatment(
        expense_purpose=AccountPurpose.ACHAT_SERVICE,
        tax_purpose=AccountPurpose.TVA_RECUPERABLE,
        counterparty_purpose=AccountPurpose.FOURNISSEUR,
        confidence=0.94,
    )
    return AccountingState(
        workflow_id=workflow_id,
        company_id=company_id,
        document_id=document_id,
        extraction=DocumentExtractionSnapshot(
            document_id=document_id, company_id=company_id, montant_ttc=Decimal(118000), confiance=0.92
        ),
        normalized_document=NormalizedAccountingDocument(
            document_id=document_id, company_id=company_id, montant_ttc=Decimal(118000), extraction_confidence=0.92
        ),
        classification=classification,
        accounting_treatment=treatment,
        resolved_accounts=[
            ResolvedAccountInfo(
                status="RESOLVED", source="MAPPING_GENERIQUE", compte_numero="621", explication="Resolu."
            )
        ],
        proposal=proposal,
        validation_result=ValidationResult(valid=True, requires_review=False),
        approval_required=False,
        approval_result=ApprovalResult(approved=True, comment="RAS"),
        posting_result=PostingResult(posted=True, entry_id=uuid4(), entry_number="ACH-2026-0001", journal="ACH"),
        status=WorkflowStatus.COMPLETED,
        errors=[],
    )


def test_creation_minimale_via_le_helper():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)

    assert state["workflow_id"] == workflow_id
    assert state["company_id"] == company_id
    assert state["document_id"] == document_id
    assert state["status"] is WorkflowStatus.PENDING
    assert state["errors"] == []
    # Un state minimal ne porte aucun resultat intermediaire - absent, pas None.
    assert "extraction" not in state
    assert "classification" not in state
    assert "proposal" not in state
    assert "posting_result" not in state


def test_creation_minimale_par_litteral_dict_sans_le_helper():
    # AccountingState est un TypedDict : un dict litteral portant uniquement les cles obligatoires
    # doit rester une construction valide, sans passer par nouvel_etat_initial.
    state: AccountingState = {"workflow_id": uuid4(), "company_id": uuid4(), "document_id": uuid4()}
    assert "status" not in state
    assert "errors" not in state


def test_state_complet_porte_tous_les_resultats_intermediaires():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = _etat_complet(workflow_id, company_id, document_id)

    assert state["extraction"].montant_ttc == Decimal(118000)
    assert state["normalized_document"].montant_ttc == Decimal(118000)
    assert state["classification"].confidence == 0.94
    assert state["accounting_treatment"].expense_purpose is AccountPurpose.ACHAT_SERVICE
    assert state["resolved_accounts"][0].compte_numero == "621"
    assert state["proposal"].lines[0].purpose is AccountPurpose.ACHAT_SERVICE
    assert state["validation_result"].valid is True
    assert state["approval_required"] is False
    assert state["approval_result"].approved is True
    assert state["posting_result"].posted is True
    assert state["status"] is WorkflowStatus.COMPLETED


def test_serialisation_round_trip_state_minimal():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)

    payload = ACCOUNTING_STATE_ADAPTER.dump_json(state)
    reconstruit = ACCOUNTING_STATE_ADAPTER.validate_json(payload)

    assert reconstruit == state
    assert reconstruit["workflow_id"] == workflow_id  # reste un vrai UUID, pas une chaine


def test_serialisation_round_trip_state_complet_preserve_les_types_imbriques():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = _etat_complet(workflow_id, company_id, document_id)

    payload = ACCOUNTING_STATE_ADAPTER.dump_json(state)
    reconstruit = ACCOUNTING_STATE_ADAPTER.validate_json(payload)

    assert reconstruit == state
    assert isinstance(reconstruit["proposal"], AccountingProposal)
    assert isinstance(reconstruit["classification"], AccountingClassification)
    assert reconstruit["proposal"].lines[0].montant_ht == Decimal(100000)
    assert reconstruit["status"] is WorkflowStatus.COMPLETED
    assert reconstruit["posting_result"].entry_number == "ACH-2026-0001"


def test_workflow_status_couvre_la_semantique_minimum_requise():
    valeurs = {s.value for s in WorkflowStatus}
    # WAITING_REVIEW du plan correspond a AWAITING_HUMAN_REVIEW deja existant (meme semantique,
    # nom conserve tel quel plutot que renomme - un seul enum, jamais deux).
    assert {"PENDING", "RUNNING", "AWAITING_HUMAN_REVIEW", "COMPLETED", "FAILED"} == valeurs


def test_mise_a_jour_partielle_ne_touche_pas_le_reste_du_state():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)
    state["status"] = WorkflowStatus.RUNNING

    # Un futur node ne renvoie qu'une mise a jour partielle (ex. classify_accounting_nature ne
    # renvoie que `classification`) - simule ici par un merge dict, la meme mecanique que LangGraph
    # applique par defaut (derniere ecriture gagne, pas de reconstruction complete du state).
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.NON_APPLICABLE,
        confidence=0.4,
        requires_review=True,
    )
    mise_a_jour: AccountingState = {"classification": classification}
    nouvel_etat: AccountingState = {**state, **mise_a_jour}

    assert nouvel_etat["classification"] is classification
    assert nouvel_etat["workflow_id"] == workflow_id
    assert nouvel_etat["status"] is WorkflowStatus.RUNNING
    assert "proposal" not in nouvel_etat

    # Un deuxieme node met a jour uniquement validation_result - classification precedente reste intacte.
    validation = ValidationResult(valid=False, requires_review=True, errors=["Compte non configure"])
    etat_suivant: AccountingState = {**nouvel_etat, "validation_result": validation}
    assert etat_suivant["classification"] is classification
    assert etat_suivant["validation_result"] is validation


def test_aucun_secret_ou_credential_dans_les_cles_du_state():
    for cle in AccountingState.__annotations__:
        cle_normalisee = cle.lower()
        assert not any(motif in cle_normalisee for motif in _FORBIDDEN_KEY_SUBSTRINGS), cle


def test_aucun_secret_dans_un_state_complet_serialise():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = _etat_complet(workflow_id, company_id, document_id)
    payload = ACCOUNTING_STATE_ADAPTER.dump_json(state).decode().lower()

    for motif in _FORBIDDEN_KEY_SUBSTRINGS:
        assert motif not in payload
