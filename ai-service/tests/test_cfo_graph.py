"""STEP 11.7/11.10/11.13/11.14 : le graph CFO complet - execution multi-tools, write safety
(interrupt/resume via le HITL centralise), checkpoint de conversation."""

from datetime import date
from decimal import Decimal
from unittest.mock import MagicMock
from uuid import uuid4

from app.agent.checkpoint import creer_checkpointer_cfo
from app.agent.graph.cfo import (
    NODE_ANALYZE,
    NODE_CLASSIFY_INTENT,
    NODE_COLLECT_FACTS,
    NODE_EXECUTE_TOOL,
    NODE_FINALIZE,
    NODE_SELECT_TOOLS,
    build_cfo_graph,
)
from app.agent.hitl import reprendre_avec_correction
from app.agent.state.cfo import nouvel_etat_initial
from app.cfo.analysis import CfoAnalysisService
from app.schemas.accounting import (
    AccountingClassification,
    AccountingNatureLabel,
    AccountingOperationType,
    AccountingProposal,
    AccountPurpose,
    PostingResult,
    ProposedAccountingLine,
    TaxTreatment,
)
from app.schemas.finance import (
    CashPosition,
    FinancialSnapshot,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
)
from app.services.accounting_client import AccountingClient
from app.services.financial_client import FinancialClient
from app.services.mock_llm_provider import MockLLMProvider


def _proposal(company_id) -> AccountingProposal:
    classification = AccountingClassification(
        operation_type=AccountingOperationType.ACHAT, accounting_nature=AccountingNatureLabel.FUEL,
        tax_treatment=TaxTreatment.TVA_RECUPERABLE, confidence=0.95, requires_review=False,
    )
    return AccountingProposal(
        workflow_id=uuid4(), document_id=uuid4(), company_id=company_id,
        lines=[ProposedAccountingLine(purpose=AccountPurpose.ACHAT_SERVICE, montant_ht=Decimal(100000), designation="Presta")],
        montant_ttc=Decimal(118000), classification=classification, idempotency_key="k",
    )


def test_le_graph_contient_tous_les_noeuds_attendus():
    llm = MockLLMProvider(responses=[])
    graph = build_cfo_graph(
        accounting_client=MagicMock(spec=AccountingClient), financial_client=MagicMock(spec=FinancialClient),
        analysis_service=CfoAnalysisService(llm_provider=llm), llm_provider=llm,
    )

    assert {NODE_CLASSIFY_INTENT, NODE_SELECT_TOOLS, NODE_EXECUTE_TOOL, NODE_COLLECT_FACTS, NODE_ANALYZE, NODE_FINALIZE}.issubset(
        graph.nodes.keys()
    )


def test_write_action_sans_approbation_interrompt_puis_no_post_si_refuse():
    company_id = uuid4()
    accounting_client = MagicMock(spec=AccountingClient)
    financial_client = MagicMock(spec=FinancialClient)
    llm = MockLLMProvider(
        responses=[
            {"intent": "ACCOUNTING", "confidence": 0.9},
            {"tools": ["accounting.post_entry"]},
        ]
    )
    service = CfoAnalysisService(llm_provider=MockLLMProvider(
        responses=[{"summary": "Non postee, approbation refusee.", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}]
    ))
    graph = build_cfo_graph(
        accounting_client=accounting_client, financial_client=financial_client, analysis_service=service,
        llm_provider=llm, checkpointer=creer_checkpointer_cfo(),
    )
    workflow_id = uuid4()
    config = {"configurable": {"thread_id": str(workflow_id)}}
    state = nouvel_etat_initial(uuid4(), workflow_id, company_id, "Comptabilise cette facture.", pending_proposal=_proposal(company_id))

    resultat_interrompu = graph.invoke(state, config=config)

    assert "__interrupt__" in resultat_interrompu
    interruption = resultat_interrompu["__interrupt__"][0]
    assert interruption.value["type"] == "CFO_WRITE_APPROVAL"
    accounting_client.post_entry.assert_not_called()

    resultat_final = graph.invoke(reprendre_avec_correction({"approved": False}), config=config)

    assert "__interrupt__" not in resultat_final
    assert "posting_result" not in resultat_final
    accounting_client.post_entry.assert_not_called()
    assert resultat_final["response"].summary == "Non postee, approbation refusee."


