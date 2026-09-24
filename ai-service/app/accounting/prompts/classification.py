"""Prompt du classifieur AI Accounting (STEP 6.5/6.8) - demande uniquement une classification
structuree (operation_type/accounting_nature/suggested_accounts/tax_treatment/confidence/
requires_review), jamais une ecriture comptable complete, un debit/credit, ou un calcul de TVA
(section 8 du plan) : le LLM ne doit jamais essayer de remplacer Java."""

from app.schemas.accounting import AccountPurpose

TOOL_NAME = "classifier_operation_comptable"

SYSTEM_PROMPT = (
    "Tu classes une operation comptable a partir d'un document deja normalise (fournisseur, "
    "libelle, montants HT/TVA/TTC). Tu ne calcules jamais un montant (HT/TVA/TTC restent ceux "
    "deja extraits), tu ne choisis jamais un compte comptable final (seulement une categorie "
    "purpose parmi celles fournies), et tu ne decides jamais si une TVA est juridiquement "
    "deductible - tu proposes uniquement une classification que le systeme comptable Java "
    "validera independamment via son propre moteur de resolution de comptes."
)

TOOL_DESCRIPTION = (
    "Retourne la classification comptable d'une operation d'achat : type d'operation, categorie "
    "metier (accounting_nature), comptes suggeres (purpose + confiance), traitement fiscal "
    "probable, confiance globale et besoin de revue humaine."
)


def construire_input_schema() -> dict:
    """Contraint le LLM au vocabulaire reel (AccountPurpose) - jamais un purpose invente, la
    validation de forme est faite par le schema JSON, la validation de fond reste faite par
    AccountingClassification.model_validate cote appelant (structured output, jamais du texte
    libre parse manuellement)."""

    return {
        "type": "object",
        "properties": {
            "operation_type": {"type": "string", "enum": ["ACHAT"]},
            "accounting_nature": {"type": "string", "enum": ["FUEL", "RENT", "TELECOM", "OTHER"]},
            "suggested_accounts": {
                "type": "array",
                "minItems": 1,
                "items": {
                    "type": "object",
                    "properties": {
                        "purpose": {"type": "string", "enum": [p.value for p in AccountPurpose]},
                        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                    },
                    "required": ["purpose", "confidence"],
                },
            },
            "tax_treatment": {"type": "string", "enum": ["TVA_RECUPERABLE", "EXONERE", "NON_APPLICABLE"]},
            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
            "requires_review": {"type": "boolean"},
            "rationale": {"type": "string"},
        },
        "required": [
            "operation_type",
            "accounting_nature",
            "suggested_accounts",
            "tax_treatment",
            "confidence",
            "requires_review",
        ],
    }
