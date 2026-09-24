import json
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest

from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    DocumentExtractionSnapshot,
    PostingResult,
    ProposedAccountingLine,
    TaxTreatment,
    ValidationResult,
)
from app.services.accounting_client import (
    AccountingAuthenticationError,
    AccountingAuthorizationError,
    AccountingClient,
    AccountingResponseMalformedError,
    AccountingServiceUnavailableError,
    AccountingTimeoutError,
    AccountingTransportError,
    AccountingValidationError,
)

_REPONSE_OK = {"valid": True, "requiresReview": False, "resolvedAccounts": [], "errors": [], "warnings": []}
_REPONSE_POSTED = {
    "posted": True,
    "proposalId": str(uuid4()),
    "entryId": str(uuid4()),
    "entryNumber": "ACH-2026-0001",
    "postedAt": "2026-08-26T10:00:00Z",
    "journal": "ACH",
    "errors": [],
}


def _proposal() -> AccountingProposal:
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT,
        accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE,
        confidence=0.9,
        requires_review=False,
    )
    return AccountingProposal(
        workflow_id=uuid4(),
        document_id=uuid4(),
        company_id=uuid4(),
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
        montant_tva=Decimal(18000),
        montant_ttc=Decimal(118000),
        classification=classification,
        idempotency_key="k",
    )


def _client_avec_handler(handler) -> AccountingClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.Client(transport=transport, base_url="http://java-test", headers={"X-Internal-Api-Key": "test-key"})
    return AccountingClient(http_client=http_client)


def test_validation_reussie_retourne_le_validation_result_de_java():
    def handler(request):
        return httpx.Response(200, json=_REPONSE_OK)

    client = _client_avec_handler(handler)
    resultat = client.validate_proposal(_proposal())

    assert isinstance(resultat, ValidationResult)
    assert resultat.valid is True


def test_422_devient_une_erreur_de_validation_human_reviewable_et_non_retryable():
    appels = []

    def handler(request):
        appels.append(request)
        return httpx.Response(422, json={"message": "Ligne 'Presta' (ACHAT_SERVICE) : compte non configure."})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingValidationError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is False
    assert exc_info.value.human_reviewable is True
    assert exc_info.value.status_code == 422
    assert len(appels) == 1  # jamais reessaye : c'est une erreur metier, pas transitoire


def test_404_entreprise_introuvable_est_aussi_une_erreur_de_validation():
    def handler(request):
        return httpx.Response(404, json={"message": "Entreprise introuvable"})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingValidationError):
        client.validate_proposal(_proposal())


def test_401_devient_une_erreur_authentification_non_retryable():
    appels = []

    def handler(request):
        appels.append(request)
        return httpx.Response(401)

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingAuthenticationError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is False
    assert exc_info.value.human_reviewable is False
    assert len(appels) == 1


def test_403_devient_une_erreur_autorisation_non_retryable():
    def handler(request):
        return httpx.Response(403)

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingAuthorizationError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is False


def test_timeout_est_classifie_retryable_et_reessaie_trois_fois_avant_dechouer():
    appels = []

    def handler(request):
        appels.append(request)
        raise httpx.ReadTimeout("delai depasse", request=request)

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingTimeoutError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is True
    assert len(appels) == 3  # politique de retry (3 tentatives max), jamais une boucle infinie


def test_503_service_indisponible_est_retryable_et_reessaie():
    appels = []

    def handler(request):
        appels.append(request)
        return httpx.Response(503)

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingServiceUnavailableError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is True
    assert len(appels) == 3


def test_succes_apres_un_premier_503_ne_leve_aucune_erreur():
    appels = []

    def handler(request):
        appels.append(request)
        if len(appels) == 1:
            return httpx.Response(503)
        return httpx.Response(200, json=_REPONSE_OK)

    client = _client_avec_handler(handler)
    resultat = client.validate_proposal(_proposal())

    assert resultat.valid is True
    assert len(appels) == 2


def test_erreur_de_connexion_est_une_erreur_de_transport_retryable():
    def handler(request):
        raise httpx.ConnectError("connexion refusee", request=request)

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingTransportError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is True


def test_reponse_malformee_est_rejetee_par_le_schema_pydantic_jamais_retryable():
    def handler(request):
        return httpx.Response(200, json={"ceci": "ne ressemble pas a un ValidationResult"})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingResponseMalformedError) as exc_info:
        client.validate_proposal(_proposal())

    assert exc_info.value.retryable is False


def test_les_headers_requis_sont_transmis_a_java():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_OK)

    client = _client_avec_handler(handler)
    client.validate_proposal(_proposal())

    requete = requetes_recues[0]
    assert requete.headers["X-Internal-Api-Key"] == "test-key"
    assert requete.headers["Content-Type"] == "application/json"
    assert "X-Request-Id" in requete.headers
    assert requete.url.path == "/api/v1/internal/ai-accounting/proposals/validate"


