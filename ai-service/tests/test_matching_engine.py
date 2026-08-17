from datetime import date
from decimal import Decimal

from app.schemas.matching import (
    MatchDecision,
    MatchingCandidate,
    MatchingContext,
    MatchingRequest,
    MatchingSource,
    MatchType,
    SourceType,
)
from app.services import matching_engine
from app.services.mock_llm_provider import MockLLMProvider


def _source(**overrides) -> MatchingSource:
    defaults = dict(
        type=SourceType.BANK_TRANSACTION,
        id="tx-123",
        date=date(2026, 6, 18),
        amount=Decimal("-485000"),
        currency="XOF",
        label="VIR ABC SARL FAC452",
        counterparty="ABC SARL",
    )
    defaults.update(overrides)
    return MatchingSource(**defaults)


def _candidat(id_, **overrides) -> MatchingCandidate:
    defaults = dict(
        type=SourceType.SUPPLIER_INVOICE,
        id=id_,
        date=date(2026, 6, 16),
        amount=Decimal("485000"),
        currency="XOF",
        counterparty="ABC SARL",
        status="UNPAID",
    )
    defaults.update(overrides)
    return MatchingCandidate(**defaults)


# --- Cas evident : court-circuit deterministe, aucun appel LLM ---


def test_exact_match_evident_ne_declenche_aucun_appel_llm():
    # Meme jour, meme montant, meme tiers, reference retrouvee : le cas le plus net possible,
    # le scoring deterministe seul suffit (pas besoin d'appeler Claude).
    candidat = _candidat("fac-452", date=date(2026, 6, 18), reference="FAC452")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    provider = MockLLMProvider()
    reponse = matching_engine.analyze(requete, llm_provider=provider)

    assert provider.calls == []
    assert reponse.decision == MatchDecision.MATCH_PROPOSED
    assert reponse.match_type == MatchType.ONE_TO_ONE
    assert reponse.matches[0].candidate_id == "fac-452"
    assert reponse.requires_human_review is False
    assert "exact_amount" in reponse.reasons


def test_reference_transactionnelle_avec_decalage_de_date_part_au_llm():
    # Meme scenario que le critere de reussite de la mission (section 31) : date decalee de 2
    # jours, donc pas assez "evident" pour le court-circuit deterministe (volontairement
    # conservateur) - passe par le LLM, ici mocke pour renvoyer exactement l'exemple de la mission.
    candidat = _candidat("FAC-452", reference="FAC452")  # date par defaut : 16/06, source 18/06
    autre = _candidat("FAC-461", date=date(2026, 6, 12), amount=Decimal("500000"), status="UNPAID")
    requete = MatchingRequest(source=_source(), candidates=[candidat, autre])

    raw = {
        "decision": "MATCH_PROPOSED",
        "matchType": "ONE_TO_ONE",
        "confidence": 0.97,
        "matches": [{"candidateId": "FAC-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.97}],
        "reasons": ["exact_amount", "supplier_match", "date_proximity", "invoice_unpaid", "reference_match"],
        "warnings": [],
    }
    provider = MockLLMProvider(responses=[raw])
    reponse = matching_engine.analyze(requete, llm_provider=provider)

    assert len(provider.calls) == 1
    assert reponse.decision == MatchDecision.MATCH_PROPOSED
    assert reponse.confidence == 0.97
    assert reponse.matches[0].candidate_id == "FAC-452"
    assert reponse.requires_human_review is False


# --- Cas ambigus/moins nets : passent par le LLM (mocke) ---


def test_nom_fournisseur_similaire_mais_pas_identique_part_au_llm():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED",
        "matchType": "ONE_TO_ONE",
        "confidence": 0.9,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.9}],
        "reasons": ["exact_amount", "supplier_match"],
        "warnings": [],
    }
    provider = MockLLMProvider(responses=[raw])
    reponse = matching_engine.analyze(requete, llm_provider=provider)

    assert len(provider.calls) == 1
    assert reponse.decision == MatchDecision.MATCH_PROPOSED
    assert reponse.matches[0].candidate_id == "fac-452"


def test_date_proche_sans_reference_part_au_llm():
    candidat = _candidat("fac-452", date=date(2026, 6, 10), reference=None)
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED",
        "matchType": "ONE_TO_ONE",
        "confidence": 0.8,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.8}],
        "reasons": ["exact_amount", "date_proximity"],
        "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.MATCH_PROPOSED
    assert reponse.confidence == 0.8


