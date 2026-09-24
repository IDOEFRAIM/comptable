"""Client HTTP Python -> Java pour les capabilities AI Accounting (STEP 4 du plan d'implementation).

Frontiere stricte : ce client ne contient AUCUNE regle comptable. Il transporte une
AccountingProposal vers Java (POST /api/v1/internal/ai-accounting/proposals/validate) et renvoie
exactement le ValidationResult que Java a calcule via AccountingAccountResolver - jamais une
deduction locale ("valid = debit == credit"). Le futur node LangGraph (STEP 5) devra appeler
`accounting_client.validate_proposal(...)`, jamais httpx directement.

Reutilise integralement les conventions HTTP deja en place
(app/services/java_tools_client.py, app/services/core_client.py) : meme base_url/cle
(settings.core_service_base_url/core_service_api_key), meme header X-Internal-Api-Key, meme
mecanisme d'authentification cote Java (agent/accounting/web/AiAccountingProposalValidationController.java).
Ajoute ce qui manquait pour un usage LangGraph fiable : hierarchie d'erreurs typee
(retryable/human_reviewable), retry via tenacity (deja une dependance transitive de langgraph,
aucune nouvelle dependance ajoutee pour ce chantier), timeout explicite, logging sans donnee
sensible (meme convention que groq_client.py/anthropic_client.py::_tracer_appel_ia).

post_entry (STEP 7) reutilise la meme politique de retry que validate_proposal : ce n'est sur que
parce que Java (AccountingOperationEngine.assemblerEtSauvegarder) verifie l'existence d'une
proposition non-REJECTED pour (entrepriseId, sourceType, sourceId, type) AVANT toute creation - un
rejeu de post_entry apres un succes (ou apres un timeout post-commit cote Java) retombe donc sur la
meme ecriture deja postee, jamais une seconde. Java reste l'autorite finale d'idempotence, jamais
Python.
"""

import logging
import time
from typing import Self
from uuid import UUID, uuid4

import httpx
import tenacity
from pydantic import ValidationError

from app.core.config import get_settings
from app.schemas.accounting import (
    AccountingProposal,
    AccountingProposalPostRequest,
    AccountingProposalValidationRequest,
    DocumentExtractionSnapshot,
    PostingResult,
    ValidationResult,
)

logger = logging.getLogger(__name__)

_TIMEOUT_SECONDS = 15.0
_MAX_ATTEMPTS = 3
_ENDPOINT_VALIDATE_PROPOSAL = "/api/v1/internal/ai-accounting/proposals/validate"
_ENDPOINT_POST_PROPOSAL = "/api/v1/internal/ai-accounting/proposals/post"


class AccountingClientError(Exception):
    """Base de toute erreur du client Java Accounting - jamais une Exception generique remontee
    telle quelle. `retryable`/`human_reviewable` permettent au futur LangGraph de decider sans
    avoir a connaitre chaque sous-classe (isinstance reste possible pour un traitement specifique)."""

    def __init__(self, message: str, *, retryable: bool, human_reviewable: bool):
        super().__init__(message)
        self.retryable = retryable
        self.human_reviewable = human_reviewable


class AccountingTransportError(AccountingClientError):
    """Erreur reseau (connexion refusee, DNS, reset...) - retryable, jamais human-reviewable."""

    def __init__(self, message: str):
        super().__init__(message, retryable=True, human_reviewable=False)


class AccountingTimeoutError(AccountingClientError):
    """Timeout de l'appel - retryable, jamais human-reviewable."""

    def __init__(self, message: str):
        super().__init__(message, retryable=True, human_reviewable=False)


class AccountingServiceUnavailableError(AccountingClientError):
    """502/503/504 - retryable, jamais human-reviewable."""

    def __init__(self, message: str, status_code: int):
        super().__init__(message, retryable=True, human_reviewable=False)
        self.status_code = status_code


class AccountingAuthenticationError(AccountingClientError):
    """401 - jamais retryable (cle interne absente/invalide) ; panne de configuration
    operationnelle, pas une revue humaine metier."""

    def __init__(self, message: str):
        super().__init__(message, retryable=False, human_reviewable=False)


class AccountingAuthorizationError(AccountingClientError):
    """403 - jamais retryable, jamais human-reviewable (probleme de configuration, pas de donnee)."""

    def __init__(self, message: str):
        super().__init__(message, retryable=False, human_reviewable=False)


class AccountingValidationError(AccountingClientError):
    """400/404/422 - la proposition elle-meme est rejetee par Java (entreprise introuvable, ligne
    non resolue...) - jamais retryable en l'etat, mais human-reviewable : un comptable peut
    corriger le parametrage comptable puis la reproposer."""

    def __init__(self, message: str, status_code: int, detail: str | None = None):
        super().__init__(message, retryable=False, human_reviewable=True)
        self.status_code = status_code
        self.detail = detail


