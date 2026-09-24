"""Catalogue des tools MCP utilisables par l'AI CFO (STEP 11.4 du plan d'implementation).

Liste UNIQUEMENT les tools MCP reellement enregistres (app/mcp/server.py,
app/mcp/finance_server.py) - jamais un tool fabrique pour completer la liste suggeree par le plan
(meme discipline que STEP 7.3 : "ne jamais fabriquer un endpoint Java juste pour remplir la liste
suggeree"). `finance.simulate_scenario` n'existe pas dans ce repository (Scenario Engine non
construit) et n'apparait donc jamais ici - une question SCENARIO degrade explicitement vers
"capability not available" (voir app/cfo/planner.py), jamais un tool invente.

Un test dedie (tests/test_cfo_planner.py) verifie que les noms de ce catalogue correspondent
exactement aux tools reellement exposes par creer_mcp_server()/creer_finance_mcp_server() - ce
catalogue ne peut donc jamais diverger silencieusement des serveurs MCP reels."""

from dataclasses import dataclass

from app.mcp.finance_server import (
    TOOL_GET_CASH_FORECAST,
    TOOL_GET_CASH_POSITION,
    TOOL_GET_FINANCIAL_SNAPSHOT,
    TOOL_GET_PAYABLES,
    TOOL_GET_RECEIVABLES,
    TOOL_GET_WORKING_CAPITAL,
)
from app.mcp.server import TOOL_GET_DOCUMENT, TOOL_POST_ENTRY, TOOL_VALIDATE_PROPOSAL
from app.schemas.cfo import ToolAccessClass


@dataclass(frozen=True)
class CfoToolDescriptor:
    """Description d'un tool MCP telle que le CFO doit la connaitre - name/description/
    input_schema/output_schema/access_class (section 4 du plan). Les schemas restent des
    resumes lisibles (pas le JSON Schema complet genere par le SDK MCP) : suffisants pour que le
    planner LLM sache quels arguments fournir, sans dupliquer la validation reelle (qui reste
    faite par le serveur MCP/Pydantic, jamais recalculee ici)."""

    name: str
    description: str
    input_schema: str
    output_schema: str
    access_class: ToolAccessClass


CFO_TOOL_CATALOG: tuple[CfoToolDescriptor, ...] = (
    CfoToolDescriptor(
        name=TOOL_GET_DOCUMENT,
        description="Relit l'extraction deja persistee d'un document (fournisseur, montants, type, confiance).",
        input_schema="document_id: UUID",
        output_schema="DocumentExtractionSnapshot (montants HT/TVA/TTC, fournisseur, type, confiance)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_VALIDATE_PROPOSAL,
        description="Valide une proposition comptable deja construite (resolution de comptes, coherence) sans rien ecrire.",
        input_schema="proposal: AccountingProposal (deja construite ailleurs, jamais par le CFO)",
        output_schema="ValidationResult (valid, requires_review, resolved_accounts, errors, warnings)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_POST_ENTRY,
        description="Poste reellement une proposition comptable deja validee - seule operation d'ecriture du catalogue.",
        input_schema="proposal: AccountingProposal (deja construite et approuvee, jamais par le CFO)",
        output_schema="PostingResult (posted, entry_id, entry_number, journal)",
        access_class=ToolAccessClass.WRITE,
    ),
    CfoToolDescriptor(
        name=TOOL_GET_CASH_POSITION,
        description="Position de tresorerie consolidee (banque/caisse/mobile money) a une date donnee.",
        input_schema="company_id: UUID, as_of: date | None",
        output_schema="CashPosition (total, par_compte)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_GET_FINANCIAL_SNAPSHOT,
        description="Revenue/expenses/profit/margin pour une periode - une seule capability pour ces quatre metriques.",
        input_schema="company_id: UUID, period_start: date, period_end: date",
        output_schema="FinancialSnapshotWithMargins (snapshot, net_margin, gross_margin)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_GET_RECEIVABLES,
        description="Creances clients : total, aging (0-30/31-60/61-90/90+), top debiteurs.",
        input_schema="company_id: UUID, as_of: date | None",
        output_schema="Receivables (total, current, overdue, aging, top_debtors)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_GET_PAYABLES,
        description="Dettes fournisseurs : total, aging, top crediteurs.",
        input_schema="company_id: UUID, as_of: date | None",
        output_schema="Payables (total, current, overdue, aging, top_creditors)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_GET_WORKING_CAPITAL,
        description="Besoin en fonds de roulement (receivables + inventory - payables).",
        input_schema="company_id: UUID, as_of: date | None",
        output_schema="WorkingCapital (status, working_capital, receivables, payables, inventory)",
        access_class=ToolAccessClass.READ,
    ),
    CfoToolDescriptor(
        name=TOOL_GET_CASH_FORECAST,
        description="Previsions de tresorerie sur plusieurs semaines (KNOWN/EXPECTED/PREDICTED), confiance, evenements de liquidite.",
        input_schema="company_id: UUID, start_date: date | None, weeks: int | None, minimum_cash_threshold: Decimal | None",
        output_schema="CashForecast (opening_cash, closing_cash, confidence, weeks, liquidity_events)",
        access_class=ToolAccessClass.READ,
    ),
)


def obtenir_descripteur(nom_outil: str) -> CfoToolDescriptor | None:
    """Recherche un tool par nom - retourne None si absent du catalogue (jamais un descripteur
    invente pour un tool non enregistre)."""

    for descripteur in CFO_TOOL_CATALOG:
        if descripteur.name == nom_outil:
            return descripteur
    return None
