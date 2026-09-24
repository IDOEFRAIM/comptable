"""build_accounting_proposal (STEP 6.8) : combine NormalizedAccountingDocument +
AccountingClassification + AccountingTreatment pour produire un AccountingProposal. Les montants
restent ceux extraits/valides : la seule derivation faite ici (HT depuis TTC-TVA quand HT est
absent) est une simple soustraction en code Python deterministe, jamais une estimation du LLM -
section 14 du plan ("les incoherences doivent etre detectees par du code ou Java")."""

from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID

from app.schemas.accounting import (
    AccountingClassification,
    AccountingProposal,
    AccountingTreatment,
    NormalizedAccountingDocument,
    ProposedAccountingLine,
)


def construire_proposal(
    *,
    workflow_id: UUID,
    document: NormalizedAccountingDocument,
    classification: AccountingClassification,
    treatment: AccountingTreatment,
) -> AccountingProposal:
    montant_ht = document.montant_ht
    if montant_ht is None and document.montant_ttc is not None:
        montant_ht = document.montant_ttc - (document.montant_tva or Decimal(0))
    if montant_ht is None:
        montant_ht = Decimal(0)

    montant_ttc = document.montant_ttc
    if montant_ttc is None:
        montant_ttc = montant_ht + (document.montant_tva or Decimal(0))

    libelle = document.libelle or document.fournisseur or "Achat"
    ligne = ProposedAccountingLine(
        purpose=treatment.expense_purpose,
        montant_ht=montant_ht,
        designation=libelle,
    )

    return AccountingProposal(
        workflow_id=workflow_id,
        document_id=document.document_id,
        company_id=document.company_id,
        lines=[ligne],
        montant_tva=document.montant_tva,
        montant_ttc=montant_ttc,
        classification=classification,
        idempotency_key=f"{document.document_id}:{classification.operation_type.value}",
        date_piece=document.date_piece or datetime.now(tz=UTC).date(),
        libelle=libelle,
    )
