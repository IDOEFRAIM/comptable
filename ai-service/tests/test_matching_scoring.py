from datetime import date
from decimal import Decimal

from app.schemas.matching import MatchingCandidate, MatchingSource, SourceType
from app.services.matching_scoring import calculer_scores, score_amount, score_date, score_status


def _source(amount="485000", jour=18) -> MatchingSource:
    return MatchingSource(
        type=SourceType.BANK_TRANSACTION,
        id="tx-1",
        date=date(2026, 6, jour),
        amount=Decimal(amount),
        label="VIR ABC SARL FAC452",
        counterparty="ABC SARL",
    )


def _candidat(amount="485000", jour=16, statut=None, counterparty="ABC SARL", reference=None) -> MatchingCandidate:
    return MatchingCandidate(
        type=SourceType.SUPPLIER_INVOICE,
        id="fac-452",
        date=date(2026, 6, jour),
        amount=Decimal(amount),
        counterparty=counterparty,
        status=statut,
        reference=reference,
    )


def test_score_amount_exact():
    score, exact = score_amount(_source(), _candidat())
    assert score == 1.0
    assert exact is True


def test_score_amount_ecart_faible_coherent_avec_des_frais():
    score, exact = score_amount(_source("500000"), _candidat("495000"))
    assert exact is False
    assert 0.5 < score < 1.0


def test_score_amount_paiement_partiel():
    # Source (paiement) plus petite que le candidat (facture) : paiement partiel plausible.
    score, exact = score_amount(_source("300000"), _candidat("500000"))
    assert exact is False
    assert 0.0 < score < 0.6


def test_score_amount_source_plus_grande_que_le_candidat_jamais_partiel():
    score, exact = score_amount(_source("900000"), _candidat("500000"))
    assert exact is False
    assert score == 0.0


def test_score_date_meme_jour():
    assert score_date(_source(jour=18), _candidat(jour=18)) == 1.0


def test_score_date_proche():
    assert score_date(_source(jour=18), _candidat(jour=16)) >= 0.7


def test_score_date_eloignee():
    assert score_date(_source(jour=18), _candidat(jour=1)) <= 0.2


def test_score_status_facture_impayee_favorisee():
    assert score_status(_candidat(statut="UNPAID")) == 1.0
    assert score_status(_candidat(statut="PAID")) < 0.5


def test_score_status_absent_nest_pas_un_signal():
    assert score_status(_candidat(statut=None)) is None


def test_calculer_scores_composite_eleve_pour_un_match_evident():
    # Meme jour, meme montant, meme tiers, reference explicitement retrouvee dans le libelle :
    # c'est exactement le profil qu'exploite le court-circuit deterministe du moteur (section 24).
    scores = calculer_scores(_source(jour=18), _candidat(jour=18, reference="FAC452"))
    assert scores.montant_exact is True
    assert scores.reference_trouvee is True
    assert scores.composite() > 0.9


def test_calculer_scores_composite_bas_pour_un_candidat_sans_rapport():
    source = _source()
    candidat = MatchingCandidate(
        type=SourceType.SUPPLIER_INVOICE,
        id="fac-999",
        date=date(2026, 1, 1),
        amount=Decimal("12000"),
        counterparty="ZZZ INDUSTRIES",
    )
    scores = calculer_scores(source, candidat)
    assert scores.composite() < 0.3