def test_write_action_avec_approbation_poste_reellement():
    company_id = uuid4()
    accounting_client = MagicMock(spec=AccountingClient)
    accounting_client.post_entry.return_value = PostingResult(posted=True, entry_number="ACH-2026-0001", journal="ACH")
    financial_client = MagicMock(spec=FinancialClient)
    llm = MockLLMProvider(
        responses=[
            {"intent": "ACCOUNTING", "confidence": 0.9},
            {"tools": ["accounting.post_entry"]},
        ]
    )
    service = CfoAnalysisService(llm_provider=MockLLMProvider(
        responses=[{"summary": "Ecriture postee.", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}]
    ))
    graph = build_cfo_graph(
        accounting_client=accounting_client, financial_client=financial_client, analysis_service=service,
        llm_provider=llm, checkpointer=creer_checkpointer_cfo(),
    )
    workflow_id = uuid4()
    config = {"configurable": {"thread_id": str(workflow_id)}}
    state = nouvel_etat_initial(uuid4(), workflow_id, company_id, "Comptabilise cette facture.", pending_proposal=_proposal(company_id))

    graph.invoke(state, config=config)
    resultat_final = graph.invoke(reprendre_avec_correction({"approved": True}), config=config)

    assert "__interrupt__" not in resultat_final
    assert resultat_final["posting_result"].posted is True
    accounting_client.post_entry.assert_called_once()


