from decimal import Decimal
from uuid import uuid4

from app.accounting.classifiers.deterministic import classifier_deterministe
from app.schemas.accounting import (
    AccountingNatureLabel,
    AccountPurpose,
    NormalizedAccountingDocument,
    TaxTreatment,
)


def _document(fournisseur=None, libelle=None, montant_ht="100000", montant_tva="18000", montant_ttc="118000"):
    return NormalizedAccountingDocument(
        document_id=uuid4(),
        company_id=uuid4(),
        fournisseur=fournisseur,
        libelle=libelle,
        montant_ht=Decimal(montant_ht),
        montant_tva=Decimal(montant_tva) if montant_tva is not None else None,
        montant_ttc=Decimal(montant_ttc),
        extraction_confidence=0.9,
    )


def test_cas1_carburant_station_service_est_classifie_fuel():
    document = _document(fournisseur="Station Total", libelle="carburant")

    classification = classifier_deterministe(document)

    assert classification is not None
    assert classification.accounting_nature is AccountingNatureLabel.FUEL
    assert classification.suggested_accounts[0].purpose is AccountPurpose.ACHAT_CONSOMMABLE
    assert classification.confidence >= 0.90
    assert classification.requires_review is False
    assert classification.tax_treatment is TaxTreatment.TVA_RECUPERABLE


def test_cas2_loyer_est_classifie_rent():
    document = _document(fournisseur="SCI Les Bureaux", libelle="Loyer bureau aout 2026")

    classification = classifier_deterministe(document)

    assert classification is not None
    assert classification.accounting_nature is AccountingNatureLabel.RENT
    assert classification.suggested_accounts[0].purpose is AccountPurpose.ACHAT_SERVICE


def test_telecom_abonnement_internet_est_classifie_telecom():
    document = _document(fournisseur="Orange CI", libelle="Abonnement internet fibre")

    classification = classifier_deterministe(document)

    assert classification is not None
    assert classification.accounting_nature is AccountingNatureLabel.TELECOM


def test_cas3_libelle_ambigu_ne_correspond_a_aucune_regle_deterministe():
    document = _document(fournisseur="Divers Services SARL", libelle="Prestations diverses")

    classification = classifier_deterministe(document)

    assert classification is None  # le fallback LLM doit prendre le relais, jamais une regle forcee


def test_document_sans_libelle_ni_fournisseur_ne_correspond_a_rien():
    document = _document(fournisseur=None, libelle=None)

    assert classifier_deterministe(document) is None


def test_document_sans_tva_est_classifie_non_applicable():
    document = _document(fournisseur="Station Shell", libelle="essence", montant_tva=None, montant_ttc="100000")

    classification = classifier_deterministe(document)

    assert classification is not None
    assert classification.tax_treatment is TaxTreatment.NON_APPLICABLE
