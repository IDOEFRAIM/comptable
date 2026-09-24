from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from app.agent.graph.accounting import MissingAccountingProposalError, creer_validate_with_java_node
from app.agent.state.accounting import nouvel_etat_initial
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    ProposedAccountingLine,
    TaxTreatment,
    ValidationResult,
)
from app.services.accounting_client import AccountingClient, AccountingServiceUnavailableError


def _proposal(workflow_id, document_id, company_id) -> AccountingProposal:
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.9,
        requires_review=False,
    )
    return AccountingProposal(
        workflow_id=workflow_id,
        document_id=document_id,
        company_id=company_id,
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
        montant_tva=Decimal(18000),
        montant_ttc=Decimal(118000),
        classification=classification,
        idempotency_key="k",
    )


def _etat_avec_proposal():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)
    state["proposal"] = _proposal(workflow_id, document_id, company_id)
    return state


def test_success_stocke_le_validation_result_et_calcule_approval_required():
    state = _etat_avec_proposal()
    client = MagicMock(spec=AccountingClient)
    resultat_java = ValidationResult(valid=True, requires_review=False, resolved_accounts=[], errors=[], warnings=[])
    client.validate_proposal.return_value = resultat_java

    node = creer_validate_with_java_node(client)
    mise_a_jour = node(state)

    client.validate_proposal.assert_called_once_with(state["proposal"])
    assert mise_a_jour["validation_result"] is resultat_java
    assert mise_a_jour["approval_required"] is False


def test_review_required_est_reporte_sur_approval_required():
    state = _etat_avec_proposal()
    client = MagicMock(spec=AccountingClient)
    client.validate_proposal.return_value = ValidationResult(
        valid=True, requires_review=True, resolved_accounts=[], errors=[], warnings=[]
    )

    node = creer_validate_with_java_node(client)
    mise_a_jour = node(state)

    assert mise_a_jour["approval_required"] is True


def test_proposition_invalide_est_stockee_telle_quelle_sans_validation_locale():
    state = _etat_avec_proposal()
    client = MagicMock(spec=AccountingClient)
    resultat_java = ValidationResult(
        valid=False, requires_review=True, resolved_accounts=[], errors=["Compte non configure"], warnings=[]
    )
    client.validate_proposal.return_value = resultat_java

    node = creer_validate_with_java_node(client)
    mise_a_jour = node(state)

    # Le node stocke exactement ce que Java a renvoye - jamais une re-derivation locale de "valid".
    assert mise_a_jour["validation_result"] is resultat_java
    assert mise_a_jour["validation_result"].valid is False
    assert mise_a_jour["validation_result"].errors == ["Compte non configure"]
    assert mise_a_jour["approval_required"] is True


def test_erreur_client_nest_jamais_masquee_et_le_state_dorigine_reste_intact():
    state = _etat_avec_proposal()
    proposal_avant = state["proposal"]
    workflow_id_avant = state["workflow_id"]
    document_id_avant = state["document_id"]
    company_id_avant = state["company_id"]
    client = MagicMock(spec=AccountingClient)
    client.validate_proposal.side_effect = AccountingServiceUnavailableError("Java indisponible", 503)

    node = creer_validate_with_java_node(client)
    with pytest.raises(AccountingServiceUnavailableError) as exc_info:
        node(state)

    assert exc_info.value.retryable is True
    # Le node n'a jamais mute (ni remplace) le state recu en entree.
    assert state["proposal"] is proposal_avant
    assert state["workflow_id"] == workflow_id_avant
    assert state["document_id"] == document_id_avant
    assert state["company_id"] == company_id_avant


def test_proposal_absent_leve_une_erreur_explicite_jamais_un_keyerror_ou_attributeerror():
    workflow_id, company_id, document_id = uuid4(), uuid4(), uuid4()
    state = nouvel_etat_initial(workflow_id, company_id, document_id)
    client = MagicMock(spec=AccountingClient)

    node = creer_validate_with_java_node(client)
    with pytest.raises(MissingAccountingProposalError):
        node(state)

    client.validate_proposal.assert_not_called()