def test_paiement_partiel():
    candidat = _candidat("fac-452", amount=Decimal("500000"))
    requete = MatchingRequest(source=_source(amount=Decimal("-300000")), candidates=[candidat])

    raw = {
        "decision": "PARTIAL_MATCH",
        "matchType": "PARTIAL_PAYMENT",
        "confidence": 0.8,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.8}],
        "reasons": ["partial_amount", "supplier_match"],
        "warnings": ["Montant inferieur a la facture : solde restant a verifier."],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.PARTIAL_MATCH
    assert reponse.match_type == MatchType.PARTIAL_PAYMENT
    assert reponse.requires_human_review is True


def test_plusieurs_factures_correspondent_ensemble_one_to_many():
    facture_a = _candidat("fac-a", amount=Decimal("600000"))
    facture_b = _candidat("fac-b", amount=Decimal("400000"))
    requete = MatchingRequest(source=_source(amount=Decimal("-1000000")), candidates=[facture_a, facture_b])

    raw = {
        "decision": "MATCH_PROPOSED",
        "matchType": "ONE_TO_MANY",
        "confidence": 0.9,
        "matches": [
            {"candidateId": "fac-a", "candidateType": "SUPPLIER_INVOICE", "score": 0.9},
            {"candidateId": "fac-b", "candidateType": "SUPPLIER_INVOICE", "score": 0.85},
        ],
        "reasons": ["sum_matches_amount", "supplier_match"],
        "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.match_type == MatchType.ONE_TO_MANY
    assert {m.candidate_id for m in reponse.matches} == {"fac-a", "fac-b"}


def test_paiement_avec_frais():
    candidat = _candidat("fac-452", amount=Decimal("500000"))
    requete = MatchingRequest(source=_source(amount=Decimal("-495000")), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED",
        "matchType": "PAYMENT_WITH_FEES",
        "confidence": 0.88,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.88}],
        "reasons": ["possible_fees", "supplier_match"],
        "warnings": ["Ecart de 5000 XOF, coherent avec des frais bancaires."],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.match_type == MatchType.PAYMENT_WITH_FEES
    assert reponse.warnings


def test_aucun_match_est_un_resultat_valide():
    candidat = _candidat("fac-999", counterparty="ZZZ INDUSTRIES", amount=Decimal("12000"), date=date(2026, 6, 17))
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "NO_MATCH", "matchType": "NONE", "confidence": 0.1,
        "matches": [], "reasons": ["no_plausible_candidate"], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.NO_MATCH
    assert reponse.matches == []
    assert reponse.requires_human_review is False


