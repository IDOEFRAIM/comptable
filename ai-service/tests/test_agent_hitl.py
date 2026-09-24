import pytest
from langgraph.types import Command

from app.agent.hitl import (
    ACQUITTEMENT_SANS_CORRECTION,
    acquitter_sans_correction,
    est_acquittement_sans_correction,
    reprendre_avec_correction,
)


def test_acquitter_sans_correction_produit_une_valeur_non_dict():
    commande = acquitter_sans_correction()
    assert isinstance(commande, Command)
    # Jamais un dict (piege Command(resume={}) qui ne debloque rien) ni None (UnboundLocalError).
    assert not isinstance(commande.resume, dict)
    assert commande.resume is not None
    assert commande.resume == ACQUITTEMENT_SANS_CORRECTION


def test_reprendre_avec_correction_transporte_le_dict_fourni():
    correction = {"classification": {"confidence": 0.99}}
    commande = reprendre_avec_correction(correction)
    assert isinstance(commande, Command)
    assert commande.resume == correction


def test_reprendre_avec_correction_refuse_un_dict_vide():
    # Un dict vide ne debloquerait jamais le workflow (piege documente) - refuse explicitement
    # plutot que de laisser un appelant se piquer silencieusement dessus.
    with pytest.raises(ValueError):
        reprendre_avec_correction({})


def test_est_acquittement_sans_correction_reconnait_la_sentinelle():
    assert est_acquittement_sans_correction(ACQUITTEMENT_SANS_CORRECTION) is True
    assert est_acquittement_sans_correction({"classification": {}}) is False
    assert est_acquittement_sans_correction(None) is False
    assert est_acquittement_sans_correction({}) is False
