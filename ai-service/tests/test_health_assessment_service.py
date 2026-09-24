"""STEP 8.9 : FinancialHealthAssessmentService combine un statut deja decide (deterministe) avec
une interpretation narrative produite par le LLM - jamais l'inverse. Inclut le test
anti-hallucination mandate par le plan (section 28) : le systeme ne doit ni demander, ni permettre
au LLM de deviner des metriques (DSO, cash runway, croissance) qui ne lui ont pas ete fournies."""

from datetime import date
from decimal import Decimal

import pytest

from app.finance.health_assessment_service import (
    FinancialHealthAssessmentService,
    FinancialHealthLlmError,
)
from app.schemas.finance import (
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
)
from app.schemas.financial_health import FinancialHealthAssessment, FinancialHealthStatus
from app.services.mock_llm_provider import MockLLMProvider


def _snapshot() -> FinancialSnapshotWithMargins:
    return FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 31),
            cash=Decimal(12500000), receivables=Decimal(21800000), payables=Decimal(9400000),
            revenue=Decimal(35000000), expenses=Decimal(30800000), profit=Decimal(4200000), taxes=Decimal(0),
        ),
        net_margin=Decimal("0.1200"),
    )


def _cash() -> CashPosition:
    return CashPosition(date_reference=date(2026, 8, 27), total=Decimal(12500000), par_compte=[])


def _receivables() -> Receivables:
    return Receivables(
        as_of=date(2026, 8, 27), total=Decimal(21800000), current=Decimal(15000000), overdue=Decimal(6800000),
        aging_0_30=Decimal(15000000), aging_31_60=Decimal(5000000), aging_61_90=Decimal(1000000), aging_plus_90=Decimal(800000),
    )


def _payables() -> Payables:
    return Payables(
        as_of=date(2026, 8, 27), total=Decimal(9400000), current=Decimal(9400000), overdue=Decimal(0),
        aging_0_30=Decimal(9400000), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )


def test_evaluer_combine_le_statut_deterministe_avec_linterpretation_llm():
    llm = MockLLMProvider(
        responses=[
            {
                "signals": ["Les creances (21.8M) representent 1.7x la tresorerie disponible (12.5M)."],
                "risks": ["Dependance forte au recouvrement des creances clients."],
                "positive_factors": ["Profit positif sur la periode (4.2M)."],
                "priority_actions": ["Prioriser le recouvrement des creances en retard (6.8M)."],
                "data_completeness_notes": [],
            }
        ]
    )
    service = FinancialHealthAssessmentService(llm_provider=llm)

    resultat = service.evaluer(
        status=FinancialHealthStatus.WATCH, snapshot=_snapshot(), cash_position=_cash(),
        receivables=_receivables(), payables=_payables(),
    )

    assert isinstance(resultat, FinancialHealthAssessment)
    # Le statut vient de l'appelant (deterministe), jamais du LLM - meme si le LLM n'en produit pas.
    assert resultat.status is FinancialHealthStatus.WATCH
    assert resultat.period == "2026-08-01/2026-08-31"
    assert resultat.as_of == date(2026, 8, 27)
    assert "recouvrement" in resultat.priority_actions[0].lower()


def test_le_llm_ne_peut_jamais_changer_le_statut_deja_decide():
    # Meme si le LLM tentait d'inclure un "status" different dans sa reponse brute, le contrat
    # d'appel ecrase toujours ce champ avec la valeur deterministe (voir evaluer() : status.value
    # est fusionne APRES **brut) - jamais l'inverse.
    llm = MockLLMProvider(
        responses=[
            {
                "status": "WATCH",  # tente de contredire le CRITICAL deja decide
                "signals": [], "risks": [], "positive_factors": [], "priority_actions": [],
            }
        ]
    )
    service = FinancialHealthAssessmentService(llm_provider=llm)

    resultat = service.evaluer(
        status=FinancialHealthStatus.CRITICAL, snapshot=_snapshot(), cash_position=_cash(),
        receivables=_receivables(), payables=_payables(),
    )

    assert resultat.status is FinancialHealthStatus.CRITICAL


def test_reponse_llm_non_conforme_leve_financial_health_llm_error_jamais_masquee():
    llm = MockLLMProvider(responses=[{"signals": "ceci devrait etre une liste, pas une chaine"}])
    service = FinancialHealthAssessmentService(llm_provider=llm)

    with pytest.raises(FinancialHealthLlmError):
        service.evaluer(
            status=FinancialHealthStatus.WATCH, snapshot=_snapshot(), cash_position=_cash(),
            receivables=_receivables(), payables=_payables(),
        )


def test_panne_llm_leve_financial_health_llm_error_jamais_masquee():
    llm = MockLLMProvider(responses=[TimeoutError("panne simulee")])
    service = FinancialHealthAssessmentService(llm_provider=llm)

    with pytest.raises(FinancialHealthLlmError):
        service.evaluer(
            status=FinancialHealthStatus.AT_RISK, snapshot=_snapshot(), cash_position=_cash(),
            receivables=_receivables(), payables=_payables(),
        )


def test_anti_hallucination_le_prompt_ne_mentionne_jamais_de_metrique_non_fournie():
    # STEP 8, section 28 du plan : le systeme ne doit ni demander, ni suggerer au LLM de calculer
    # des metriques qui ne lui sont pas explicitement fournies (DSO, cash runway, croissance). Le
    # system prompt cite ces termes UNIQUEMENT comme exemples negatifs explicites ("n'invente
    # jamais un DSO...") - ce test verifie donc le texte utilisateur (les faits transmis pour cet
    # appel precis), qui ne doit lui jamais introduire ces concepts comme s'ils etaient disponibles.
    llm = MockLLMProvider(
        responses=[{"signals": [], "risks": [], "positive_factors": [], "priority_actions": []}]
    )
    service = FinancialHealthAssessmentService(llm_provider=llm)

    service.evaluer(
        status=FinancialHealthStatus.WATCH, snapshot=_snapshot(), cash_position=_cash(),
        receivables=_receivables(), payables=_payables(),
    )

    appel = llm.calls[0]
    texte_utilisateur = appel["user_text"].lower()
    for terme_invente in ("dso", "runway", "croissance", "growth"):
        assert terme_invente not in texte_utilisateur, f"le texte utilisateur ne devrait jamais mentionner '{terme_invente}'"

    # Le system prompt doit explicitement interdire au LLM d'inventer ces metriques de son cote.
    assert "n'invente jamais" in appel["system_prompt"].lower()


def test_anti_hallucination_seuls_les_chiffres_fournis_apparaissent_dans_le_texte_utilisateur():
    llm = MockLLMProvider(
        responses=[{"signals": [], "risks": [], "positive_factors": [], "priority_actions": []}]
    )
    service = FinancialHealthAssessmentService(llm_provider=llm)

    service.evaluer(
        status=FinancialHealthStatus.WATCH, snapshot=_snapshot(), cash_position=_cash(),
        receivables=_receivables(), payables=_payables(),
    )

    texte = llm.calls[0]["user_text"]
    assert "35000000" in texte  # revenue
    assert "30800000" in texte  # expenses
    assert "4200000" in texte  # profit
    assert "12500000" in texte  # cash
    assert "21800000" in texte  # receivables
    assert "9400000" in texte  # payables
