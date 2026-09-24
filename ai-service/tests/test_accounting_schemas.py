from decimal import Decimal
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    NatureAchat,
    NormalizedAccountingDocument,
    PostingResult,
    ProposedAccountingLine,
    ResolvedAccountInfo,
    SuggestedAccount,
    TaxTreatment,
    ValidationResult,
    WorkflowResult,
    WorkflowStatus,
)


def test_account_purpose_a_exactement_les_23_valeurs_miroir_du_java():
    valeurs = {p.value for p in AccountPurpose}
    assert len(valeurs) == 23
    # Un echantillon representatif des 5 groupes (achats/ventes/tva/tresorerie/resultat) plutot
    # que les 23 - le nombre exact ci-dessus suffit a detecter une valeur ajoutee/retiree par erreur.
    for attendu in ("ACHAT_SERVICE", "VENTE_MARCHANDISE", "TVA_RECUPERABLE", "BANQUE", "RESULTAT_PERTE"):
        assert attendu in valeurs


def test_normalized_document_construit_depuis_un_payload_camel_case_comme_cote_java():
    document_id = uuid4()
    company_id = uuid4()
    doc = NormalizedAccountingDocument.model_validate(
        {
            "documentId": str(document_id),
            "companyId": str(company_id),
            "montantHt": "100000",
            "montantTva": "18000",
            "montantTtc": "118000",
            "extractionConfidence": 0.92,
        }
    )
    assert doc.document_id == document_id
    assert doc.montant_ttc == Decimal(118000)
    assert doc.devise == "XOF"
    assert doc.normalization_warnings == []


def test_normalized_document_accepte_aussi_les_kwargs_snake_case():
    doc = NormalizedAccountingDocument(
        document_id=uuid4(),
        company_id=uuid4(),
        montant_ttc=Decimal(50000),
        extraction_confidence=0.5,
        normalization_warnings=["montant_ht absent"],
    )
    assert doc.normalization_warnings == ["montant_ht absent"]


def test_confidence_hors_bornes_est_refusee():
    with pytest.raises(ValidationError):
        SuggestedAccount(purpose=AccountPurpose.ACHAT_SERVICE, confidence=1.5)
    with pytest.raises(ValidationError):
        SuggestedAccount(purpose=AccountPurpose.ACHAT_SERVICE, confidence=-0.1)


def test_accounting_classification_serialise_par_alias_pour_le_futur_appel_java():
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        nature_achat=NatureAchat.SERVICE,
        suggested_accounts=[SuggestedAccount(purpose=AccountPurpose.ACHAT_SERVICE, confidence=0.94)],
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.94,
        requires_review=False,
        rationale="Libelle correspond a une prestation de service recurrente.",
    )
    payload = classification.model_dump(by_alias=True)
    assert payload["operationType"] == "ACHAT"
    assert payload["natureAchat"] == "SERVICE"
    assert payload["taxTreatment"] == "TVA_RECUPERABLE"
    assert payload["requiresReview"] is False
    assert payload["suggestedAccounts"][0]["purpose"] == "ACHAT_SERVICE"


def test_accounting_proposal_regroupe_classification_et_lignes():
    workflow_id, document_id, company_id = uuid4(), uuid4(), uuid4()
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.7,
        requires_review=True,
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
    assert proposal.lines[0].purpose is AccountPurpose.ACHAT_SERVICE
    assert proposal.classification.requires_review is True
    payload = proposal.model_dump(by_alias=True)
    assert payload["idempotencyKey"] == f"{document_id}:ACHAT"
    assert payload["lines"][0]["montantHt"] == Decimal(100000)


def test_validation_result_deserialise_une_reponse_java_avec_comptes_resolus():
    result = ValidationResult.model_validate(
        {
            "valid": True,
            "requiresReview": False,
            "resolvedAccounts": [
                {
                    "status": "RESOLVED",
                    "compteId": str(uuid4()),
                    "compteNumero": "621",
                    "compteIntitule": "Sous-traitance generale",
                    "source": "MAPPING_GENERIQUE",
                    "explication": "Resolu via le parametrage comptable de l'entreprise.",
                }
            ],
        }
    )
    assert result.valid is True
    assert result.resolved_accounts[0].compte_numero == "621"


def test_posting_result_sans_ecriture_quand_non_poste():
    result = PostingResult(posted=False, errors=["Compte non configure"])
    assert result.entry_id is None
    assert result.posted is False


def test_workflow_result_agrege_tout_le_pipeline_et_round_trip_par_alias():
    ids = {"workflow_id": uuid4(), "document_id": uuid4(), "company_id": uuid4()}
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.NON_APPLICABLE,
        confidence=0.99,
        requires_review=False,
    )
    result = WorkflowResult(
        workflow_id=ids["workflow_id"],
        document_id=ids["document_id"],
        company_id=ids["company_id"],
        status=WorkflowStatus.COMPLETED,
        classification=classification,
        posting=PostingResult(posted=True, entry_id=uuid4(), entry_number="ACH-2026-0001", journal="ACH"),
    )
    payload = result.model_dump(by_alias=True)
    reconstruit = WorkflowResult.model_validate(payload)
    assert reconstruit.status is WorkflowStatus.COMPLETED
    assert reconstruit.posting.posted is True
    assert reconstruit.proposal is None
    assert reconstruit.errors == []


def test_resolved_account_info_source_invalide_est_refusee():
    with pytest.raises(ValidationError):
        ResolvedAccountInfo(status="RESOLVED", source="SOURCE_INEXISTANTE", explication="x")
