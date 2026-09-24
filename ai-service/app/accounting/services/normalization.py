"""normalize_document (STEP 6.3) : 100% deterministe, aucun LLM. Convertit
DocumentExtractionSnapshot (deja persistee cote Java, voir load_extraction) en
NormalizedAccountingDocument. Les incoherences sont signalees (normalization_warnings), jamais
corrigees silencieusement ni devinees (section 4 du plan)."""

from decimal import Decimal

from app.schemas.accounting import DocumentExtractionSnapshot, NormalizedAccountingDocument

_TOLERANCE_MONTANT = Decimal("1.00")


def normaliser(extraction: DocumentExtractionSnapshot) -> NormalizedAccountingDocument:
    warnings: list[str] = []

    montant_ht = extraction.montant_ht
    montant_tva = extraction.montant_tva
    montant_ttc = extraction.montant_ttc

    if montant_ttc is None:
        warnings.append("Montant TTC absent de l'extraction.")
    elif montant_ht is not None:
        tva_effective = montant_tva if montant_tva is not None else Decimal(0)
        ecart = (montant_ht + tva_effective - montant_ttc).copy_abs()
        if ecart > _TOLERANCE_MONTANT:
            warnings.append(
                f"Incoherence arithmetique : HT ({montant_ht}) + TVA ({tva_effective}) != TTC ({montant_ttc})."
            )

    fournisseur = extraction.fournisseur.strip() if extraction.fournisseur else None

    return NormalizedAccountingDocument(
        document_id=extraction.document_id,
        company_id=extraction.company_id,
        document_type=extraction.type_document,
        fournisseur=fournisseur,
        montant_ht=montant_ht,
        montant_tva=montant_tva,
        montant_ttc=montant_ttc,
        devise=extraction.devise,
        date_piece=extraction.date_piece,
        extraction_confidence=extraction.confiance if extraction.confiance is not None else 0.0,
        normalization_warnings=warnings,
    )
