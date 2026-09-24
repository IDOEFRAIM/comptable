"""Classification deterministe (STEP 6.4) : premiere passe avant tout appel LLM (voir
classification_service.py) - "deterministe d'abord, LLM en repli intelligent" (section 5 du plan).

Categories volontairement minimales (MVP, section 6 du plan) : carburant/loyer/telecom. Etendre
uniquement sur besoin reel constate, jamais par anticipation - ne pas creer 200 regles."""

from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountPurpose,
    NormalizedAccountingDocument,
    SuggestedAccount,
    TaxTreatment,
)

CONFIANCE_REGLE_DETERMINISTE = 0.95
"""Meme seuil que PieceJustificativeGraphConfig.SEUIL_CONFIANCE_AUTO (Java, LangGraph4j) : une
correspondance deterministe par mot-cle est aussi fiable qu'une extraction VLM haute confiance."""

_REGLES: tuple[tuple[AccountingNatureLabel, tuple[str, ...], AccountPurpose], ...] = (
    (
        AccountingNatureLabel.FUEL,
        ("carburant", "essence", "diesel", "station service", "station-service"),
        AccountPurpose.ACHAT_CONSOMMABLE,
    ),
    (
        AccountingNatureLabel.RENT,
        ("loyer", "location bureau", "location de bureau"),
        AccountPurpose.ACHAT_SERVICE,
    ),
    (
        AccountingNatureLabel.TELECOM,
        ("abonnement internet", "fibre", "telecom", "forfait mobile"),
        AccountPurpose.ACHAT_SERVICE,
    ),
)


def classifier_deterministe(document: NormalizedAccountingDocument) -> AccountingClassification | None:
    """Cherche un mot-cle dans le libelle/fournisseur normalise. Retourne None si aucune regle ne
    correspond (jamais une classification devinee a faible confiance) - le fallback LLM prend
    alors le relais dans classification_service.py."""

    texte = " ".join(filter(None, [document.libelle, document.fournisseur])).lower()
    if not texte.strip():
        return None

    for nature, mots_cles, purpose in _REGLES:
        if any(mot in texte for mot in mots_cles):
            tax_treatment = TaxTreatment.TVA_RECUPERABLE if document.montant_tva else TaxTreatment.NON_APPLICABLE
            return AccountingClassification(
                operation_type=AccountingOperationType.ACHAT,
                accounting_nature=nature,
                suggested_accounts=[SuggestedAccount(purpose=purpose, confidence=CONFIANCE_REGLE_DETERMINISTE)],
                tax_treatment=tax_treatment,
                confidence=CONFIANCE_REGLE_DETERMINISTE,
                requires_review=False,
                rationale=f"Correspondance deterministe sur mot-cle pour la categorie {nature.value}.",
            )

    return None
