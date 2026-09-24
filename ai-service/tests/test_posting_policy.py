from app.accounting.services.posting_policy import (
    SEUIL_CONFIANCE_POSTING_AUTOMATIQUE,
    approbation_humaine_requise_pour_poster,
)
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountResolutionSource,
    AccountResolutionStatus,
    ResolvedAccountInfo,
    TaxTreatment,
    ValidationResult,
)


def _classification(confidence: float) -> AccountingClassification:
    return AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=confidence,
        requires_review=False,
    )


def _validation(*, valid=True, requires_review=False, warnings=None) -> ValidationResult:
    return ValidationResult(
        valid=valid,
        requires_review=requires_review,
        resolved_accounts=[
            ResolvedAccountInfo(
                status=AccountResolutionStatus.RESOLVED, compte_numero="621",
                source=AccountResolutionSource.MAPPING_GENERIQUE, explication="Resolu.",
            )
        ],
        warnings=warnings or [],
    )


def test_confiance_elevee_validation_propre_autorise_le_posting_sans_revue():
    classification = _classification(SEUIL_CONFIANCE_POSTING_AUTOMATIQUE)
    validation = _validation()
    assert approbation_humaine_requise_pour_poster(classification, validation) is False


def test_confiance_sous_le_seuil_exige_une_revue():
    classification = _classification(SEUIL_CONFIANCE_POSTING_AUTOMATIQUE - 0.01)
    validation = _validation()
    assert approbation_humaine_requise_pour_poster(classification, validation) is True


def test_proposition_invalide_exige_toujours_une_revue():
    classification = _classification(0.99)
    validation = _validation(valid=False)
    assert approbation_humaine_requise_pour_poster(classification, validation) is True


def test_java_requires_review_exige_une_revue_meme_a_confiance_maximale():
    classification = _classification(0.99)
    validation = _validation(requires_review=True)
    assert approbation_humaine_requise_pour_poster(classification, validation) is True


def test_le_moindre_avertissement_java_exige_une_revue():
    classification = _classification(0.99)
    validation = _validation(warnings=["Compte 621 desactive depuis le 2026-01-01"])
    assert approbation_humaine_requise_pour_poster(classification, validation) is True


def test_classification_absente_exige_prudemment_une_revue():
    validation = _validation()
    assert approbation_humaine_requise_pour_poster(None, validation) is True
