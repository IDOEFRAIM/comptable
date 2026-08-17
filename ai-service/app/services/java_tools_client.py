from datetime import date
from decimal import Decimal

import httpx

from app.core.config import get_settings
from app.schemas.agent import (
    DemandeClarificationResponse,
    DoublonVerificationResponse,
    HistoriqueFournisseurResponse,
)

_TIMEOUT = 15


def _client() -> httpx.Client:
    settings = get_settings()
    return httpx.Client(
        base_url=settings.core_service_base_url,
        headers={"X-Internal-Api-Key": settings.core_service_api_key},
        timeout=_TIMEOUT,
    )


def verifier_doublon_montant(entreprise_id: str, montant_ttc: Decimal, date_piece: date) -> DoublonVerificationResponse:
    """Backend Java de l'outil `verifier_doublon_montant` (agent_orchestrator.py) :
    GET /api/v1/internal/agent-tools/doublons, reutilise DocumentRepository.rechercherDoublonsPotentiels
    (meme requete que le controle deterministe AnalyseFraudeService.verifierDoublon)."""
    with _client() as client:
        response = client.get(
            "/api/v1/internal/agent-tools/doublons",
            params={
                "entrepriseId": entreprise_id,
                "montantTtc": str(montant_ttc),
                "datePiece": date_piece.isoformat(),
            },
        )
        response.raise_for_status()
        return DoublonVerificationResponse.model_validate(response.json())


def consulter_historique_fournisseur(entreprise_id: str, fournisseur: str) -> HistoriqueFournisseurResponse:
    """Backend Java de l'outil `consulter_historique_fournisseur` : GET
    /api/v1/internal/agent-tools/historique-fournisseur, filtrage JSONB approximatif (ILIKE) sur
    le nom de fournisseur deja extrait des pieces precedentes."""
    with _client() as client:
        response = client.get(
            "/api/v1/internal/agent-tools/historique-fournisseur",
            params={"entrepriseId": entreprise_id, "fournisseur": fournisseur},
        )
        response.raise_for_status()
        return HistoriqueFournisseurResponse.model_validate(response.json())


def demander_clarification_client(entreprise_id: str, libelle: str, description: str | None) -> DemandeClarificationResponse:
    """Backend Java de l'outil `demander_clarification_client` : POST
    /api/v1/internal/agent-tools/demande-clarification, cree une DemandePiece consultable dans le
    portail client (memes statuts/relances que les demandes creees manuellement par un collaborateur)."""
    with _client() as client:
        response = client.post(
            "/api/v1/internal/agent-tools/demande-clarification",
            json={"entrepriseId": entreprise_id, "libelle": libelle, "description": description},
        )
        response.raise_for_status()
        return DemandeClarificationResponse.model_validate(response.json())
