from datetime import date
from decimal import Decimal
from unittest.mock import patch

from app.schemas.matching import MatchDecision, MatchingResponse, MatchProposal, MatchType, SourceType
from app.schemas.rapprochement import LigneEcritureCandidate, LigneReleve
from app.services import rapprochement_service


def _ligne(id_, montant) -> LigneReleve:
    return LigneReleve(id=id_, date_operation=date(2027, 1, 5), montant=Decimal(montant), libelle="WAVE P2P KOUASSI A")


def _candidat(id_, debit="0", credit="0", tiers="Kouassi A.") -> LigneEcritureCandidate:
    return LigneEcritureCandidate(
        id=id_, date_ecriture=date(2027, 1, 5), debit=Decimal(debit), credit=Decimal(credit),
        libelle="Vente au comptant", tiers=tiers,
    )


def _reponse_match(candidate_id: str, confiance: float, candidate_type=SourceType.ACCOUNTING_ENTRY) -> MatchingResponse:
    return MatchingResponse(
        decision=MatchDecision.MATCH_PROPOSED,
        confidence=confiance,
        match_type=MatchType.ONE_TO_ONE,
        matches=[MatchProposal(candidate_id=candidate_id, candidate_type=candidate_type, score=confiance)],
        reasons=["exact_amount", "supplier_match"],
        warnings=[],
        requires_human_review=False,
    )


def _reponse_no_match() -> MatchingResponse:
    return MatchingResponse(
        decision=MatchDecision.NO_MATCH, confidence=0.0, match_type=MatchType.NONE,
        matches=[], reasons=["no_plausible_candidate"], warnings=[], requires_human_review=False,
    )


def test_retient_un_appariement_au_dessus_du_seuil():
    import uuid

    ligne_id, candidat_id = uuid.uuid4(), uuid.uuid4()
    ligne = _ligne(ligne_id, "15000")
    candidat = _candidat(candidat_id, debit="15000")

    with patch("app.services.rapprochement_service.analyze", return_value=_reponse_match(str(candidat_id), 0.95)):
        resultat = rapprochement_service.proposer_appariements([ligne], [candidat])

    assert len(resultat) == 1
    assert resultat[0].ligne_releve_id == ligne_id
    assert resultat[0].ligne_ecriture_id == candidat_id
    assert resultat[0].confiance == 0.95


def test_rejette_un_appariement_sous_le_seuil():
    import uuid

    ligne_id, candidat_id = uuid.uuid4(), uuid.uuid4()
    ligne = _ligne(ligne_id, "15000")
    candidat = _candidat(candidat_id, debit="15000")

    with patch("app.services.rapprochement_service.analyze", return_value=_reponse_match(str(candidat_id), 0.5)):
        resultat = rapprochement_service.proposer_appariements([ligne], [candidat])

    assert resultat == []


def test_no_match_ne_produit_aucun_appariement():
    import uuid

    ligne = _ligne(uuid.uuid4(), "15000")
    candidat = _candidat(uuid.uuid4(), debit="15000")

    with patch("app.services.rapprochement_service.analyze", return_value=_reponse_no_match()):
        resultat = rapprochement_service.proposer_appariements([ligne], [candidat])

    assert resultat == []


def test_exclut_les_candidats_de_direction_incompatible_avant_meme_dappeler_le_moteur():
    import uuid

    ligne = _ligne(uuid.uuid4(), "15000")  # positif = argent recu -> doit matcher un DEBIT
    candidat_credit = _candidat(uuid.uuid4(), credit="15000")  # credit, pas debit : incompatible

    with patch("app.services.rapprochement_service.analyze") as mock_engine:
        resultat = rapprochement_service.proposer_appariements([ligne], [candidat_credit])

    mock_engine.assert_not_called()
    assert resultat == []


def test_ne_reutilise_pas_deux_fois_la_meme_ecriture_priorite_a_la_plus_haute_confiance():
    import uuid

    ligne1, ligne2 = uuid.uuid4(), uuid.uuid4()
    candidat_id = uuid.uuid4()
    l1 = _ligne(ligne1, "15000")
    l2 = _ligne(ligne2, "15000")
    candidat = _candidat(candidat_id, debit="15000")

    # Les deux lignes se voient proposer le MEME candidat ; seule la plus confiante doit gagner.
    reponses = {str(ligne1): _reponse_match(str(candidat_id), 0.92), str(ligne2): _reponse_match(str(candidat_id), 0.98)}

    def fake_analyze(requete):
        return reponses[requete.source.id]

    with patch("app.services.rapprochement_service.analyze", side_effect=fake_analyze):
        resultat = rapprochement_service.proposer_appariements([l1, l2], [candidat])

    assert len(resultat) == 1
    assert resultat[0].ligne_releve_id == ligne2
    assert resultat[0].confiance == 0.98


def test_aucune_ligne_ou_aucun_candidat_ne_declenche_aucun_appel():
    import uuid

    with patch("app.services.rapprochement_service.analyze") as mock_engine:
        assert rapprochement_service.proposer_appariements([], []) == []
        assert rapprochement_service.proposer_appariements([_ligne(uuid.uuid4(), "1")], []) == []
    mock_engine.assert_not_called()
