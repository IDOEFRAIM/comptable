from decimal import Decimal
from uuid import uuid4

from app.accounting.services.normalization import normaliser
from app.schemas.accounting import DocumentExtractionSnapshot, TypeDocument


def test_extraction_coherente_est_normalisee_sans_avertissement():
    extraction = DocumentExtractionSnapshot(
        document_id=uuid4(), company_id=uuid4(), type_document=TypeDocument.FACTURE_ACHAT,
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(118000),
        fournisseur="  Station Total  ", confiance=0.92,
    )

    document = normaliser(extraction)

    assert document.montant_ttc == Decimal(118000)
    assert document.fournisseur == "Station Total"  # espaces superflus retires
    assert document.document_type is TypeDocument.FACTURE_ACHAT
    assert document.normalization_warnings == []
    assert document.extraction_confidence == 0.92


def test_incoherence_arithmetique_est_signalee_jamais_corrigee_silencieusement():
    extraction = DocumentExtractionSnapshot(
        document_id=uuid4(), company_id=uuid4(),
        montant_ht=Decimal(100000), montant_tva=Decimal(18000), montant_ttc=Decimal(999999),
        confiance=0.9,
    )

    document = normaliser(extraction)

    assert document.montant_ttc == Decimal(999999)  # jamais recalcule/ecrase
    assert len(document.normalization_warnings) == 1
    assert "Incoherence arithmetique" in document.normalization_warnings[0]


def test_montant_ttc_absent_est_signale():
    extraction = DocumentExtractionSnapshot(document_id=uuid4(), company_id=uuid4(), confiance=0.5)

    document = normaliser(extraction)

    assert document.montant_ttc is None
    assert "Montant TTC absent" in document.normalization_warnings[0]


def test_confiance_absente_devient_zero_jamais_none_implicite():
    extraction = DocumentExtractionSnapshot(document_id=uuid4(), company_id=uuid4(), montant_ttc=Decimal(1000))

    document = normaliser(extraction)

    assert document.extraction_confidence == 0.0
