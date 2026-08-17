from datetime import date
from decimal import Decimal

from app.schemas.matching import MatchingCandidate, MatchingSource, SourceType
from app.services.matching_prefilter import filtrer_candidats


def _source() -> MatchingSource:
    return MatchingSource(
        type=SourceType.BANK_TRANSACTION, id="tx-1", date=date(2026, 6, 18),
        amount=Decimal("485000"), currency="XOF",
    )


def _candidat(id_, candidat_date, devise="XOF") -> MatchingCandidate:
    return MatchingCandidate(
        type=SourceType.SUPPLIER_INVOICE, id=id_, date=candidat_date,
        amount=Decimal("485000"), currency=devise,
    )


def test_elimine_les_candidats_hors_fenetre_de_dates():
    candidats = [
        _candidat("proche", date(2026, 6, 16)),
        _candidat("loin", date(2026, 1, 1)),
    ]
    resultat = filtrer_candidats(_source(), candidats, fenetre_jours=30)
    ids = {c.id for c in resultat}
    assert "proche" in ids
    assert "loin" not in ids


def test_elimine_les_candidats_dune_autre_devise():
    candidats = [
        _candidat("xof", date(2026, 6, 16), devise="XOF"),
        _candidat("eur", date(2026, 6, 16), devise="EUR"),
    ]
    resultat = filtrer_candidats(_source(), candidats, fenetre_jours=30)
    ids = {c.id for c in resultat}
    assert "xof" in ids
    assert "eur" not in ids


def test_ne_penalise_pas_un_candidat_sans_date():
    candidat = MatchingCandidate(type=SourceType.SUPPLIER_INVOICE, id="sans-date", amount=Decimal("485000"))
    resultat = filtrer_candidats(_source(), [candidat], fenetre_jours=30)
    assert len(resultat) == 1


def test_deduplique_les_candidats_en_doublon():
    candidat = _candidat("fac-452", date(2026, 6, 16))
    resultat = filtrer_candidats(_source(), [candidat, candidat], fenetre_jours=30)
    assert len(resultat) == 1
