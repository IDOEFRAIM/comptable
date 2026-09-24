"""Client MCP AI Accounting (STEP 7.9 du plan d'implementation) : point d'entree synchrone unique
par lequel les nodes LangGraph appellent les capabilities Java desormais exposees comme des outils
MCP (app/mcp/server.py), plutot que d'appeler AccountingClient directement depuis un node. MCP
devient ainsi la frontiere agentique du graph - aucune logique metier ne se deplace (elle reste
entierement dans AccountingClient/Java, inchangee).

Reclassement des erreurs (seul endroit du code a connaitre ToolError/UnexpectedToolError, cf.
app/mcp/server.py) :
- UnexpectedToolError dont la cause est une AccountingClientError -> cette AccountingClientError
  est relevee telle quelle (meme type, memes retryable/human_reviewable) : les nodes existants
  (validate_with_java, ...) n'ont donc rien a changer a leur `except AccountingClientError`.
- Toute autre erreur (ToolError d'entree invalide au niveau MCP, ou UnexpectedToolError dont la
  cause n'est pas une AccountingClientError) -> McpToolCallError, qui HERITE de
  AccountingClientError pour que l'appelant continue a ne connaitre qu'une seule famille
  d'erreurs, qu'il passe par MCP ou par AccountingClient directement.

Synchrone par choix (asyncio.run) pour rester coherent avec AccountingClient (httpx.Client sync) et
les nodes LangGraph existants (fonctions sync) - ne transforme pas le graph en graph async, hors
perimetre de STEP 7. Limite assumee et documentee : ne peut pas etre appele depuis un contexte ou
une boucle asyncio tourne deja (asyncio.run leverait RuntimeError) - non rencontre dans ce vertical
slice, le graph etant toujours invoque de maniere synchrone (cf. tests)."""

import asyncio
from typing import Any
from uuid import UUID

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError, UnexpectedToolError

from app.mcp.server import TOOL_GET_DOCUMENT, TOOL_POST_ENTRY, TOOL_VALIDATE_PROPOSAL
from app.schemas.accounting import (
    AccountingProposal,
    DocumentExtractionSnapshot,
    PostingResult,
    ValidationResult,
)
from app.services.accounting_client import AccountingClientError


class McpToolCallError(AccountingClientError):
    """Erreur MCP qui n'a pas pu etre reclassee vers l'AccountingClientError d'origine (entree
    invalide au niveau MCP, ou erreur inattendue non liee a AccountingClient). Herite de
    AccountingClientError pour que tout appelant continue a ne connaitre qu'une seule famille
    d'erreurs, qu'il passe par MCP ou par AccountingClient directement."""

    def __init__(self, message: str, *, retryable: bool = False, human_reviewable: bool = False):
        super().__init__(message, retryable=retryable, human_reviewable=human_reviewable)


def _appeler_outil_sync(server: MCPServer, nom_outil: str, arguments: dict[str, Any]) -> Any:
    try:
        resultat = asyncio.run(server.call_tool(nom_outil, arguments))
    except UnexpectedToolError as exc:
        cause = exc.__cause__
        if isinstance(cause, AccountingClientError):
            raise cause from exc
        raise McpToolCallError(f"Erreur inattendue de l'outil MCP {nom_outil} : {cause}") from exc
    except ToolError as exc:
        raise McpToolCallError(
            f"Entree invalide pour l'outil MCP {nom_outil} : {exc}", human_reviewable=True
        ) from exc
    return resultat.structured_content


class McpAccountingClient:
    """Point d'entree unique pour un node LangGraph appelant une capability comptable via MCP -
    jamais httpx/AccountingClient directement depuis un node (STEP 7.9). Meme forme d'API que
    AccountingClient (memes noms de methode/types de retour) pour que le remplacement dans les
    nodes soit un changement d'injection, jamais une reecriture de logique."""

    def __init__(self, server: MCPServer):
        self._server = server

    def get_document_extraction(self, document_id: UUID) -> DocumentExtractionSnapshot:
        contenu = _appeler_outil_sync(self._server, TOOL_GET_DOCUMENT, {"document_id": str(document_id)})
        return DocumentExtractionSnapshot.model_validate(contenu)

    def validate_proposal(self, proposal: AccountingProposal) -> ValidationResult:
        contenu = _appeler_outil_sync(
            self._server, TOOL_VALIDATE_PROPOSAL, {"proposal": proposal.model_dump(mode="json", by_alias=True)}
        )
        return ValidationResult.model_validate(contenu)

    def post_entry(self, proposal: AccountingProposal) -> PostingResult:
        contenu = _appeler_outil_sync(
            self._server, TOOL_POST_ENTRY, {"proposal": proposal.model_dump(mode="json", by_alias=True)}
        )
        return PostingResult.model_validate(contenu)
