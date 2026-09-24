"""Serveur MCP AI Accounting (STEP 7.2-7.6 du plan d'implementation).

MCP est ici une couche d'EXPOSITION D'OUTILS, jamais un endroit de logique metier/comptable
(priorite explicite du plan) : chaque outil ci-dessous est un pass-through trivial vers
AccountingClient - aucun try/except ici. Une AccountingClientError levee par AccountingClient
traverse le tool tel quel ; le SDK MCP (mcp.server.mcpserver.MCPServer) la re-encapsule en
UnexpectedToolError (son `.__cause__` reste l'AccountingClientError d'origine) - c'est
app/mcp/client.py qui la deballe et la reclasse, jamais ce module.

SDK MCP officiel (mcp.server.mcpserver.MCPServer, verifie par introspection : `FastMCP` n'existe
plus dans la ligne 2.x installee ici) - jamais une abstraction MCP maison.

Volontairement absents (section 7.3 du plan, "ne jamais fabriquer un endpoint Java juste pour
remplir la liste suggeree de outils") : accounting.get_accounts / get_account / get_ledger /
get_trial_balance / get_profit_loss. Aucune capability Java reelle, accessible via la cle API
interne AI Accounting, n'existe pour ces lectures a ce jour (seules
AiAccountingDocumentQueryService, AiAccountingProposalValidationService et
AiAccountingPostingService existent cote Java) - les exposer ici fabriquerait un outil MCP sans
backend reel, exactement ce que le plan interdit.
"""

from uuid import UUID

from mcp.server.mcpserver import MCPServer

from app.schemas.accounting import (
    AccountingProposal,
    DocumentExtractionSnapshot,
    PostingResult,
    ValidationResult,
)
from app.services.accounting_client import AccountingClient

MCP_SERVER_NAME = "ai-accounting"

TOOL_GET_DOCUMENT = "accounting.get_document"
TOOL_VALIDATE_PROPOSAL = "accounting.validate_proposal"
TOOL_POST_ENTRY = "accounting.post_entry"


def creer_mcp_server(accounting_client: AccountingClient) -> MCPServer:
    """Factory explicite (jamais un singleton global) - meme discipline d'injection par fermeture
    que build_accounting_graph/creer_load_extraction_node : chaque appelant fournit son propre
    AccountingClient (reel ou mock), ce qui garde le serveur MCP testable sans reseau reel."""

    server = MCPServer(name=MCP_SERVER_NAME)

    @server.tool(name=TOOL_GET_DOCUMENT)
    def get_document(document_id: UUID) -> DocumentExtractionSnapshot:
        """Relit l'extraction deja persistee cote Java pour ce document (lecture seule) - ne
        relance jamais le VLM, ne reimplemente aucun pipeline de documents."""
        return accounting_client.get_document_extraction(document_id)

    @server.tool(name=TOOL_VALIDATE_PROPOSAL)
    def validate_proposal(proposal: AccountingProposal) -> ValidationResult:
        """Fait valider une proposition comptable par Java (AccountingAccountResolver, lecture
        seule cote Java) - ne decide jamais localement de la validite."""
        return accounting_client.validate_proposal(proposal)

    @server.tool(name=TOOL_POST_ENTRY)
    def post_entry(proposal: AccountingProposal) -> PostingResult:
        """Fait poster reellement une proposition comptable par Java
        (AccountingOperationEngine) - seule operation mutative exposee par ce serveur MCP. La
        politique de posting (confiance/HITL) vit dans le graph, jamais ici : cet outil poste des
        qu'on l'appelle."""
        return accounting_client.post_entry(proposal)

    return server