def test_scenario_question_degrade_proprement_sans_tool_invente():
    llm = MockLLMProvider(
        responses=[
            {"intent": "SCENARIO", "confidence": 0.85},
            {"tools": []},
        ]
    )
    service = CfoAnalysisService(llm_provider=MockLLMProvider(
        responses=[{
            "summary": "La simulation de scenario n'est pas encore disponible.",
            "analysis": [], "risks": [], "recommendations": [],
            "missing_data": ["scenario simulation not yet available"],
        }]
    ))
    graph = build_cfo_graph(
        accounting_client=MagicMock(spec=AccountingClient), financial_client=MagicMock(spec=FinancialClient),
        analysis_service=service, llm_provider=llm,
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Puis-je recruter deux commerciaux ?"))

    assert resultat["response"].tools_used == []
    assert any("scenario" in m.lower() for m in resultat["response"].missing_data)


def test_financial_health_reutilise_health_policy_bout_en_bout():
    # STEP 11, section 11.12 : "Comment va mon entreprise ?" doit reutiliser
    # app/finance/health_policy.py (deterministe, STEP 8) - jamais un statut invente par le LLM.
    # Fixture volontairement AT_RISK : payables (9.4M) > cash (3M).
    financial_client = MagicMock(spec=FinancialClient)
    financial_client.get_financial_snapshot.return_value = FinancialSnapshotWithMargins(
        snapshot=FinancialSnapshot(
            periode_debut=date(2026, 8, 1), periode_fin=date(2026, 8, 27),
            cash=Decimal(3000000), receivables=Decimal(5000000), payables=Decimal(9400000),
            revenue=Decimal(35000000), expenses=Decimal(30800000), profit=Decimal(4200000), taxes=Decimal(0),
        )
    )
    financial_client.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 27), total=Decimal(3000000), par_compte=[])
    financial_client.get_receivables.return_value = Receivables(
        as_of=date(2026, 8, 27), total=Decimal(5000000), current=Decimal(5000000), overdue=Decimal(0),
        aging_0_30=Decimal(5000000), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    financial_client.get_payables.return_value = Payables(
        as_of=date(2026, 8, 27), total=Decimal(9400000), current=Decimal(9400000), overdue=Decimal(0),
        aging_0_30=Decimal(9400000), aging_31_60=Decimal(0), aging_61_90=Decimal(0), aging_plus_90=Decimal(0),
    )
    llm = MockLLMProvider(
        responses=[
            {"intent": "FINANCIAL_HEALTH", "confidence": 0.95},
            {"tools": ["finance.get_financial_snapshot", "finance.get_cash_position", "finance.get_receivables", "finance.get_payables"]},
        ]
    )
    analysis_llm = MockLLMProvider(responses=[{"summary": "s", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}])
    service = CfoAnalysisService(llm_provider=analysis_llm)
    graph = build_cfo_graph(
        accounting_client=MagicMock(spec=AccountingClient), financial_client=financial_client,
        analysis_service=service, llm_provider=llm,
    )

    graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Comment va mon entreprise ?"))

    texte_transmis_au_llm_danalyse = analysis_llm.calls[0]["user_text"]
    assert "AT_RISK" in texte_transmis_au_llm_danalyse


def test_max_tool_calls_arrete_proprement_a_travers_le_graph_complet():
    # STEP 11, section 26 (guardrail MAX_TOOL_CALLS) - verifie a travers le VRAI graph compile
    # (pas seulement le node isole comme dans test_cfo_tool_loop.py) : le planner propose plus de
    # tools que la limite, le graph doit quand meme atteindre `analyze`/`finalize` proprement.
    financial_client = MagicMock(spec=FinancialClient)
    financial_client.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 27), total=Decimal(1), par_compte=[])
    financial_client.get_receivables.return_value = None
    financial_client.get_payables.return_value = None
    llm = MockLLMProvider(
        responses=[
            {"intent": "GENERAL_FINANCIAL", "confidence": 0.5},
            {"tools": ["finance.get_cash_position", "finance.get_receivables", "finance.get_payables"]},
        ]
    )
    service = CfoAnalysisService(llm_provider=MockLLMProvider(
        responses=[{"summary": "s", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}]
    ))
    graph = build_cfo_graph(
        accounting_client=MagicMock(spec=AccountingClient), financial_client=financial_client,
        analysis_service=service, llm_provider=llm, max_tool_calls=1,
    )

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), uuid4(), uuid4(), "Comment va mon entreprise ?"))

    assert resultat["response"] is not None
    assert len(resultat["tool_calls"]) == 1
    assert any("max_tool_calls_reached" in e for e in resultat["errors"])


def test_le_checkpointer_durci_fonctionne_pour_ce_graph():
    company_id = uuid4()
    financial_client = MagicMock(spec=FinancialClient)
    financial_client.get_cash_position.return_value = CashPosition(date_reference=date(2026, 8, 27), total=Decimal(12500000), par_compte=[])
    llm = MockLLMProvider(
        responses=[{"intent": "CASH_POSITION", "confidence": 0.9}, {"tools": ["finance.get_cash_position"]}]
    )
    service = CfoAnalysisService(llm_provider=MockLLMProvider(
        responses=[{"summary": "Cash actuel : 12.5M.", "analysis": [], "risks": [], "recommendations": [], "missing_data": []}]
    ))
    graph = build_cfo_graph(
        accounting_client=MagicMock(spec=AccountingClient), financial_client=financial_client, analysis_service=service,
        llm_provider=llm, checkpointer=creer_checkpointer_cfo(),
    )
    workflow_id = uuid4()
    config = {"configurable": {"thread_id": str(workflow_id)}}

    resultat = graph.invoke(nouvel_etat_initial(uuid4(), workflow_id, company_id, "Quel est mon cash ?"), config=config)

    assert resultat["response"] is not None
    etat_final = graph.get_state(config)
    assert etat_final.next == ()