class AccountingServiceError(AccountingClientError):
    """Toute autre erreur HTTP cote Java non classee ci-dessus - jamais retryable par defaut
    (eviter de boucler sur une erreur inconnue potentiellement deterministe)."""

    def __init__(self, message: str, status_code: int):
        super().__init__(message, retryable=False, human_reviewable=False)
        self.status_code = status_code


class AccountingResponseMalformedError(AccountingClientError):
    """Reponse 2xx mais dont le corps ne respecte pas ValidationResult - jamais retryable (rejouer
    le meme appel produirait la meme reponse malformee), jamais human-reviewable (bug technique,
    pas une decision comptable)."""

    def __init__(self, message: str):
        super().__init__(message, retryable=False, human_reviewable=False)


def _detail_sans_donnee_sensible(response: httpx.Response) -> str:
    """Message d'erreur exploitable sans jamais logger/propager le corps brut de la reponse."""
    try:
        corps = response.json()
    except ValueError:
        return f"HTTP {response.status_code}"
    if isinstance(corps, dict) and "message" in corps:
        return str(corps["message"])[:300]
    return f"HTTP {response.status_code}"


def _classifier_erreur_http(exc: httpx.HTTPStatusError) -> AccountingClientError:
    status = exc.response.status_code
    detail = _detail_sans_donnee_sensible(exc.response)
    if status == 401:
        return AccountingAuthenticationError(f"Authentification refusee par Java (401) : {detail}")
    if status == 403:
        return AccountingAuthorizationError(f"Acces refuse par Java (403) : {detail}")
    if status in (400, 404, 422):
        return AccountingValidationError(f"Proposition rejetee par Java ({status}) : {detail}", status, detail)
    if status in (502, 503, 504):
        return AccountingServiceUnavailableError(f"Service Java indisponible ({status}) : {detail}", status)
    return AccountingServiceError(f"Erreur Java non classee ({status}) : {detail}", status)


def _est_retryable(exc: BaseException) -> bool:
    return isinstance(exc, AccountingClientError) and exc.retryable


_retry_policy = tenacity.retry(
    retry=tenacity.retry_if_exception(_est_retryable),
    wait=tenacity.wait_fixed(0.1),
    stop=tenacity.stop_after_attempt(_MAX_ATTEMPTS),
    reraise=True,
)


def _logger_appel(
    operation: str, correlation_id: UUID, request_id: UUID, statut: str, duree_s: float, erreur: str | None
) -> None:
    """Meme convention que groq_client.py/anthropic_client.py::_tracer_appel_ia - jamais de
    prompt/document/secret logge, uniquement des identifiants et une duree. `correlation_id` est
    le workflow_id pour validate_proposal, le document_id pour get_document_extraction (pas encore
    de workflow_id connu a ce stade du graph)."""
    if erreur:
        logger.warning(
            "accounting_client operation=%s correlation_id=%s request_id=%s status=%s latency_ms=%d error=%s",
            operation, correlation_id, request_id, statut, int(duree_s * 1000), erreur,
        )
    else:
        logger.info(
            "accounting_client operation=%s correlation_id=%s request_id=%s status=%s latency_ms=%d",
            operation, correlation_id, request_id, statut, int(duree_s * 1000),
        )


