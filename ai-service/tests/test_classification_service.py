from decimal import Decimal
from uuid import uuid4

import pytest

from app.accounting.services.classification_service import (
    ClassificationLlmError,
    ClassificationService,
)
from app.schemas.accounting import (
    AccountingNatureLabel,
    AccountPurpose,
    NormalizedAccountingDocument,
)
from app.services.mock_llm_provider import MockLLMProvider


def _document_ambigu() -> NormalizedAccountingDocument:
    return NormalizedAccountingDocument(
        document_id=uuid4(),
        company_id=uuid4(),
        fournisseur="Divers Services SARL",
        libelle="Prestations diverses",
        montant_ht=Decimal(50000),
        montant_tva=Decimal(9000),
        montant_ttc=Decimal(59000),
        extraction_confidence=0.9,
    )


def test_cas3_document_deterministe_ne_declenche_jamais_le_llm():
    document = NormalizedAccountingDocument(
        document_id=uuid4(), company_id=uuid4(), fournisseur="Station Total", libelle="carburant",
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000), extraction_confidence=0.9,
    )
    provider = MockLLMProvider()
    service = ClassificationService(llm_provider=provider)

    classification = service.classifier(document)

    assert classification.accounting_nature is AccountingNatureLabel.FUEL
    assert provider.calls == []  # jamais appele : la regle deterministe a suffi


def test_libelle_ambigu_declenche_bien_le_fallback_llm():
    provider = MockLLMProvider(
        responses=[
            {
                "operation_type": "ACHAT",
                "accounting_nature": "OTHER",
                "suggested_accounts": [{"purpose": "ACHAT_SERVICE", "confidence": 0.72}],
                "tax_treatment": "TVA_RECUPERABLE",
                "confidence": 0.72,
                "requires_review": True,
                "rationale": "Libelle trop generique pour une regle deterministe.",
            }
        ]
    )
    service = ClassificationService(llm_provider=provider)

    classification = service.classifier(_document_ambigu())

    assert len(provider.calls) == 1
    assert classification.accounting_nature is AccountingNatureLabel.OTHER
    assert classification.suggested_accounts[0].purpose is AccountPurpose.ACHAT_SERVICE
    assert classification.confidence == 0.72
    assert classification.requires_review is True


def test_cas4_sortie_llm_malformee_est_rejetee_par_le_schema():
    # Il manque des champs obligatoires (tax_treatment/confidence/requires_review) : le parser
    # structure doit rejeter, jamais deviner les champs manquants.
    provider = MockLLMProvider(responses=[{"operation_type": "ACHAT", "accounting_nature": "OTHER"}])
    service = ClassificationService(llm_provider=provider)

    with pytest.raises(ClassificationLlmError):
        service.classifier(_document_ambigu())


def test_cas4_purpose_invente_hors_du_vocabulaire_est_rejete():
    provider = MockLLMProvider(
        responses=[
            {
                "operation_type": "ACHAT",
                "accounting_nature": "OTHER",
                "suggested_accounts": [{"purpose": "FUEL", "confidence": 0.9}],  # pas un AccountPurpose reel
                "tax_treatment": "TVA_RECUPERABLE",
                "confidence": 0.9,
                "requires_review": False,
            }
        ]
    )
    service = ClassificationService(llm_provider=provider)

    with pytest.raises(ClassificationLlmError):
        service.classifier(_document_ambigu())


def test_cas5_timeout_llm_produit_une_erreur_exploitable_jamais_une_exception_opaque():
    provider = MockLLMProvider(responses=[TimeoutError("le provider n'a pas repondu a temps")])
    service = ClassificationService(llm_provider=provider)

    with pytest.raises(ClassificationLlmError):
        service.classifier(_document_ambigu())