def test_candidats_ambigus_forcent_la_revue_humaine():
    fac_452 = _candidat("FAC-452", reference=None)
    fac_501 = _candidat("FAC-501", counterparty="XYZ SARL")
    requete = MatchingRequest(source=_source(), candidates=[fac_452, fac_501])

    raw = {
        "decision": "AMBIGUOUS_MATCH", "matchType": "NONE", "confidence": 0.61,
        "matches": [
            {"candidateId": "FAC-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.63},
            {"candidateId": "FAC-501", "candidateType": "SUPPLIER_INVOICE", "score": 0.60},
        ],
        "reasons": ["same_amount", "multiple_candidates", "insufficient_reference"],
        "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.AMBIGUOUS_MATCH
    assert reponse.requires_human_review is True
    assert len(reponse.matches) == 2


def test_mobile_money_wave():
    source = _source(
        type=SourceType.MOBILE_MONEY_TRANSACTION,
        label="WAVE P2P 22507xxxxxx KOUASSI A",
        counterparty=None,
    )
    candidat = _candidat("ecr-1", type=SourceType.ACCOUNTING_ENTRY, counterparty="KOUASSI A", reference=None)
    requete = MatchingRequest(source=source, candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.82,
        "matches": [{"candidateId": "ecr-1", "candidateType": "ACCOUNTING_ENTRY", "score": 0.82}],
        "reasons": ["exact_amount", "supplier_match"], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.MATCH_PROPOSED


def test_virement_interne():
    source = _source(type=SourceType.BANK_TRANSACTION, amount=Decimal("-1000000"), counterparty=None, label="VIR COMPTE B")
    candidat = _candidat(
        "tx-compte-b", type=SourceType.BANK_TRANSACTION, amount=Decimal("1000000"), counterparty=None, status=None,
    )
    requete = MatchingRequest(source=source, candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "INTERNAL_TRANSFER", "confidence": 0.85,
        "matches": [{"candidateId": "tx-compte-b", "candidateType": "BANK_TRANSACTION", "score": 0.85}],
        "reasons": ["internal_transfer_pattern"], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.match_type == MatchType.INTERNAL_TRANSFER


# --- Garde-fous : jamais confiance a la sortie brute du modele ---


def test_llm_qui_invente_un_id_est_rejete_integralement():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.95,
        "matches": [{"candidateId": "fac-INVENTE", "candidateType": "SUPPLIER_INVOICE", "score": 0.95}],
        "reasons": ["exact_amount"], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.AMBIGUOUS_MATCH
    assert reponse.matches == []
    assert reponse.requires_human_review is True
    assert "invalid_ai_output" in reponse.reasons


def test_llm_qui_invente_un_type_est_rejete_integralement():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.95,
        "matches": [{"candidateId": "fac-452", "candidateType": "CUSTOMER_INVOICE", "score": 0.95}],
        "reasons": [], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.AMBIGUOUS_MATCH
    assert reponse.matches == []


def test_json_llm_invalide_degrade_proprement():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    provider = MockLLMProvider(responses=[ValueError("reponse non parsable")])
    reponse = matching_engine.analyze(requete, llm_provider=provider)

    assert reponse.decision == MatchDecision.NO_MATCH
    assert reponse.requires_human_review is True
    assert "llm_unavailable" in reponse.reasons


def test_timeout_anthropic_degrade_proprement():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    provider = MockLLMProvider(responses=[TimeoutError("timeout")])
    reponse = matching_engine.analyze(requete, llm_provider=provider)

    assert reponse.decision == MatchDecision.NO_MATCH
    assert reponse.requires_human_review is True


def test_reponse_vide_du_llm_degrade_proprement():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[{}]))

    assert reponse.decision != MatchDecision.MATCH_PROPOSED
    assert reponse.matches == []


def test_aucun_candidat_apres_filtrage_ne_declenche_aucun_appel_llm():
    candidat = _candidat("trop-vieux", date=date(2020, 1, 1))
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    provider = MockLLMProvider()
    reponse = matching_engine.analyze(requete, llm_provider=provider)

    assert provider.calls == []
    assert reponse.decision == MatchDecision.NO_MATCH
    assert "no_candidates_after_prefilter" in reponse.reasons


def test_doublons_de_candidats_deduplique_avant_le_llm():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat, candidat, candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.9,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.9}],
        "reasons": ["exact_amount"], "warnings": [],
    }
    provider = MockLLMProvider(responses=[raw])
    matching_engine.analyze(requete, llm_provider=provider)

    # Un seul bloc candidat doit avoir ete envoye au modele malgre les 3 doublons en entree.
    user_text = provider.calls[-1]["user_text"]
    assert user_text.count("ID: fac-452") == 1


def test_confiance_sous_le_seuil_de_revue_est_retrogradee_en_no_match():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.5,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.5}],
        "reasons": ["weak_signal"], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.NO_MATCH
    assert reponse.matches == []
    assert "confidence_below_review_threshold" in reponse.warnings


def test_confiance_entre_les_deux_seuils_necessite_une_revue_humaine():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    requete = MatchingRequest(source=_source(), candidates=[candidat])

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.80,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.80}],
        "reasons": ["supplier_match"], "warnings": [],
    }
    reponse = matching_engine.analyze(requete, llm_provider=MockLLMProvider(responses=[raw]))

    assert reponse.decision == MatchDecision.MATCH_PROPOSED
    assert reponse.requires_human_review is True


def test_contexte_historique_est_transmis_au_prompt():
    candidat = _candidat("fac-452", counterparty="A.B.C SARL")
    contexte = MatchingContext(history=[{"amount": "490000", "date": "2026-05-18", "method": "BANK"}])
    requete = MatchingRequest(source=_source(), candidates=[candidat], context=contexte)

    raw = {
        "decision": "MATCH_PROPOSED", "matchType": "ONE_TO_ONE", "confidence": 0.9,
        "matches": [{"candidateId": "fac-452", "candidateType": "SUPPLIER_INVOICE", "score": 0.9}],
        "reasons": ["exact_amount"], "warnings": [],
    }
    provider = MockLLMProvider(responses=[raw])
    matching_engine.analyze(requete, llm_provider=provider)

    user_text = provider.calls[-1]["user_text"]
    assert "CONTEXTE" in user_text
    assert "490000" in user_text