def test_ne_transporte_jamais_classification_ni_idempotency_key_vers_java():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_OK)

    client = _client_avec_handler(handler)
    client.validate_proposal(_proposal())

    corps = json.loads(requetes_recues[0].content)
    assert "classification" not in corps
    assert "idempotencyKey" not in corps
    assert corps["lines"][0]["purpose"] == "ACHAT_SERVICE"


def test_get_document_extraction_reussi_retourne_le_snapshot():
    document_id = uuid4()
    company_id = uuid4()

    def handler(request):
        assert request.url.path == f"/api/v1/internal/ai-accounting/documents/{document_id}"
        return httpx.Response(
            200,
            json={
                "documentId": str(document_id),
                "companyId": str(company_id),
                "typeDocument": "FACTURE_ACHAT",
                "montantHt": "100000",
                "montantTva": "18000",
                "montantTtc": "118000",
                "fournisseur": "Station Total",
                "devise": "XOF",
                "modePaiement": "VIREMENT",
                "confiance": 0.95,
            },
        )

    client = _client_avec_handler(handler)
    snapshot = client.get_document_extraction(document_id)

    assert isinstance(snapshot, DocumentExtractionSnapshot)
    assert snapshot.document_id == document_id
    assert snapshot.fournisseur == "Station Total"
    assert snapshot.montant_ttc == Decimal(118000)


def test_get_document_extraction_document_introuvable_est_une_erreur_de_validation():
    def handler(request):
        return httpx.Response(404, json={"message": "Document introuvable"})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingValidationError) as exc_info:
        client.get_document_extraction(uuid4())

    assert exc_info.value.status_code == 404
    assert exc_info.value.human_reviewable is True


def test_get_document_extraction_reponse_malformee_est_rejetee():
    def handler(request):
        return httpx.Response(200, json={"pas": "un snapshot valide"})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingResponseMalformedError):
        client.get_document_extraction(uuid4())


def test_post_entry_reussi_retourne_le_posting_result_de_java():
    def handler(request):
        assert request.url.path == "/api/v1/internal/ai-accounting/proposals/post"
        return httpx.Response(200, json=_REPONSE_POSTED)

    client = _client_avec_handler(handler)
    resultat = client.post_entry(_proposal())

    assert isinstance(resultat, PostingResult)
    assert resultat.posted is True
    assert resultat.entry_number == "ACH-2026-0001"
    assert resultat.journal == "ACH"


def test_post_entry_transporte_date_piece_et_libelle_jamais_classification_ni_idempotency_key():
    requetes_recues = []

    def handler(request):
        requetes_recues.append(request)
        return httpx.Response(200, json=_REPONSE_POSTED)

    client = _client_avec_handler(handler)
    client.post_entry(_proposal())

    corps = json.loads(requetes_recues[0].content)
    assert "classification" not in corps
    assert "idempotencyKey" not in corps
    assert "datePiece" in corps
    assert "libelle" in corps


def test_post_entry_422_devient_une_erreur_de_validation_human_reviewable_et_non_retryable():
    appels = []

    def handler(request):
        appels.append(request)
        return httpx.Response(422, json={"message": "Compte fournisseur non configure"})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingValidationError) as exc_info:
        client.post_entry(_proposal())

    assert exc_info.value.retryable is False
    assert exc_info.value.human_reviewable is True
    assert len(appels) == 1


def test_post_entry_timeout_est_retryable_et_reessaie_trois_fois():
    appels = []

    def handler(request):
        appels.append(request)
        raise httpx.ReadTimeout("delai depasse", request=request)

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingTimeoutError):
        client.post_entry(_proposal())

    assert len(appels) == 3


def test_post_entry_reponse_malformee_est_rejetee_jamais_retryable():
    def handler(request):
        return httpx.Response(200, json={"pas": "un posting result valide"})

    client = _client_avec_handler(handler)
    with pytest.raises(AccountingResponseMalformedError) as exc_info:
        client.post_entry(_proposal())

    assert exc_info.value.retryable is False


def test_post_entry_rejeu_apres_timeout_puis_succes_ne_leve_aucune_erreur():
    # Simule un retry apres un timeout : Java etant idempotent (source_type/source_id/type), un
    # rejeu de post_entry ne doit jamais faire echouer le client - la garantie de non-duplication
    # reste entierement cote Java, ce test verifie seulement que Python ne bloque pas ce rejeu.
    appels = []

    def handler(request):
        appels.append(request)
        if len(appels) == 1:
            raise httpx.ReadTimeout("delai depasse", request=request)
        return httpx.Response(200, json=_REPONSE_POSTED)

    client = _client_avec_handler(handler)
    resultat = client.post_entry(_proposal())

    assert resultat.posted is True
    assert len(appels) == 2