class AccountingClient:
    """Point d'entree unique pour un futur node LangGraph appelant les capabilities comptables
    Java (branchement reel prevu en STEP 5 - ce client n'est pas encore utilise par un node).

    `http_client` est injectable pour les tests (httpx.MockTransport) ; par defaut construit un
    httpx.Client a partir de la configuration existante (meme base_url/cle que java_tools_client.py).
    """

    def __init__(self, http_client: httpx.Client | None = None):
        if http_client is not None:
            self._client = http_client
            self._proprietaire_du_client = False
        else:
            settings = get_settings()
            self._client = httpx.Client(
                base_url=settings.core_service_base_url,
                headers={"X-Internal-Api-Key": settings.core_service_api_key},
                timeout=_TIMEOUT_SECONDS,
            )
            self._proprietaire_du_client = True

    def close(self) -> None:
        if self._proprietaire_du_client:
            self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_exc_info: object) -> None:
        self.close()

    @_retry_policy
    def validate_proposal(self, proposal: AccountingProposal) -> ValidationResult:
        """POST /api/v1/internal/ai-accounting/proposals/validate. Ne poste jamais rien, ne
        decide jamais localement de la validite : renvoie exactement ce que Java a resolu.

        Idempotence : cet appel est en lecture seule cote Java (aucune mutation), le rejouer sur
        retry ne peut jamais produire de doublon - contrairement a un futur post_entry."""

        request_id = uuid4()
        requete = AccountingProposalValidationRequest.depuis_proposal(proposal)
        debut = time.perf_counter()

        headers = {"Content-Type": "application/json", "X-Request-Id": str(request_id)}

        try:
            response = self._client.post(
                _ENDPOINT_VALIDATE_PROPOSAL,
                content=requete.model_dump_json(by_alias=True),
                headers=headers,
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            erreur = _classifier_erreur_http(exc)
            _logger_appel("validate_proposal", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, type(erreur).__name__)
            raise erreur from exc
        except httpx.TimeoutException as exc:
            _logger_appel("validate_proposal", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, "timeout")
            raise AccountingTimeoutError(f"Timeout apres {_TIMEOUT_SECONDS}s vers Java") from exc
        except httpx.HTTPError as exc:
            _logger_appel("validate_proposal", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, "transport")
            raise AccountingTransportError(f"Erreur de transport vers Java : {exc.__class__.__name__}") from exc

        try:
            resultat = ValidationResult.model_validate(response.json())
        except (ValueError, ValidationError) as exc:
            _logger_appel("validate_proposal", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, "reponse_malformee")
            raise AccountingResponseMalformedError(
                f"Reponse Java ne respecte pas ValidationResult : {exc.__class__.__name__}"
            ) from exc

        _logger_appel("validate_proposal", proposal.workflow_id, request_id, "succes", time.perf_counter() - debut, None)
        return resultat

    @_retry_policy
    def post_entry(self, proposal: AccountingProposal) -> PostingResult:
        """POST /api/v1/internal/ai-accounting/proposals/post. Premiere operation mutative de ce
        client (STEP 7) : ne decide jamais localement si le posting doit avoir lieu (la politique
        de posting - confiance/validation/HITL - vit dans le graph, jamais ici) ; se contente de
        transporter la proposition et de renvoyer exactement ce que Java a effectivement pose.

        Idempotence : voir le commentaire de module en tete de fichier - le retry ci-dessous est
        sans danger car Java verifie l'idempotence (source_type/source_id/type) avant de creer
        quoi que ce soit."""

        request_id = uuid4()
        requete = AccountingProposalPostRequest.depuis_proposal(proposal)
        debut = time.perf_counter()

        headers = {"Content-Type": "application/json", "X-Request-Id": str(request_id)}

        try:
            response = self._client.post(
                _ENDPOINT_POST_PROPOSAL,
                content=requete.model_dump_json(by_alias=True),
                headers=headers,
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            erreur = _classifier_erreur_http(exc)
            _logger_appel("post_entry", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, type(erreur).__name__)
            raise erreur from exc
        except httpx.TimeoutException as exc:
            _logger_appel("post_entry", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, "timeout")
            raise AccountingTimeoutError(f"Timeout apres {_TIMEOUT_SECONDS}s vers Java") from exc
        except httpx.HTTPError as exc:
            _logger_appel("post_entry", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, "transport")
            raise AccountingTransportError(f"Erreur de transport vers Java : {exc.__class__.__name__}") from exc

        try:
            resultat = PostingResult.model_validate(response.json())
        except (ValueError, ValidationError) as exc:
            _logger_appel("post_entry", proposal.workflow_id, request_id, "echec", time.perf_counter() - debut, "reponse_malformee")
            raise AccountingResponseMalformedError(
                f"Reponse Java ne respecte pas PostingResult : {exc.__class__.__name__}"
            ) from exc

        _logger_appel("post_entry", proposal.workflow_id, request_id, "succes", time.perf_counter() - debut, None)
        return resultat

    @_retry_policy
    def get_document_extraction(self, document_id: UUID) -> DocumentExtractionSnapshot:
        """GET /api/v1/internal/ai-accounting/documents/{document_id}. Relit l'extraction deja
        persistee cote Java (AiAccountingDocumentQueryService) - ne relance jamais le VLM, ne
        reimplemente aucun pipeline de documents (ce n'est qu'une lecture)."""

        request_id = uuid4()
        debut = time.perf_counter()
        headers = {"X-Request-Id": str(request_id)}

        try:
            response = self._client.get(f"/api/v1/internal/ai-accounting/documents/{document_id}", headers=headers)
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            erreur = _classifier_erreur_http(exc)
            _logger_appel("get_document_extraction", document_id, request_id, "echec", time.perf_counter() - debut, type(erreur).__name__)
            raise erreur from exc
        except httpx.TimeoutException as exc:
            _logger_appel("get_document_extraction", document_id, request_id, "echec", time.perf_counter() - debut, "timeout")
            raise AccountingTimeoutError(f"Timeout apres {_TIMEOUT_SECONDS}s vers Java") from exc
        except httpx.HTTPError as exc:
            _logger_appel("get_document_extraction", document_id, request_id, "echec", time.perf_counter() - debut, "transport")
            raise AccountingTransportError(f"Erreur de transport vers Java : {exc.__class__.__name__}") from exc

        try:
            resultat = DocumentExtractionSnapshot.model_validate(response.json())
        except (ValueError, ValidationError) as exc:
            _logger_appel("get_document_extraction", document_id, request_id, "echec", time.perf_counter() - debut, "reponse_malformee")
            raise AccountingResponseMalformedError(
                f"Reponse Java ne respecte pas DocumentExtractionSnapshot : {exc.__class__.__name__}"
            ) from exc

        _logger_appel("get_document_extraction", document_id, request_id, "succes", time.perf_counter() - debut, None)
        return resultat
